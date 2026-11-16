package com.hermes.chatandroid.data

import com.hermes.chatandroid.net.array
import com.hermes.chatandroid.net.asArray
import com.hermes.chatandroid.net.asDoubleLenient
import com.hermes.chatandroid.net.asObject
import com.hermes.chatandroid.net.asText
import com.hermes.chatandroid.net.bool
import com.hermes.chatandroid.net.int
import com.hermes.chatandroid.net.obj
import com.hermes.chatandroid.net.parseJsonOrNull
import com.hermes.chatandroid.net.text
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// 에이전트 계정 사용량 — Bridge `GET /usage` (맥미니의 `paseo usage --json` 그대로, 아이콘만 뺌).
// ---------------------------------------------------------------------------

/** 사용 한도 창 하나(5시간·주간 등). [tone] = ok | warning | danger. */
data class UsageWindow(val label: String, val usedPct: Int?, val resetsAt: String?, val tone: String?)

/** 남은 크레딧 등. */
data class UsageBalance(val label: String, val remaining: String?, val unit: String?, val tone: String?)

data class UsageDetail(val label: String, val value: String)

data class UsageReport(
    val available: Boolean,
    val planLabel: String? = null,
    val windows: List<UsageWindow> = emptyList(),
    val balances: List<UsageBalance> = emptyList(),
    val details: List<UsageDetail> = emptyList(),
    /** 사용할 수 없을 때의 HTTP 상태(401 = 로그인 거부). */
    val problemStatus: Int? = null,
    val problemKind: String? = null,
)

data class UsageSource(
    val id: String,
    /** claude | grok | codex | … */
    val sourceId: String,
    val label: String,
    val account: String? = null,
    val fetchedAt: String? = null,
    val report: UsageReport,
    /** 지금은 못 읽지만 마지막으로 성공한 값(있으면 흐리게 보여 준다). */
    val last: UsageReport? = null,
    val lastFetchedAt: String? = null,
) {
    /** 화면에 그릴 수치 — 지금 값이 없으면 마지막 성공 값. */
    val shown: UsageReport get() = if (report.available) report else last ?: report
    val shownAt: String? get() = if (report.available) fetchedAt else lastFetchedAt ?: fetchedAt

    val problemText: String?
        get() = when {
            report.available -> null
            report.problemStatus == 401 -> "$label: 로그인이 거부되었습니다 (HTTP 401). 맥미니에서 다시 로그인하세요."
            report.problemStatus != null -> "$label: 사용량을 읽지 못했습니다 (HTTP ${report.problemStatus})."
            else -> "$label: 사용량을 읽지 못했습니다" + (report.problemKind?.let { " ($it)" } ?: "") + "."
        }
}

// ---------------------------------------------------------------------------
// 작업 폴더 git — Bridge `GET /git/status`, `POST /git/<action>`.
// ---------------------------------------------------------------------------

data class GitPr(val number: Int?, val url: String?, val state: String?, val title: String?) {
    val isOpen: Boolean get() = state == "OPEN"
}

data class GitStatus(
    val branch: String = "",
    val dirty: Boolean = false,
    val files: List<String> = emptyList(),
    val hasUpstream: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
    val hasRemote: Boolean = false,
    val defaultBranch: String? = null,
    val pr: GitPr? = null,
    /** 리포 안의 배포 스크립트 상대경로. 없으면 배포 불가. */
    val deployScript: String? = null,
) {
    val onDefaultBranch: Boolean get() = defaultBranch != null && branch == defaultBranch

    /** "main · 변경 3 · ↑2 ↓1" */
    val summary: String
        get() = buildList {
            add(branch.ifBlank { "?" })
            add(if (dirty) "변경 ${files.size}" else "깨끗함")
            listOfNotNull("↑$ahead".takeIf { ahead > 0 }, "↓$behind".takeIf { behind > 0 })
                .takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" ")) }
        }.joinToString(" · ")
}

/** 원격에 닿거나 되돌리기 어려운 동작은 [confirm] 문구로 확인 창을 거친다. */
enum class GitAction(val key: String, val label: String, val confirm: String?) {
    COMMIT("commit", "커밋", null),
    PULL("pull", "풀", null),
    PUSH("push", "푸시", "커밋을 원격 저장소로 보냅니다. 되돌릴 수 없습니다."),
    PR("pr", "PR 만들기", "이 브랜치로 GitHub 풀 리퀘스트를 엽니다. 리뷰어에게 알림이 갑니다."),
    MERGE("merge", "병합", "열려 있는 PR 을 기본 브랜치에 병합합니다. 되돌릴 수 없습니다."),
    DEPLOY("deploy", "배포", "리포의 배포 스크립트를 맥미니에서 실행합니다."),
}

data class GitResult(val output: String, val url: String? = null)

/** 작업 폴더 [workdir] 가 속한 워크스페이스(경로가 같거나 그 아래) — 여럿이면 가장 깊은 것. */
fun workspaceOf(workdir: String?, workspaces: List<Workspace>): Workspace? {
    val wd = workdir?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    return workspaces
        .filter { ws -> ws.path.trimEnd('/').let { wd == it || wd.startsWith("$it/") } }
        .maxByOrNull { it.path.length }
}

/** 워크스페이스 id → 그 폴더에서 일하는 작업 채팅(채널). 폴더가 없거나 등록 밖인 채널은 빠진다. */
fun workChatsByWorkspace(workspaces: List<Workspace>, channels: List<BotChannel>): Map<Int, List<BotChannel>> =
    channels.mapNotNull { ch -> workspaceOf(ch.workdir, workspaces)?.let { it.id to ch } }
        .groupBy({ it.first }, { it.second })

object DevParsers {
    private fun report(o: JsonObject?): UsageReport? {
        o ?: return null
        val problem = o.obj("problem")
        return UsageReport(
            available = o.text("status") == "available",
            planLabel = o.text("planLabel")?.takeIf { it.isNotBlank() },
            windows = o.array("windows")?.mapNotNull { el ->
                val w = el.asObject() ?: return@mapNotNull null
                UsageWindow(
                    label = w.text("label") ?: w.text("shortLabel") ?: return@mapNotNull null,
                    usedPct = w["usedPct"].asDoubleLenient()?.toInt()?.coerceIn(0, 100),
                    resetsAt = w.text("resetsAt"),
                    tone = w.text("tone"),
                )
            }.orEmpty(),
            balances = o.array("balances")?.mapNotNull { el ->
                val b = el.asObject() ?: return@mapNotNull null
                UsageBalance(b.text("label") ?: return@mapNotNull null, b.text("remaining"), b.text("unit"), b.text("tone"))
            }.orEmpty(),
            details = o.array("details")?.mapNotNull { el ->
                val d = el.asObject() ?: return@mapNotNull null
                UsageDetail(d.text("label") ?: return@mapNotNull null, d.text("value") ?: return@mapNotNull null)
            }.orEmpty(),
            problemStatus = problem?.int("status"),
            problemKind = problem?.text("kind"),
        )
    }

    /** `{"data":[…]}` 또는 배열 그대로. 앞에 둘 순서: Claude, Grok, Antigravity, 그다음 나머지. */
    fun parseUsage(body: String): List<UsageSource> {
        val root = parseJsonOrNull(body)
        val arr = root.asObject()?.array("data") ?: root.asArray() ?: return emptyList()
        val order = listOf("claude", "grok", "antigravity")
        return arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            val sourceId = o.text("sourceId") ?: return@mapNotNull null
            val last = o.obj("lastSuccessful")
            UsageSource(
                id = o.text("id") ?: sourceId,
                sourceId = sourceId,
                label = o.text("sourceLabel") ?: sourceId,
                account = o.obj("account")?.text("label")?.takeIf { it.isNotBlank() },
                fetchedAt = o.text("fetchedAt"),
                report = report(o.obj("report")) ?: UsageReport(available = false),
                last = report(last?.obj("report")),
                lastFetchedAt = last?.text("fetchedAt"),
            )
        }.sortedBy { s -> order.indexOf(s.sourceId).let { if (it < 0) order.size else it } }
    }

    fun parseGitStatus(body: String): GitStatus {
        val o = parseJsonOrNull(body).asObject() ?: return GitStatus()
        val pr = o.obj("pr")
        return GitStatus(
            branch = o.text("branch").orEmpty(),
            dirty = o.bool("dirty") ?: false,
            files = o.array("files")?.mapNotNull { it.asText() }.orEmpty(),
            hasUpstream = o.bool("upstream") ?: false,
            ahead = o.int("ahead") ?: 0,
            behind = o.int("behind") ?: 0,
            hasRemote = o.bool("remote") ?: false,
            defaultBranch = o.text("default_branch"),
            pr = pr?.let { GitPr(it.int("number"), it.text("url"), it.text("state"), it.text("title")) },
            deployScript = o.text("deploy_script"),
        )
    }

    fun parseGitResult(body: String): GitResult {
        val o = parseJsonOrNull(body).asObject()
        return GitResult(o?.text("output").orEmpty(), o?.text("url"))
    }
}

object UsageText {
    private fun instant(iso: String?): Instant? =
        try { iso?.let { OffsetDateTime.parse(it).toInstant() } } catch (e: Exception) { null }

    private val stamp = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

    /** "2일 3시간 후 재설정 · 11월 23일 오후 11:28" / 지났으면 "초기화 시간이 지났습니다". */
    fun reset(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = instant(iso) ?: return null
        val left = Duration.between(now, at)
        if (left.isNegative || left.isZero) return "초기화 시간이 지났습니다"
        val days = left.toDays()
        val hours = left.toHours() % 24
        val minutes = left.toMinutes() % 60
        val span = when {
            days > 0 -> "${days}일 ${hours}시간"
            hours > 0 -> "${hours}시간 ${minutes}분"
            else -> "${maxOf(minutes, 1)}분"
        }
        return "$span 후 재설정 · " + stamp.format(at.atZone(zone))
    }

    /** "방금" / "12분 전" / "3시간 전" / "2일 전". */
    fun ago(iso: String?, now: Instant = Instant.now()): String? {
        val at = instant(iso) ?: return null
        val d = Duration.between(at, now)
        return when {
            d.toMinutes() < 1 -> "방금"
            d.toHours() < 1 -> "${d.toMinutes()}분 전"
            d.toDays() < 1 -> "${d.toHours()}시간 전"
            else -> "${d.toDays()}일 전"
        }
    }
}
