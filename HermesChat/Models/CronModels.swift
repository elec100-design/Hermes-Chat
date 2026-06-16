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
    var status: String?        // "scheduled" | "paused" | "running" 등 (서버 제공 시)
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

    /// 일시정지 여부 — enabled=false가 최우선, 아니면 status 문자열로 판정.
    var isPaused: Bool {
        if enabled == false { return true }
        return status?.lowercased() == "paused"
    }

    /// 상태 배지 라벨 (대시보드의 scheduled/paused/running 배지 재현).
    var statusLabel: String {
        if isPaused { return "paused" }
        if let status, !status.isEmpty { return status }
        return "scheduled"
    }

    /// cron식을 사람이 읽는 문구로 ("Daily at 07:30"). 해석 불가하면 원본 cron식을 그대로.
    var scheduleDescription: String? {
        guard let schedule, !schedule.isEmpty else { return nil }
        return Self.humanizeSchedule(schedule) ?? schedule
    }

    var lastRunDisplay: String? { Self.formatTimestamp(lastRun) }
    var nextRunDisplay: String? { Self.formatTimestamp(nextRun) }

    /// 흔한 daily/weekly cron 패턴만 사람 문구로 바꾼다 (그 외는 nil → 호출부가 원본 표시).
    static func humanizeSchedule(_ expr: String) -> String? {
        let parts = expr.split(separator: " ").map(String.init)
        guard parts.count == 5 else { return nil }
        let (minute, hour, dom, mon, dow) = (parts[0], parts[1], parts[2], parts[3], parts[4])
        guard let m = Int(minute), let h = Int(hour), dom == "*", mon == "*" else { return nil }
        let time = String(format: "%02d:%02d", h, m)
        if dow == "*" { return "매일 \(time)" }
        if let d = Int(dow), (0...7).contains(d) {
            let names = ["일", "월", "화", "수", "목", "금", "토", "일"]
            return "매주 \(names[d])요일 \(time)"
        }
        return nil
    }

    /// ISO8601(또는 epoch에서 변환된 ISO) 문자열을 한국어 로컬 시각으로. 파싱 실패 시 원본 반환.
    static func formatTimestamp(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = withFraction.date(from: value) ?? ISO8601DateFormatter().date(from: value)
        guard let date else { return value }
        let df = DateFormatter()
        df.locale = Locale(identifier: "ko_KR")
        df.dateStyle = .medium
        df.timeStyle = .short
        return df.string(from: date)
    }
}

extension CronJob: Decodable {
    enum CodingKeys: String, CodingKey {
        case id, name, mode, prompt, schedule, skills, enabled, status
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
        status = try? c.decodeIfPresent(String.self, forKey: .status)
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
