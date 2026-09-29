import Foundation
import MWDATCamera
import MWDATCore
import UIKit

/// 레이밴 메타 글라스 카메라 실시간 스트림 (T-172) — Meta Wearables Device Access Toolkit(DAT, SPM 예외).
/// Live 탭에서 켜면 글라스 화면을 초당 1장 JPEG로 넘겨 Gemini Live가 "내가 보는 것"을 보게 한다.
///
/// 흐름: (최초 1회) Meta AI 앱 등록 → 카메라 권한 → DeviceSession 시작 → Camera 추가 → 스트림.
/// 등록·권한 요청은 Meta AI 앱으로 전환됐다가 `hermes://?metaWearablesAction=…` 콜백으로 돌아온다
/// (`HermesChatApp.handleDeepLink` → `handleURL`).
/// 전제: Meta AI 앱 개발자 모드 ON — 미게시 앱은 개발자 모드에서만 등록된다.
@MainActor
final class GlassesCameraService {
    /// 진행/오류 안내 문구 (nil = 지울 것)
    var onStatus: ((String?) -> Void)?
    /// 스트리밍 on/off
    var onActiveChange: ((Bool) -> Void)?
    /// 미리보기용 최신 프레임 (스트림 fps 그대로)
    var onPreview: ((UIImage) -> Void)?
    /// Gemini로 보낼 JPEG — `sendInterval`마다 1장
    var onFrame: ((Data) -> Void)?

    /// Gemini Live 권장: 초당 1장 이하
    private let sendInterval: TimeInterval = 1.0
    private var lastSent = Date.distantPast

    /// 반드시 오래 살려 두고 `activeDeviceStream()`을 계속 소비해야 SDK가 기기 적격성을 추적한다.
    /// 호출마다 새로 만들면 `noEligibleDevice`로 실패한다 (DAT 이슈 #148, 실기기 재현).
    private let deviceSelector = AutoDeviceSelector(wearables: Wearables.shared)
    private var deviceMonitor: Task<Void, Never>?
    private var hasActiveDevice = false
    private var session: DeviceSession?
    /// 글라스가 세션을 끊으면(`Session ended by device`) 1회 자동 재연결 — DAT 이슈 #301:
    /// 기기가 시작 후 수 초 만에 세션을 끊는 구간이 있고, 즉시 재생성하면 대개 회복된다.
    private var retriesLeft = 0
    private var framesReceived = 0
    private var camera: MWDATCamera.Camera?
    private let tokens = ListenerTokenBag()

    init() {
        deviceMonitor = Task { [weak self, deviceSelector] in
            for await deviceId in deviceSelector.activeDeviceStream() {
                self?.hasActiveDevice = deviceId != nil
            }
        }
    }

    deinit { deviceMonitor?.cancel() }

    /// 앱 시작 시 1회 (HermesChatApp.init)
    static func configureSDK() {
        do { try Wearables.configure() } catch { print("[GlassesCamera] configure 실패: \(error)") }
    }

    /// Meta AI 앱에서 돌아온 등록/권한 콜백이면 SDK에 넘긴다. 처리했으면 true.
    static func handleURL(_ url: URL) -> Bool {
        guard URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true else { return false }
        Task { _ = try? await Wearables.shared.handleUrl(url) }
        return true
    }

    // MARK: - 시작/정지

    func start() async {
        retriesLeft = 1
        await run()
    }

    private func run() async {
        guard session == nil else { return }
        let wearables = Wearables.shared
        do {
            // 1) 등록 — Meta AI 앱으로 전환된다. 돌아온 뒤 다시 켜면 다음 단계로 진행.
            if wearables.registrationState != .registered {
                onStatus?("Meta AI 앱에서 연결을 승인한 뒤 돌아와 다시 눌러주세요.")
                try await wearables.startRegistration()
                return
            }
            // 2) 카메라 권한 — 없으면 Meta AI 앱으로 전환해 요청
            if try await wearables.checkPermissionStatus(.camera) != .granted {
                guard try await wearables.requestPermission(.camera) == .granted else {
                    onStatus?("글라스 카메라 권한이 거부됐어요 (Meta AI 앱 > 앱 연결).")
                    return
                }
            }
            // 3) 세션 — 활성 글라스가 잡힐 때까지 최대 10초 대기
            onStatus?("글라스 연결 중…")
            for _ in 0..<20 where !hasActiveDevice {
                try await Task.sleep(for: .milliseconds(500))
            }
            guard hasActiveDevice else {
                onStatus?("글라스를 찾지 못했어요 — 착용·안경다리 펴짐·블루투스 연결을 확인하세요.")
                return
            }
            let session = try wearables.createSession(deviceSelector: deviceSelector)
            self.session = session
            session.statePublisher.listen { [weak self] state in
                Task { @MainActor in if state == .stopped { self?.sessionEndedByDevice() } }
            }.store(in: tokens)
            session.errorPublisher.listen { [weak self] error in
                Task { @MainActor in self?.onStatus?("글라스 오류: \(error.localizedDescription)") }
            }.store(in: tokens)
            let states = session.stateStream()
            try session.start()
            for await state in states {
                if state == .started { break }
                if state == .stopped { return }
            }
            onStatus?("글라스 연결됨 — 카메라 여는 중…")
            // 4) 카메라 스트림 — 저해상도·최저 fps가 블루투스 압축이 적어 화질이 가장 좋다
            let config = StreamConfiguration(videoCodec: .raw, resolution: .low, frameRate: 2)
            guard let camera = try session.addCamera(config: config) else {
                onStatus?("글라스 카메라를 열지 못했어요.")
                stop()
                return
            }
            self.camera = camera
            camera.stream.statePublisher.listen { [weak self] state in
                Task { @MainActor in
                    switch state {
                    case .waitingForDevice: self?.onStatus?("글라스 카메라 응답 대기 중…")
                    case .starting: self?.onStatus?("글라스 카메라 시작 중…")
                    case .streaming: self?.streamingStarted()
                    case .paused: self?.onStatus?("글라스 카메라 일시정지 — 착용·안경다리 확인")
                    case .stopped: self?.teardown()
                    default: break
                    }
                }
            }.store(in: tokens)
            camera.stream.errorPublisher.listen { [weak self] error in
                Task { @MainActor in self?.onStatus?("글라스 카메라 오류: \(error.localizedDescription)") }
            }.store(in: tokens)
            camera.stream.videoFramePublisher.listen { [weak self] frame in
                // 프레임 콜백은 백그라운드 스레드 — 이미지 변환은 여기서, UI·전송은 메인에서
                guard let image = frame.makeUIImage() else { return }
                Task { @MainActor in self?.deliver(image) }
            }.store(in: tokens)
            camera.stream.start()
        } catch {
            onStatus?("글라스 카메라 시작 실패: \(error.localizedDescription)")
            teardown()
        }
    }

    /// 사용자가 끔 — 자동 재연결하지 않는다
    func stop() {
        retriesLeft = 0
        teardown()
    }

    /// 멱등 — 세션 정지가 카메라·스트림까지 연쇄 정지시킨다. 리스너를 먼저 걷어 재연결을 막는다.
    private func teardown() {
        tokens.clear()
        session?.stop()
        cleanup()
    }

    /// 세션 리스너가 살아 있을 때 .stopped = 기기(글라스) 쪽에서 끊은 것
    private func sessionEndedByDevice() {
        cleanup()
        guard retriesLeft > 0 else { return }
        retriesLeft -= 1
        onStatus?("글라스가 연결을 끊어 다시 연결하는 중…")
        Task {
            try? await Task.sleep(for: .seconds(1))
            await run()
        }
    }

    private func streamingStarted() {
        onStatus?(nil)
        onActiveChange?(true)
        // .raw 디코더가 첫 프레임부터 멈추는 알려진 문제(DAT #287) — 화면이 안 오면 드러낸다
        let before = framesReceived
        Task {
            try? await Task.sleep(for: .seconds(5))
            if camera != nil, framesReceived == before {
                onStatus?("글라스 카메라는 켜졌지만 화면이 들어오지 않아요.")
            }
        }
    }

    private func cleanup() {
        guard session != nil || camera != nil else { return }
        tokens.clear()
        camera = nil
        session = nil
        lastSent = .distantPast
        onActiveChange?(false)
    }

    private func deliver(_ image: UIImage) {
        framesReceived += 1
        onPreview?(image)
        guard Date().timeIntervalSince(lastSent) >= sendInterval,
              let jpeg = image.jpegData(compressionQuality: 0.6) else { return }
        lastSent = Date()
        onFrame?(jpeg)
    }
}
