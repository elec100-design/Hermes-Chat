import Foundation

/// Gemini Live 프리빌트 보이스 (T-154).
enum GeminiVoice: String, CaseIterable, Identifiable {
    case aoede = "Aoede"
    case charon = "Charon"
    case fenrir = "Fenrir"
    case kore = "Kore"
    case puck = "Puck"

    var id: String { rawValue }
    /// 사람이 읽는 짧은 성격 라벨
    var subtitle: String {
        switch self {
        case .aoede:  return "밝고 부드러움"
        case .charon: return "차분하고 낮음"
        case .fenrir: return "또렷하고 단단함"
        case .kore:   return "중립적·명료"
        case .puck:   return "경쾌하고 활기"
        }
    }
}

/// Live 세션 연결/대화 상태 (T-155).
enum LiveConnectionState: Equatable {
    case disconnected
    case connecting
    case listening   // 마이크 청취 중
    case speaking    // Gemini 응답 재생 중
    case error(String)

    var isActive: Bool {
        switch self {
        case .listening, .speaking, .connecting: return true
        default: return false
        }
    }
}
