package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.array
import com.hermes.chatandroid.net.asArray
import com.hermes.chatandroid.net.asBoolLenient
import com.hermes.chatandroid.net.asIntLenient
import com.hermes.chatandroid.net.asObject
import com.hermes.chatandroid.net.asText
import com.hermes.chatandroid.net.int
import com.hermes.chatandroid.net.obj
import com.hermes.chatandroid.net.parseJsonOrNull
import com.hermes.chatandroid.net.text
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// 칸반 — hermes-agent 내장 kanban.db (Bridge /kanban). 쓰기는 Bridge 가 `hermes kanban` CLI 경유.
// ---------------------------------------------------------------------------

/** 상태별 수동 전이 (hermes kanban CLI 제약과 일치 — iOS KanbanModels 와 동일). running 은 디스패처 소유. */
enum class KanbanStatus(val key: String, val label: String, val argb: Long) {
    TRIAGE("triage", "분류", 0xFF8A94A6),
    TODO("todo", "할 일", 0xFF3B82F6),
    SCHEDULED("scheduled", "예약", 0xFF8B5CF6),
    READY("ready", "대기", 0xFF14B8A6),
    RUNNING("running", "실행 중", 0xFFF59E0B),
    BLOCKED("blocked", "보류", 0xFFEF4444),
    DONE("done", "완료", 0xFF22C55E);

    val actions: List<KanbanAction>
        get() = when (this) {
            TRIAGE -> listOf(KanbanAction.BLOCK)
            TODO -> listOf(KanbanAction.PROMOTE, KanbanAction.BLOCK, KanbanAction.COMPLETE)
            SCHEDULED -> listOf(KanbanAction.UNBLOCK)
            READY -> listOf(KanbanAction.BLOCK, KanbanAction.COMPLETE)
            RUNNING -> emptyList()
            BLOCKED -> listOf(KanbanAction.UNBLOCK, KanbanAction.COMPLETE, KanbanAction.ARCHIVE)
            DONE -> listOf(KanbanAction.ARCHIVE)
        }

    companion object {
        fun of(key: String?): KanbanStatus = entries.firstOrNull { it.key == key } ?: TRIAGE
    }
}

/** Bridge `POST /kanban/<board>/tasks/<id>/action` 의 action. [reasonHint] 가 있으면 사유 입력을 받는다. */
enum class KanbanAction(val key: String, val label: String, val reasonHint: String?) {
    PROMOTE("promote", "실행", null),
    BLOCK("block", "보류", "보류 사유 (선택)"),
    UNBLOCK("unblock", "재개", null),
    COMPLETE("complete", "완료 처리", "결과 메모 (선택)"),
    ARCHIVE("archive", "보관", null),
}

/** 새 태스크 초기 상태 (Bridge POST status). */
enum class KanbanCreateMode(val key: String, val label: String, val note: String) {
    READY("ready", "바로 실행", "디스패처가 1분 안에 담당 프로필로 실행합니다"),
    TRIAGE("triage", "분류 먼저", "스페시파이어가 내용을 구체화한 뒤 진행합니다"),
    BLOCKED("blocked", "보류", "사람이 재개할 때까지 대기합니다"),
}

data class KanbanTask(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: KanbanStatus = KanbanStatus.TRIAGE,
    val assignee: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val result: String? = null,
    val error: String? = null,
    val failures: Int = 0,
)

data class KanbanBoardSummary(val board: String, val name: String, val counts: Map<String, Int>) {
    val activeCount: Int get() = counts.filterKeys { it != "archived" }.values.sum()
}

data class KanbanBoard(val board: String, val name: String, val tasks: List<KanbanTask>)

data class KanbanComment(val author: String, val body: String, val createdAt: String?)
data class KanbanEvent(val kind: String, val payload: String, val createdAt: String?)
data class KanbanTaskDetail(val task: KanbanTask, val comments: List<KanbanComment>, val events: List<KanbanEvent>)

// ---------------------------------------------------------------------------
// 크론 — 프로필별 cron/jobs.json (Bridge /profiles/<p>/cron). 쓰기는 Bridge 가 `hermes cron` CLI 경유.
// ---------------------------------------------------------------------------

data class CronJob(
    val id: String,
    val profile: String,
    val name: String = "",
    val prompt: String = "",
    /** cron 식 또는 "30m"/"every 2h" 같은 CLI 표현. */
    val schedule: String = "",
    val deliver: String = "",
    val skills: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** scheduled | paused | running | completed … */
    val state: String = "",
    val lastRunAt: String? = null,
    val nextRunAt: String? = null,
    /** ok | error … */
    val lastStatus: String? = null,
    val lastError: String? = null,
    val noAgent: Boolean = false,
    val model: String? = null,
) {
    val title: String get() = name.ifBlank { prompt.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: id }
    val paused: Boolean get() = !enabled || state.equals("paused", ignoreCase = true)
    val failed: Boolean get() = lastStatus != null && !lastStatus.equals("ok", ignoreCase = true)
}

/** 크론 생성/수정 폼. 수정 시에는 바뀐 필드만 보낸다. */
data class CronForm(
    val profile: String = "default",
    val name: String = "",
    val schedule: String = "0 9 * * *",
    val prompt: String = "",
    val deliver: String = "origin",
    val skills: String = "",
    val enabled: Boolean = true,
) {
    val skillList: List<String> get() = skills.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun of(j: CronJob) = CronForm(
            profile = j.profile, name = j.name, schedule = j.schedule, prompt = j.prompt,
            deliver = j.deliver.ifBlank { "origin" }, skills = j.skills.joinToString(", "), enabled = !j.paused,
        )
    }
}

object CronText {
    private val days = listOf("일", "월", "화", "수", "목", "금", "토", "일")

    /** 흔한 cron 식을 한국어로. 해석 못 하면 null(호출부가 원문 표시). */
    fun humanize(expr: String): String? {
        val e = expr.trim()
        Regex("^(?:every\\s+)?(\\d+)\\s*([mhd])$", RegexOption.IGNORE_CASE).matchEntire(e)?.let {
            val unit = when (it.groupValues[2].lowercase()) { "m" -> "분"; "h" -> "시간"; else -> "일" }
            return "${it.groupValues[1]}${unit}마다"
        }
        val p = e.split(Regex("\\s+"))
        if (p.size != 5) return null
        val (mi, h, dom, mon, dow) = p
        if (mon != "*") return null
        val minute = mi.toIntOrNull()
        if (h.startsWith("*/") && minute != null && dom == "*" && dow == "*") {
            return "${h.removePrefix("*/")}시간마다 (${minute}분)"
        }
        if (mi.startsWith("*/") && h == "*" && dom == "*" && dow == "*") return "${mi.removePrefix("*/")}분마다"
        if (h == "*" && minute != null && dom == "*" && dow == "*") return "매시 ${minute}분"
        val hours = h.split(',').map { it.toIntOrNull() ?: return null }
        if (minute == null) return null
        val time = hours.joinToString(", ") { "%02d:%02d".format(it, minute) }
        return when {
            dom == "*" && dow == "*" -> "매일 $time"
            dom == "*" && dow == "1-5" -> "평일 $time"
            dom == "*" && dow.split(',').all { it.toIntOrNull() in 0..7 } ->
                "매주 ${dow.split(',').joinToString("·") { days[it.toInt()] }} $time"
            dow == "*" && dom.toIntOrNull() != null -> "매월 ${dom}일 $time"
            else -> null
        }
    }

    private val outFmt = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN)

    /** ISO 시각 → "11월 16일 (월) 07:00". 실패 시 원문. */
    fun formatTime(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(outFmt) ?: iso
    }

    /** 지금 기준 상대 표현: "3시간 후", "12분 전". */
    fun relative(iso: String?, now: Instant = Instant.now()): String? {
        val t = parseInstant(iso ?: return null) ?: return null
        val sec = t.epochSecond - now.epochSecond
        val abs = kotlin.math.abs(sec)
        val s = when {
            abs < 60 -> return if (sec >= 0) "곧" else "방금"
            abs < 3600 -> "${abs / 60}분"
            abs < 86400 -> "${abs / 3600}시간"
            else -> "${abs / 86400}일"
        }
        return if (sec >= 0) "$s 후" else "$s 전"
    }

    fun parseInstant(iso: String): Instant? =
        runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(iso) }.getOrNull()
}

object OpsParsers {
    private fun task(o: kotlinx.serialization.json.JsonObject): KanbanTask? {
        val id = o.text("id") ?: return null
        return KanbanTask(
            id = id,
            title = o.text("title")?.takeIf { it.isNotBlank() } ?: "(제목 없음)",
            detail = o.text("detail").orEmpty(),
            status = KanbanStatus.of(o.text("status")),
            assignee = o.text("assignee").orEmpty(),
            createdAt = o.text("created_at"),
            updatedAt = o.text("updated_at") ?: o.text("completed_at"),
            result = o.text("result")?.takeIf { it.isNotBlank() },
            error = o.text("error")?.takeIf { it.isNotBlank() },
            failures = o.int("failures") ?: 0,
        )
    }

    fun parseBoards(body: String): List<KanbanBoardSummary> {
        val arr = parseJsonOrNull(body).asObject()?.array("data") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            val board = o.text("board") ?: return@mapNotNull null
            val counts = o.obj("counts")?.mapNotNull { (k, v) -> v.asIntLenient()?.let { k to it } }?.toMap().orEmpty()
            KanbanBoardSummary(board, o.text("name") ?: board, counts)
        }
    }

    fun parseBoard(body: String): KanbanBoard {
        val o = parseJsonOrNull(body).asObject() ?: return KanbanBoard("", "", emptyList())
        val board = o.text("board").orEmpty()
        return KanbanBoard(board, o.text("name") ?: board, o.array("tasks")?.mapNotNull { it.asObject()?.let(::task) }.orEmpty())
    }

    fun parseTaskDetail(body: String): KanbanTaskDetail? {
        val o = parseJsonOrNull(body).asObject() ?: return null
        val t = o.obj("task")?.let(::task) ?: return null
        return KanbanTaskDetail(
            task = t,
            comments = o.array("comments")?.mapNotNull { el ->
                el.asObject()?.let { KanbanComment(it.text("author").orEmpty(), it.text("body").orEmpty(), it.text("created_at")) }
            }.orEmpty(),
            events = o.array("events")?.mapNotNull { el ->
                el.asObject()?.let { KanbanEvent(it.text("kind").orEmpty(), it.text("payload").orEmpty(), it.text("created_at")) }
            }.orEmpty(),
        )
    }

    /**
     * `GET /profiles/<p>/cron` → `{"jobs": [...]}` 원본 jobs.json 객체. hermes-agent 버전별 키 차이를 흡수:
     * schedule 은 문자열 또는 `{expr, display}` 객체, 시각은 `*_run_at` 또는 구 `*_run`, 전달은 `deliver` 또는 `deliver_to`.
     */
    fun parseCronJobs(body: String, profile: String): List<CronJob> {
        val arr = parseJsonOrNull(body).asObject()?.array("jobs") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            val id = o.text("id") ?: return@mapNotNull null
            val sched = o["schedule"]
            val schedule = sched.asObject()?.let { it.text("expr") ?: it.text("display") } ?: sched.asText()
                ?: o.text("schedule_display").orEmpty()
            CronJob(
                id = id,
                profile = profile,
                name = o.text("name").orEmpty(),
                prompt = o.text("prompt").orEmpty(),
                schedule = schedule.orEmpty(),
                deliver = o.text("deliver") ?: o.text("deliver_to").orEmpty(),
                skills = o.array("skills")?.mapNotNull { it.asText() }.orEmpty(),
                enabled = o["enabled"].asBoolLenient() ?: true,
                state = o.text("state") ?: o.text("status").orEmpty(),
                lastRunAt = o.text("last_run_at") ?: o.text("last_run"),
                nextRunAt = o.text("next_run_at") ?: o.text("next_run"),
                lastStatus = o.text("last_status"),
                lastError = o.text("last_error")?.takeIf { it.isNotBlank() },
                noAgent = o["no_agent"].asBoolLenient() == true,
                model = o.text("model"),
            )
        }
    }
}
