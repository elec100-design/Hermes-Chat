import Foundation

/// 맥미니의 Hermes Bridge(server/hermes_bridge.py)와 통신하는 클라이언트.
///
/// 게이트웨이 API가 제공하지 않는 기능을 담당한다:
/// 프로필 목록(정확한 포트 포함), 게이트웨이 재시작, SOUL.md 읽기/쓰기,
/// 파일 업로드(채팅 첨부용), 칸반 보드 저장소.
/// 인증: /health 외 모든 요청에 Bearer 토큰.

struct BridgeProfile: Identifiable, Codable, Equatable, Hashable {
    let name: String
    let port: Int
    let apiEnabled: Bool

    var id: String { name }

    enum CodingKeys: String, CodingKey {
        case name, port
        case apiEnabled = "api_enabled"
    }
}

/// 파일 브라우저(/files) 목록의 한 항목
struct BridgeFileEntry: Identifiable, Codable, Equatable {
    let name: String
    let isDir: Bool
    let size: Int?

    var id: String { name }

    enum CodingKeys: String, CodingKey {
        case name, size
        case isDir = "is_dir"
    }
}

@MainActor
final class BridgeClient {
    let baseURL: URL
    let token: String

    init(baseURL: URL, token: String) {
        self.baseURL = baseURL
        self.token = token
    }

    // MARK: - Profiles

    func fetchProfiles() async throws -> [BridgeProfile] {
        struct Response: Decodable { let data: [BridgeProfile] }
        let data = try await request("GET", "profiles")
        return try decode(Response.self, from: data).data
    }

    /// 해당 프로필의 게이트웨이를 재시작하고 명령 출력을 돌려준다.
    func restartGateway(profile: String) async throws -> String {
        struct Response: Decodable {
            let ok: Bool
            let output: String?
        }
        let data = try await request("POST", "profiles/\(profile)/restart")
        let response = try decode(Response.self, from: data)
        guard response.ok else {
            throw HermesAPIError.serverError(response.output ?? "재시작 실패")
        }
        return response.output ?? ""
    }

    // MARK: - SOUL.md

    func fetchSoul(profile: String) async throws -> String {
        struct Response: Decodable { let content: String }
        let data = try await request("GET", "profiles/\(profile)/soul")
        return try decode(Response.self, from: data).content
    }

    func saveSoul(profile: String, content: String) async throws {
        let body = try JSONSerialization.data(withJSONObject: ["content": content])
        _ = try await request("PUT", "profiles/\(profile)/soul", body: body)
    }

    // MARK: - Cron (프로필별 크론잡 — cron/jobs.json)

    /// 해당 프로필의 크론잡 목록. 원본 객체를 그대로 받아 방어적으로 디코딩한다.
    func fetchCronJobs(profile: String) async throws -> [CronJob] {
        struct Response: Decodable { let jobs: [CronJob] }
        let data = try await request("GET", "profiles/\(profile)/cron")
        return try decode(Response.self, from: data).jobs
    }

    /// 새 크론잡을 jobs.json에 추가한다 (대시보드 "CREATE"). name·schedule은 필수.
    func createCronJob(profile: String, fields: [String: Any]) async throws {
        let body = try JSONSerialization.data(withJSONObject: fields)
        _ = try await request("POST", "profiles/\(profile)/cron", body: body, timeout: 30)
    }

    /// 편집된 필드만 보내 해당 잡을 갱신한다 — 나머지 필드는 Bridge가 보존한다.
    func updateCronJob(profile: String, jobID: String, fields: [String: Any]) async throws {
        let body = try JSONSerialization.data(withJSONObject: fields)
        _ = try await request("PUT", "profiles/\(profile)/cron/\(jobID)", body: body, timeout: 30)
    }

    /// 크론잡 사용/일시정지 — `enabled` 한 필드만 갱신하는 편의 래퍼.
    func setCronJobEnabled(profile: String, jobID: String, enabled: Bool) async throws {
        try await updateCronJob(profile: profile, jobID: jobID, fields: ["enabled": enabled])
    }

    /// 크론잡을 즉시 실행한다 (대시보드 "Trigger now"). 실행 출력을 돌려준다.
    @discardableResult
    func triggerCronJob(profile: String, jobID: String) async throws -> String {
        struct Response: Decodable { let output: String? }
        let data = try await request("POST", "profiles/\(profile)/cron/\(jobID)/run", timeout: 120)
        return (try? decode(Response.self, from: data).output ?? "") ?? ""
    }

    /// 크론잡을 jobs.json에서 삭제한다.
    func deleteCronJob(profile: String, jobID: String) async throws {
        _ = try await request("DELETE", "profiles/\(profile)/cron/\(jobID)", timeout: 30)
    }

    // MARK: - Profile 생성 / 모델

    /// 새 프로필을 백엔드까지 완전 생성한다 (디렉터리+.env+SOUL.md+게이트웨이 install/restart).
    /// 반환값은 실제 할당된 포트 (port를 nil로 주면 Bridge가 자동 할당).
    func createProfile(name: String, port: Int?, apiKey: String, soul: String?) async throws -> Int {
        struct Response: Decodable { let port: Int }
        var payload: [String: Any] = ["name": name, "api_key": apiKey]
        if let port { payload["port"] = port }
        if let soul, !soul.isEmpty { payload["soul"] = soul }
        let body = try JSONSerialization.data(withJSONObject: payload)
        let data = try await request("POST", "profiles", body: body, timeout: 60)
        return try decode(Response.self, from: data).port
    }

    /// 프로필의 현재 모델(config.yaml) + 선택 가능한 카탈로그(cache/model_catalog.json).
    func fetchModelInfo(profile: String) async throws -> (current: String?, catalog: [String]) {
        struct Response: Decodable { let current: String?; let catalog: [String] }
        let data = try await request("GET", "profiles/\(profile)/model")
        let r = try decode(Response.self, from: data)
        return (r.current, r.catalog)
    }

    /// config.yaml의 모델을 바꾸고, restart=true면 게이트웨이를 재시작한다.
    func setModel(profile: String, model: String, restart: Bool) async throws {
        let body = try JSONSerialization.data(withJSONObject: ["model": model, "restart": restart])
        _ = try await request("PUT", "profiles/\(profile)/model", body: body, timeout: 60)
    }

    /// 프로필을 백엔드에서 삭제한다 (`hermes profile delete <name> -y`). default는 불가.
    func deleteProfile(name: String) async throws {
        _ = try await request("DELETE", "profiles/\(name)", timeout: 60)
    }

    // MARK: - Upload (채팅 첨부)

    /// 파일을 해당 프로필의 uploads 폴더로 올리고 맥미니 측 절대경로를 돌려준다.
    /// 돌려받은 경로를 채팅 메시지에 포함하면 Hermes가 파일 도구로 읽을 수 있다.
    func upload(data fileData: Data, filename: String, profile: String) async throws -> String {
        struct Response: Decodable { let path: String }
        let data = try await request(
            "POST", "upload/\(profile)",
            body: fileData,
            headers: ["X-Filename": filename]
        )
        return try decode(Response.self, from: data).path
    }

    // MARK: - Files & Logs (읽기전용, T-061)

    /// HERMES_HOME 기준 상대경로의 디렉터리 목록
    func listFiles(path: String) async throws -> [BridgeFileEntry] {
        struct Response: Decodable { let data: [BridgeFileEntry] }
        let data = try await request("GET", "files", query: ["path": path])
        return try decode(Response.self, from: data).data
    }

    /// 텍스트 파일 내용 (브리지가 512KB로 제한)
    func fetchFileContent(path: String) async throws -> String {
        let data = try await request("GET", "files/content", query: ["path": path])
        return String(decoding: data, as: UTF8.self)
    }

    /// 바이너리 파일 (T-106 — 이미지 썸네일). 브리지 구버전이면 404 → 호출부가 placeholder로 강등.
    func fetchRawFile(path: String) async throws -> Data {
        try await request("GET", "files/raw", query: ["path": path], timeout: 30)
    }

    /// 해당 프로필의 최신 로그 꼬리
    func fetchLogs(profile: String, tail: Int = 200) async throws -> String {
        let data = try await request("GET", "profiles/\(profile)/logs", query: ["tail": String(tail)])
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: - Kanban (hermes-agent 내장 칸반 — 게이트웨이 디스패처·대시보드와 동일 데이터)

    func fetchKanbanBoards() async throws -> [KanbanBoardSummary] {
        struct Response: Decodable { let data: [KanbanBoardSummary] }
        let data = try await request("GET", "kanban")
        return try decode(Response.self, from: data).data
    }

    func fetchBoard(slug: String) async throws -> KanbanBoard {
        let data = try await request("GET", "kanban/\(slug)")
        return try decode(KanbanBoard.self, from: data)
    }

    /// 태스크 생성. mode=.ready면 디스패처가 1분 내 워커를 띄워 실행한다.
    func createKanbanTask(
        board: String,
        title: String,
        detail: String?,
        assignee: String?,
        mode: KanbanCreateMode
    ) async throws {
        var payload: [String: Any] = ["title": title, "status": mode.rawValue]
        if let detail, !detail.isEmpty { payload["detail"] = detail }
        if let assignee, !assignee.isEmpty { payload["assignee"] = assignee }
        let body = try JSONSerialization.data(withJSONObject: payload)
        _ = try await request("POST", "kanban/\(board)/tasks", body: body, timeout: 60)
    }

    /// 새 보드 생성 — Bridge가 `hermes kanban boards create` CLI를 호출한다.
    func createKanbanBoard(name: String, slug: String? = nil) async throws {
        var payload: [String: Any] = ["name": name]
        if let slug, !slug.isEmpty { payload["slug"] = slug }
        let body = try JSONSerialization.data(withJSONObject: payload)
        _ = try await request("POST", "kanban/boards", body: body, timeout: 30)
    }

    /// 상태 전이 — promote/block/unblock/complete/archive (hermes kanban CLI 경유)
    func kanbanAction(
        board: String,
        taskID: String,
        action: KanbanAction,
        reason: String? = nil
    ) async throws {
        var payload: [String: Any] = ["action": action.rawValue]
        if let reason, !reason.isEmpty { payload["reason"] = reason }
        let body = try JSONSerialization.data(withJSONObject: payload)
        _ = try await request("POST", "kanban/\(board)/tasks/\(taskID)/action", body: body, timeout: 60)
    }

    // MARK: - Private

    private func request(
        _ method: String,
        _ path: String,
        query: [String: String]? = nil,
        body: Data? = nil,
        headers: [String: String] = [:],
        timeout: TimeInterval = 15
    ) async throws -> Data {
        var url = baseURL.appendingPathComponent(path)
        if let query,
           var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
            url = comps.url ?? url
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method
        urlRequest.timeoutInterval = timeout
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if body != nil, headers["X-Filename"] == nil {
            urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        for (key, value) in headers {
            urlRequest.setValue(value, forHTTPHeaderField: key)
        }
        urlRequest.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: urlRequest)
        guard let http = response as? HTTPURLResponse else {
            throw HermesAPIError.serverError("브리지 응답 없음")
        }
        switch http.statusCode {
        case 200..<300:
            return data
        case 401:
            throw HermesAPIError.unauthorized
        default:
            let message = String(data: data, encoding: .utf8) ?? "알 수 없는 오류"
            throw HermesAPIError.serverError("브리지 HTTP \(http.statusCode): \(message)")
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw HermesAPIError.decoding(error)
        }
    }
}
