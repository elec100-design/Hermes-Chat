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

    // MARK: - Kanban (보드 원본 JSON은 T-050 모델에서 디코딩)

    func fetchBoardNames() async throws -> [String] {
        struct Response: Decodable { let data: [String] }
        let data = try await request("GET", "kanban")
        return try decode(Response.self, from: data).data
    }

    func fetchBoardData(name: String) async throws -> Data {
        try await request("GET", "kanban/\(name)")
    }

    func saveBoardData(name: String, data: Data) async throws {
        _ = try await request("PUT", "kanban/\(name)", body: data)
    }

    // MARK: - Private

    private func request(
        _ method: String,
        _ path: String,
        body: Data? = nil,
        headers: [String: String] = [:]
    ) async throws -> Data {
        var urlRequest = URLRequest(url: baseURL.appendingPathComponent(path))
        urlRequest.httpMethod = method
        urlRequest.timeoutInterval = 15
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
