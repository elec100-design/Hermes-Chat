import AVFoundation
import Foundation
import Photos
import UIKit

/// 메타(레이밴) 글라스로 찍은 사진이 Meta AI 앱을 통해 아이폰 카메라 롤에 동기화되는 것을
/// 감지해 자동 첨부·전송 흐름으로 넘긴다 (Phase 16, T-125).
///
/// 배경: 글라스의 물리 '촬영' 버튼은 3rd-party 앱이 가로챌 수 없고(Meta Wearables DAT 제약),
/// DAT 카메라 스트림은 개발자 등록이 필요해 도입하지 않기로 했다. 대신 보관함 변화 감지로 우회한다.
/// 촬영→Meta AI 앱→카메라 롤 동기화에는 수초~수십초 지연이 있으므로 실시간이 아니다.
///
/// **전체 사진 접근(.authorized)이 필요**하다 — 제한 접근(.limited)에서는 사용자가 직접 고른
/// 사진만 보여 새 글라스 사진을 감지하지 못한다.
@MainActor
final class PhotoImportWatcher: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
    /// 새 사진 1건을 포착했을 때 (파일명, 이미지 데이터). 메인 액터에서 호출된다.
    var onNewPhoto: ((String, Data) -> Void)?
    /// 새 동영상 1건 (Live 탭 전용). nil이면 동영상은 무시한다 — 채팅 탭은 기존대로 사진만.
    /// 불러오기에 실패하면 nil — 호출부가 실패를 안내할 수 있게 한다.
    var onNewVideo: ((AVAsset?) -> Void)?
    /// 감지 단계의 진단 메시지 (실기기 디버깅용 — 조용한 실패를 화면에 드러낸다)
    var onNotice: ((String) -> Void)?

    @Published private(set) var isWatching = false

    /// 권한 요청 결과 — 제한 접근은 호출부가 안내하도록 구분한다.
    enum StartResult { case authorized, limited, denied }

    /// 감시 시작 시각 — 이보다 하루 이상 오래된 촬영물은 무시한다(iCloud로 옛 사진이 들어오는 경우 등)
    private var since = Date.distantFuture
    /// 변화 비교 기준 — 이 결과에 *새로 삽입된* 에셋만 새 촬영물로 본다
    private var fetchResult: PHFetchResult<PHAsset>?
    private var isRegistered = false

    /// 감시를 시작한다. **전체 접근**이 허용된 경우에만 `.authorized`를 반환하고 실제로 감시한다.
    func start(since date: Date) async -> StartResult {
        let status = await Self.requestAuthorization()
        switch status {
        case .authorized: break
        case .limited: return .limited
        default: return .denied
        }

        self.since = date
        // 시작 시점 보관함을 기준으로 잡는다 — 이후 변화에서 insertedObjects만 본다 (옛 사진 오발송 방지)
        fetchResult = Self.fetchMedia()
        if !isRegistered {
            PHPhotoLibrary.shared().register(self)
            isRegistered = true
        }
        isWatching = true
        return .authorized
    }

    /// 멱등 — 감시를 멈추고 상태를 초기화한다
    func stop() {
        if isRegistered {
            PHPhotoLibrary.shared().unregisterChangeObserver(self)
            isRegistered = false
        }
        isWatching = false
        since = .distantFuture
        fetchResult = nil
    }

    // MARK: - PHPhotoLibraryChangeObserver

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor [weak self] in self?.handleChange(changeInstance) }
    }

    /// 보관함에 새로 들어온 이미지·동영상(비스크린샷)을 골라 전달한다.
    /// creationDate(촬영 시각)가 아니라 **삽입 여부**로 판단한다 — 실기기에서 폰 카메라 사진은 잡히는데
    /// Meta AI 앱이 가져온 글라스 사진은 보관함에 있어도 못 잡았다. 가져온 사진의 creationDate가
    /// 기준 시각보다 이르게 기록되는 것으로 추정 (T-171).
    private func handleChange(_ change: PHChange) {
        guard isWatching, let fetchResult,
              let details = change.changeDetails(for: fetchResult) else { return }
        self.fetchResult = details.fetchResultAfterChanges
        // 비증분 변화면 insertedObjects가 비어 있어 새 촬영물을 알 수 없다 — 놓쳤음을 드러낸다
        if !details.hasIncrementalChanges {
            onNotice?("보관함이 크게 바뀌어 새 사진·영상을 확인하지 못했어요.")
            return
        }
        let cutoff = since.addingTimeInterval(-24 * 3600)
        let newAssets = details.insertedObjects.filter {
            !$0.mediaSubtypes.contains(.photoScreenshot) && ($0.creationDate ?? .now) >= cutoff
        }
        // 오래된 것부터 순서대로 전달
        for asset in newAssets.sorted(by: {
            ($0.creationDate ?? .distantPast) < ($1.creationDate ?? .distantPast)
        }) {
            loadAndDeliver(asset)
        }
    }

    private func loadAndDeliver(_ asset: PHAsset) {
        if asset.mediaType == .video {
            guard onNewVideo != nil else { return }
            let options = PHVideoRequestOptions()
            options.version = .current
            options.isNetworkAccessAllowed = true
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { [weak self] avAsset, _, _ in
                Task { @MainActor [weak self] in self?.onNewVideo?(avAsset) }
            }
            return
        }
        let filename = Self.filename(for: asset)
        let options = PHImageRequestOptions()
        options.version = .current
        options.deliveryMode = .highQualityFormat
        // 글라스 사진은 iCloud에만 있을 수 있어 네트워크 다운로드를 허용한다
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) {
            [weak self] data, _, _, _ in
            guard let data else { return }
            Task { @MainActor [weak self] in self?.onNewPhoto?(filename, data) }
        }
    }

    // MARK: - Helpers

    /// 보관함의 이미지·동영상 전체 (변화 감지 기준용 — 열거하지 않으므로 큰 보관함도 가볍다)
    private static func fetchMedia() -> PHFetchResult<PHAsset> {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "mediaType == %d OR mediaType == %d",
            PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue
        )
        return PHAsset.fetchAssets(with: options)
    }

    /// 원본 파일명을 쓰되, 없으면 타임스탬프 기반 이름을 만든다 (확장자는 첨부 썸네일/업로드에 쓰임)
    private static func filename(for asset: PHAsset) -> String {
        if let name = PHAssetResource.assetResources(for: asset).first?.originalFilename,
           !name.isEmpty {
            return name
        }
        return "glasses_\(Int(Date.now.timeIntervalSince1970)).jpg"
    }

    nonisolated private static func requestAuthorization() async -> PHAuthorizationStatus {
        await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                continuation.resume(returning: status)
            }
        }
    }
}
