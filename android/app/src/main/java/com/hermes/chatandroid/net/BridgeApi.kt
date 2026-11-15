package com.hermes.chatandroid.net

import com.hermes.chatandroid.data.CronForm
import com.hermes.chatandroid.data.CronJob
import com.hermes.chatandroid.data.KanbanBoard
import com.hermes.chatandroid.data.KanbanBoardSummary
import com.hermes.chatandroid.data.KanbanCreateMode
import com.hermes.chatandroid.data.KanbanTaskDetail
import com.hermes.chatandroid.data.OpsParsers
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.hermes.chatandroid.data.BridgeProfile
import com.hermes.chatandroid.data.Parsers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Hermes Bridge(:8765) — 프로필 목록·첨부 업로드·칸반·크론. [token] 비어 있으면 인증 헤더 없음. */
open class BridgeApi(
    private val baseUrl: String,
    private val token: String,
    client: OkHttpClient = defaultHttpClient(),
) {
    private val http = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    open suspend fun fetchProfiles(): List<BridgeProfile> {
        val req = Request.Builder().url(joinUrl(baseUrl, "profiles")).bearer(token).build()
        return Parsers.parseBridgeProfiles(http.fetchText(req))
    }

    /** 파일을 프로필 uploads 폴더에 올리고 맥미니 절대경로를 돌려준다. */
    open suspend fun upload(bytes: ByteArray, filename: String, profile: String): String {
        // HTTP 헤더는 ASCII 만 안전 — 서버도 [A-Za-z0-9._-가-힣] 외에는 _ 로 치환한다.
        val name = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val req = Request.Builder()
            .url(joinUrl(baseUrl, "upload/${URLEncoder.encode(profile, "UTF-8")}"))
            .bearer(token)
            .header("X-Filename", name)
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val text = http.fetchText(req)
        return parseJsonOrNull(text).asObject()?.text("path")
            ?: throw ApiException(0, "업로드 응답에 path 가 없습니다")
    }

    /** HERMES_HOME 기준 상대경로의 파일 원본(`GET /files/raw`, 20MB 이하). 첨부 이미지 표시용. */
    open suspend fun fetchRaw(relativePath: String): ByteArray =
        http.fetchBytes(req("files/raw?path=" + URLEncoder.encode(relativePath, "UTF-8")).build())

    private fun req(path: String) = Request.Builder().url(joinUrl(baseUrl, path)).bearer(token)
    private fun seg(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    // ---- 칸반 ----

    open suspend fun fetchKanbanBoards(): List<KanbanBoardSummary> =
        OpsParsers.parseBoards(http.fetchText(req("kanban").build()))

    open suspend fun fetchKanbanBoard(board: String): KanbanBoard =
        OpsParsers.parseBoard(http.fetchText(req("kanban/${seg(board)}").build()))

    open suspend fun fetchKanbanTask(board: String, taskId: String): KanbanTaskDetail =
        OpsParsers.parseTaskDetail(http.fetchText(req("kanban/${seg(board)}/tasks/${seg(taskId)}").build()))
            ?: throw ApiException(0, "태스크를 읽지 못했습니다")

    open suspend fun createKanbanTask(board: String, title: String, detail: String, assignee: String, mode: KanbanCreateMode) {
        val body = buildJsonObject {
            put("title", title)
            put("status", mode.key)
            if (detail.isNotBlank()) put("detail", detail)
            if (assignee.isNotBlank()) put("assignee", assignee)
        }.toString()
        http.fetchText(req("kanban/${seg(board)}/tasks").post(jsonBody(body)).build())
    }

    open suspend fun createKanbanBoard(name: String) {
        http.fetchText(req("kanban/boards").post(jsonBody(buildJsonObject { put("name", name) }.toString())).build())
    }

    /** [action] = promote|block|unblock|complete|archive|comment. comment 는 [reason] 이 본문. */
    open suspend fun kanbanAction(board: String, taskId: String, action: String, reason: String? = null) {
        val body = buildJsonObject {
            put("action", action)
            if (!reason.isNullOrBlank()) put("reason", reason)
        }.toString()
        http.fetchText(req("kanban/${seg(board)}/tasks/${seg(taskId)}/action").post(jsonBody(body)).build())
    }

    // ---- 크론 ----

    open suspend fun fetchCronJobs(profile: String): List<CronJob> =
        OpsParsers.parseCronJobs(http.fetchText(req("profiles/${seg(profile)}/cron").build()), profile)

    open suspend fun createCronJob(form: CronForm) {
        val body = buildJsonObject {
            put("name", form.name.trim())
            put("schedule", form.schedule.trim())
            put("prompt", form.prompt.trim())
            put("deliver", form.deliver.trim())
            put("skills", buildJsonArray { form.skillList.forEach { add(it) } })
            put("enabled", form.enabled)
        }.toString()
        http.fetchText(req("profiles/${seg(form.profile)}/cron").post(jsonBody(body)).build())
    }

    /** 바뀐 필드만 보낸다 — 나머지는 서버(hermes cron edit)가 보존. */
    open suspend fun updateCronJob(profile: String, jobId: String, old: CronForm, new: CronForm) {
        val body = buildJsonObject {
            if (new.name != old.name) put("name", new.name.trim())
            if (new.schedule != old.schedule) put("schedule", new.schedule.trim())
            if (new.prompt != old.prompt) put("prompt", new.prompt.trim())
            if (new.deliver != old.deliver) put("deliver", new.deliver.trim())
            if (new.skillList != old.skillList) put("skills", buildJsonArray { new.skillList.forEach { add(it) } })
            if (new.enabled != old.enabled) put("enabled", new.enabled)
        }
        if (body.isEmpty()) return
        http.fetchText(req("profiles/${seg(profile)}/cron/${seg(jobId)}").put(jsonBody(body.toString())).build())
    }

    open suspend fun setCronEnabled(profile: String, jobId: String, enabled: Boolean) {
        val body = buildJsonObject { put("enabled", enabled) }.toString()
        http.fetchText(req("profiles/${seg(profile)}/cron/${seg(jobId)}").put(jsonBody(body)).build())
    }

    /** 다음 스케줄러 틱(약 1분 안)에 실행되도록 예약. */
    open suspend fun runCronJob(profile: String, jobId: String) {
        http.fetchText(req("profiles/${seg(profile)}/cron/${seg(jobId)}/run").post(jsonBody("{}")).build())
    }

    open suspend fun deleteCronJob(profile: String, jobId: String) {
        http.fetchText(req("profiles/${seg(profile)}/cron/${seg(jobId)}").delete().build())
    }
}

