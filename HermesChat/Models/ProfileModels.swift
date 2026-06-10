import Foundation

/// 맥미니의 hermes-agent 프로필 하나에 대응한다.
///
/// hermes-agent에서 프로필은 각각 독립된 게이트웨이 프로세스이며,
/// 프로필마다 자기만의 API 서버 포트를 가진다 (`~/.hermes/profiles/<name>/.env`의
/// `API_SERVER_PORT`). 따라서 앱에서 "프로필 전환" = "다른 포트의 API 서버로 전환"이다.
/// 기본(default) 프로필은 `~/.hermes` 자체이며 포트 8642를 쓴다.
struct HermesProfile: Identifiable, Codable, Equatable, Hashable {
    var id: UUID
    /// hermes profile 이름 ("default", "work" 등)
    var name: String
    /// 해당 프로필 게이트웨이의 API_SERVER_PORT
    var port: Int
    /// 프로필 전용 API Key. 비어 있으면 전역 API Key를 사용한다.
    var apiKey: String

    init(id: UUID = UUID(), name: String, port: Int, apiKey: String = "") {
        self.id = id
        self.name = name
        self.port = port
        self.apiKey = apiKey
    }

    static let `default` = HermesProfile(name: "default", port: 8642)
}
