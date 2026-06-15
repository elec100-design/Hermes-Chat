import Foundation

/// 프로필의 크론잡 한 건 — `~/.hermes/profiles/<name>/cron/jobs.json`의 항목.
///
/// jobs.json 키 이름이 hermes-agent 버전에 따라 다를 수 있어 모든 필드를 방어적으로
/// 디코딩한다. 편집 외 필드(`mode`, `script`, `last_run` 등)는 Bridge가 read-modify-write로
/// 원본을 보존하므로 앱이 전부 알 필요는 없다 — 표시에 쓰는 것만 추린다.
struct CronJob: Identifiable, Equatable, Hashable {
    let id: String
    var name: String?
    var mode: String?          // "agent" | "no_agent" 등 (표시용)
    var prompt: String?
    var schedule: String?      // cron expression, 예: "0 8 * * *"
    var deliverTo: String?
    var skills: [String]
    var enabled: Bool?
    var lastRun: String?
    var nextRun: String?

    /// 목록 제목 — name 우선, 없으면 prompt 첫 줄, 그것도 없으면 id.
    var displayTitle: String {
        if let name, !name.isEmpty { return name }
        if let first = prompt?.split(whereSeparator: \.isNewline).first,
           !first.isEmpty {
            return String(first)
        }
        return id
    }
}

extension CronJob: Decodable {
    enum CodingKeys: String, CodingKey {
        case id, name, mode, prompt, schedule, skills, enabled
        case deliverTo = "deliver_to"
        case lastRun = "last_run"
        case nextRun = "next_run"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // id: String 또는 Int 모두 수용
        if let s = try? c.decode(String.self, forKey: .id) {
            id = s
        } else if let i = try? c.decode(Int.self, forKey: .id) {
            id = String(i)
        } else {
            id = ""
        }
        name = try? c.decodeIfPresent(String.self, forKey: .name)
        mode = try? c.decodeIfPresent(String.self, forKey: .mode)
        prompt = try? c.decodeIfPresent(String.self, forKey: .prompt)
        schedule = try? c.decodeIfPresent(String.self, forKey: .schedule)
        deliverTo = try? c.decodeIfPresent(String.self, forKey: .deliverTo)
        skills = (try? c.decodeIfPresent([String].self, forKey: .skills)) ?? []
        enabled = try? c.decodeIfPresent(Bool.self, forKey: .enabled)
        lastRun = Self.flexibleString(c, .lastRun)
        nextRun = Self.flexibleString(c, .nextRun)
    }

    /// 문자열 또는 숫자(epoch)로 올 수 있는 필드를 문자열로 정규화 (표시 전용).
    private static func flexibleString(
        _ c: KeyedDecodingContainer<CodingKeys>, _ key: CodingKeys
    ) -> String? {
        if let s = try? c.decodeIfPresent(String.self, forKey: key) { return s }
        if let d = try? c.decodeIfPresent(Double.self, forKey: key) {
            return ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: d))
        }
        return nil
    }
}
