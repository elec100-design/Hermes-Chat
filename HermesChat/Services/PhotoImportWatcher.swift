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

    @Published private(set) var isWatching = false

    /// 권한 요청 결과 — 제한 접근은 호출부가 안내하도록 구분한다.
    enum StartResult { case authorized, limited, denied }

    /// 감시 시작 시각 — 이 이후 생성된 사진만 대상으로 본다
    private var since = Date.distantFuture
    /// 이미 처리(또는 시작 시점 베이스라인으로 무시)한 에셋 식별자 — 중복 전송 방지
    private var processedIDs = Set<String>()
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
        // 시작 시점에 이미 보관함에 있던 사진은 베이스라인으로 기록해 무시한다 (옛 사진 오발송 방지)
        processedIDs.removeAll()
        Self.fetchImages(after: date).enumerateObjects { asset, _, _ in
            self.processedIDs.insert(asset.localIdentifier)
        }
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
        processedIDs.removeAll()
    }

    // MARK: - PHPhotoLibraryChangeObserver

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor [weak self] in self?.scanForNewPhotos() }
    }

    /// 변화 이후 보관함에서 기준 시각 이후·이미지·비스크린샷·미처리 에셋을 골라 전달한다
    private func scanForNewPhotos() {
        guard isWatching else { return }
        var newAssets: [PHAsset] = []
        Self.fetchImages(after: since).enumerateObjects { asset, _, _ in
            guard !self.processedIDs.contains(asset.localIdentifier) else { return }
            // 스크린샷은 글라스 사진이 아니므로 제외 (한 번 보고 무시 목록에 넣는다)
            if asset.mediaSubtypes.contains(.photoScreenshot) {
                self.processedIDs.insert(asset.localIdentifier)
                return
            }
            newAssets.append(asset)
        }
        // 오래된 것부터 순서대로 전달
        for asset in newAssets.sorted(by: {
            ($0.creationDate ?? .distantPast) < ($1.creationDate ?? .distantPast)
        }) {
            processedIDs.insert(asset.localIdentifier)
            loadAndDeliver(asset)
        }
    }

    private func loadAndDeliver(_ asset: PHAsset) {
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

    /// 기준 시각 이후 생성된 이미지 에셋 (생성순 오름차순)
    private static func fetchImages(after date: Date) -> PHFetchResult<PHAsset> {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "mediaType == %d AND creationDate >= %@",
            PHAssetMediaType.image.rawValue, date as NSDate
        )
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
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
