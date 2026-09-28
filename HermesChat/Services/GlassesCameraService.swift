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

    private var session: DeviceSession?
    private var camera: MWDATCamera.Camera?
    private let tokens = ListenerTokenBag()

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
            // 3) 세션
            onStatus?("글라스 연결 중…")
            let session = try wearables.createSession(deviceSelector: AutoDeviceSelector(wearables: wearables))
            self.session = session
            session.statePublisher.listen { [weak self] state in
                Task { @MainActor in if state == .stopped { self?.cleanup() } }
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
                    case .streaming: self?.onStatus?(nil); self?.onActiveChange?(true)
                    case .paused: self?.onStatus?("글라스 카메라 일시정지 — 착용·안경다리 확인")
                    case .stopped: self?.stop()
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
            stop()
        }
    }

    /// 멱등 — 세션 정지가 카메라·스트림까지 연쇄 정지시킨다
    func stop() {
        session?.stop()
        cleanup()
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
        onPreview?(image)
        guard Date().timeIntervalSince(lastSent) >= sendInterval,
              let jpeg = image.jpegData(compressionQuality: 0.6) else { return }
        lastSent = Date()
        onFrame?(jpeg)
    }
}
