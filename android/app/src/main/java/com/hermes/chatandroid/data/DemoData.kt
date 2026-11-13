package com.hermes.chatandroid.data

/** Play 심사/체험용 데모 데이터 — 서버 없이 모든 화면을 볼 수 있게 한다. android 의존 없음. */
object DemoData {
    val profiles = listOf(ProfileEntry("Hermes (Demo)", 8642), ProfileEntry("Codex (Demo)", 8643))

    private fun nowSec() = System.currentTimeMillis() / 1000.0

    fun sessions(): List<Session> = listOf(
        Session("demo-session-001", "여행 계획", "가을 교토 여행을 계획해 줘", nowSec() - 3600, "default"),
        Session("demo-session-002", "저녁 메뉴 추천", "오늘 저녁 뭐 해 먹을까?", nowSec() - 86400, "default"),
    )

    fun messages(sessionId: String): List<ChatMessage> = when (sessionId) {
        "demo-session-001" -> listOf(
            ChatMessage("d1", "user", "가을 교토 여행을 계획해 줘. 꼭 가봐야 할 곳이 어디야?", nowSec() - 3700),
            ChatMessage(
                "d2", "assistant",
                """
                좋은 선택이에요! 교토의 가을(10월 말~11월 중순)은 정말 아름답습니다. 꼭 가볼 곳은 다음과 같아요.

                **1. 아라시야마**
                대나무 숲과 텐류지 정원이 붉고 노랗게 물듭니다.

                **2. 후시미 이나리**
                천 개의 도리이 문과 단풍의 조합이 환상적이에요. 일출 무렵이 가장 좋습니다.

                **3. 킨카쿠지(금각사)**
                단풍에 둘러싸인 연못 위의 금빛 누각이 대표 풍경입니다.

                **4. 철학자의 길**
                수로를 따라 단풍나무가 늘어선 산책로로, 11월에 절정입니다.

                **5. 에이칸도**
                교토 최고의 단풍 명소 중 하나로, 야간 라이트업이 특히 아름답습니다.

                3일 일정표를 짜 드릴까요?
                """.trimIndent(),
                nowSec() - 3650,
            ),
            ChatMessage("d3", "user", "응, 3일 일정표 만들어 줘!", nowSec() - 3600),
        )
        "demo-session-002" -> listOf(
            ChatMessage("e1", "user", "오늘 저녁 뭐 해 먹을까? 닭고기, 마늘, 레몬, 파스타가 있어.", nowSec() - 86500),
            ChatMessage(
                "e2", "assistant",
                """
                **레몬 갈릭 치킨 파스타**를 추천해요! 간단한 레시피입니다.

                **재료 (2인분)**
                - 파스타 200g
                - 닭가슴살 2덩이(얇게 썰기)
                - 마늘 4쪽(다지기)
                - 레몬 1개(즙과 껍질)
                - 올리브유 2큰술, 소금, 후추, 파슬리

                **순서**
                1. 소금물에 파스타를 알덴테로 삶고 면수 반 컵을 남겨 두세요.
                2. 닭고기에 소금·후추를 하고 올리브유에 양면 3~4분씩 굽습니다.
                3. 같은 팬에 마늘을 1분 볶고 레몬즙, 껍질, 면수를 넣어요.
                4. 파스타와 닭고기를 넣어 버무리고 파슬리를 올리면 완성!

                30분이면 충분해요. 맛있게 드세요 🍋
                """.trimIndent(),
                nowSec() - 86400,
            ),
        )
        else -> emptyList()
    }

    /** 데모 답변(순환). */
    val scriptedResponses = listOf(
        """
        3일 교토 가을 일정을 제안드려요.

        **1일차 — 동쪽 교토**
        오전: 후시미 이나리(8시 전 도착 추천)
        오후: 기요미즈데라, 니넨자카·산넨자카 거리
        저녁: 기온 산책과 가이세키 저녁

        **2일차 — 아라시야마와 서쪽**
        오전: 대나무 숲, 텐류지 정원
        오후: 니조성, 킨카쿠지
        저녁: 석양 무렵 철학자의 길

        **3일차 — 중심가**
        오전: 에이칸도
        오후: 니시키 시장에서 먹거리 쇼핑

        팁: ICOCA 같은 교통카드가 있으면 버스·지하철 이용이 편해요.
        """.trimIndent(),
        "좋은 질문이에요! 말씀해 주신 내용을 바탕으로 도와드릴게요. 더 자세한 내용이 필요하거나 다른 방향을 원하시면 편하게 말씀해 주세요.",
        "알겠습니다! 공유해 주신 내용을 기준으로 이렇게 제안드려요. 궁금한 점은 언제든 이어서 물어보세요.",
    )

    // ---- Hermes-bot ----

    val bots = listOf(
        Bot(1, "planner", "플래너", "Hermes (Demo)", "🗓️", "일정과 할 일을 정리해 주는 봇", "online", "active", 101, "이번 주 일정을 정리했어요.", 1),
        Bot(2, "coder", "코더", "Codex (Demo)", "💻", "코드 작성과 리뷰를 돕는 봇", "online", "active", 102, "리뷰할 코드를 보내 주세요.", 2),
        Bot(3, "researcher", "리서처", "Hermes (Demo)", "🔎", "자료 조사와 요약을 맡는 봇", "offline", "active", 103, "조사할 주제를 알려 주세요.", 3),
    )

    val channels = listOf(
        BotChannel(101, "dm-planner", "플래너", null, false, "active", null, listOf(BotMember(1, "planner", "플래너", "🗓️", "online", 1)), "이번 주 일정을 정리했어요.", kind = "dm"),
        BotChannel(102, "dm-coder", "코더", null, false, "active", null, listOf(BotMember(2, "coder", "코더", "💻", "online", 2)), "리뷰할 코드를 보내 주세요.", kind = "dm"),
        BotChannel(
            201, "team", "팀 채널", "프로젝트 논의", true, "active", null,
            listOf(BotMember(1, "planner", "플래너", "🗓️", "online", 1), BotMember(2, "coder", "코더", "💻", "online", 2)),
            "배포 일정 논의", kind = "channel",
        ),
    )

    val workspaces = listOf(
        Workspace(1, "folder", "문서", "/Users/demo/Documents"),
        Workspace(2, "repo", "Hermes-Chat", "/Users/demo/repos/elec100-design/Hermes-Chat", "https://github.com/elec100-design/Hermes-Chat", "main"),
    )

    fun botMessages(channelId: Int): List<BotMessage> = when (channelId) {
        101 -> listOf(
            BotMessage(1001, 101, 1, "user", "나", "@planner 이번 주 일정 정리해 줘", "done"),
            BotMessage(1002, 101, 2, "bot", "planner", "월: 기획 회의, 수: 디자인 리뷰, 금: 스테이징 배포로 정리했어요.", "done", authorBotId = 1, displayName = "플래너", avatar = "🗓️", tone = 1),
        )
        102 -> listOf(
            BotMessage(1101, 102, 1, "bot", "coder", "리뷰할 코드를 보내 주세요.", "done", authorBotId = 2, displayName = "코더", avatar = "💻", tone = 2),
        )
        201 -> listOf(
            BotMessage(2001, 201, 1, "user", "나", "@planner @coder 금요일 배포 괜찮을까요?", "done"),
            BotMessage(2002, 201, 2, "bot", "planner", "일정상 문제 없어요. 승인만 나면 진행할게요.", "done", authorBotId = 1, displayName = "플래너", avatar = "🗓️", tone = 1),
        )
        else -> emptyList()
    }

    fun approvals(): List<BotApproval> = listOf(
        BotApproval(
            1, "deploy", "스테이징 서버에 배포", "최신 빌드를 스테이징 환경에 배포합니다.",
            "deploy --env staging", "medium", "pending", 2, 201,
        ),
    )

    val botReplies = listOf(
        "네, 확인했어요! 데모 모드라서 실제 작업은 하지 않지만, 실제 서버에 연결하면 이렇게 답변이 실시간으로 표시됩니다.",
        "좋아요. 요청하신 내용을 정리해서 알려드릴게요. (데모 응답입니다)",
    )
}
