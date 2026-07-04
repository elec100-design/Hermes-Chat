import Foundation

/// App Review용 데모 데이터 — 서버나 계정 없이 앱의 모든 기능을 체험할 수 있도록 한다.
/// isDemoMode == true 일 때 각 뷰/뷰모델이 이 데이터를 사용한다.
enum DemoData {

    // MARK: - Profiles

    static let profiles: [HermesProfile] = [
        HermesProfile(id: UUID(uuidString: "DDDDDD01-DDDD-DDDD-DDDD-DDDDDDDDDD01")!,
                      name: "Hermes (Demo)",
                      port: 8642),
        HermesProfile(id: UUID(uuidString: "DDDDDD02-DDDD-DDDD-DDDD-DDDDDDDDDD02")!,
                      name: "Codex (Demo)",
                      port: 8643),
    ]

    // MARK: - Sessions

    static let sessions: [Session] = [
        Session(
            id: "demo-session-001",
            title: "Travel Planning",
            preview: "Let's plan a trip to Kyoto in autumn",
            updatedAt: Date().addingTimeInterval(-3600),
            source: "default"
        ),
        Session(
            id: "demo-session-002",
            title: "Recipe Ideas",
            preview: "What should I cook for dinner tonight?",
            updatedAt: Date().addingTimeInterval(-86400),
            source: "default"
        ),
    ]

    // MARK: - Chat History (session 001 & 002)

    static let messagesForSession001: [ChatMessage] = [
        ChatMessage(
            id: UUID(uuidString: "AAAA0001-0000-0000-0000-000000000001")!,
            role: .user,
            content: "Let's plan a trip to Kyoto in autumn. What are the must-see spots?",
            createdAt: Date().addingTimeInterval(-3700)
        ),
        ChatMessage(
            id: UUID(uuidString: "AAAA0001-0000-0000-0000-000000000002")!,
            role: .assistant,
            content: """
            Great choice! Kyoto in autumn (late October–mid-November) is stunning. Here are the must-sees:

            **1. Arashiyama**
            The bamboo grove and Tenryu-ji temple garden turn spectacular shades of red and gold.

            **2. Fushimi Inari**
            The famous thousand torii gates look magical with autumn foliage framing them — best at sunrise.

            **3. Kinkaku-ji (Golden Pavilion)**
            The reflection of the golden temple in the pond surrounded by autumn leaves is iconic.

            **4. Philosopher's Path**
            A canal-side walk lined with maple trees — the colours peak in November.

            **5. Eikan-do Temple**
            One of Kyoto's best "momiji" (maple) spots, especially beautiful at night during the light-up season.

            Would you like me to put together a 3-day itinerary?
            """,
            createdAt: Date().addingTimeInterval(-3650)
        ),
        ChatMessage(
            id: UUID(uuidString: "AAAA0001-0000-0000-0000-000000000003")!,
            role: .user,
            content: "Yes, please make a 3-day itinerary!",
            createdAt: Date().addingTimeInterval(-3600)
        ),
    ]

    static let messagesForSession002: [ChatMessage] = [
        ChatMessage(
            id: UUID(uuidString: "BBBB0001-0000-0000-0000-000000000001")!,
            role: .user,
            content: "What should I cook for dinner tonight? I have chicken, garlic, lemon, and pasta.",
            createdAt: Date().addingTimeInterval(-86500)
        ),
        ChatMessage(
            id: UUID(uuidString: "BBBB0001-0000-0000-0000-000000000002")!,
            role: .assistant,
            content: """
            Perfect ingredients for **Lemon Garlic Chicken Pasta**! Here's a quick recipe:

            **Ingredients (2 servings)**
            - 200 g pasta (spaghetti or linguine)
            - 2 chicken breasts, sliced thin
            - 4 garlic cloves, minced
            - Juice & zest of 1 lemon
            - 2 tbsp olive oil, salt & pepper, fresh parsley

            **Steps**
            1. Cook pasta in salted water until al dente. Reserve ½ cup pasta water.
            2. Season chicken with salt & pepper, sear in olive oil 3-4 min per side. Set aside.
            3. In same pan, sauté garlic 1 min. Add lemon juice + zest + pasta water.
            4. Toss in pasta and sliced chicken. Garnish with parsley.

            Ready in under 30 minutes! Enjoy 🍋
            """,
            createdAt: Date().addingTimeInterval(-86400)
        ),
    ]

    static func messages(for sessionId: String) -> [ChatMessage] {
        switch sessionId {
        case "demo-session-001": return messagesForSession001
        case "demo-session-002": return messagesForSession002
        default: return []
        }
    }

    // MARK: - Demo AI Responses (round-robin)

    private static var responseIndex = 0

    private static let scriptedResponses: [String] = [
        """
        Here's a suggested 3-day Kyoto autumn itinerary:

        **Day 1 — Eastern Kyoto**
        Morning: Fushimi Inari (arrive before 8 am to beat crowds)
        Afternoon: Kiyomizu-dera Temple, Ninen-zaka & Sannen-zaka lanes
        Evening: Gion district stroll and kaiseki dinner

        **Day 2 — Arashiyama & West**
        Morning: Bamboo Grove + Tenryu-ji garden
        Afternoon: Nijo Castle, Kinkaku-ji
        Evening: Philosopher's Path at sunset

        **Day 3 — Central & Hidden Gems**
        Morning: Eikan-do night light-up (check dates — usually Nov 1–30)
        Afternoon: Nishiki Market for food souvenirs
        Evening: Train back to Tokyo or onward travel

        Tip: Get a IC card (Suica/ICOCA) for seamless bus/subway rides across the city.
        """,
        "That's a great question! I'd be happy to help you explore that further. Let me know if you'd like more details or want to take a different direction.",
        "Understood! Here's what I'd suggest based on what you've shared. Feel free to ask follow-up questions — I'm here to help you get the best outcome.",
    ]

    @MainActor
    static func nextResponse() -> String {
        let r = scriptedResponses[responseIndex % scriptedResponses.count]
        responseIndex += 1
        return r
    }

    // MARK: - Kanban

    static let kanbanBoardSummaries: [KanbanBoardSummary] = [
        KanbanBoardSummary(
            board: "demo-board",
            name: "My Projects (Demo)",
            counts: ["todo": 2, "ready": 1, "done": 1]
        ),
    ]

    static let kanbanBoard = KanbanBoard(
        name: "My Projects (Demo)",
        board: "demo-board",
        updatedAt: nil,
        tasks: [
            KanbanTask(
                id: "demo-task-01",
                title: "Write API documentation",
                detail: "Document all REST endpoints with examples",
                status: .todo,
                assignee: "Hermes",
                sessionId: nil,
                createdAt: nil,
                updatedAt: nil
            ),
            KanbanTask(
                id: "demo-task-02",
                title: "Set up CI pipeline",
                detail: "Configure GitHub Actions for automated testing",
                status: .todo,
                assignee: "Codex",
                sessionId: nil,
                createdAt: nil,
                updatedAt: nil
            ),
            KanbanTask(
                id: "demo-task-03",
                title: "Deploy to staging",
                detail: "Push the latest build to the staging environment",
                status: .ready,
                assignee: "Hermes",
                sessionId: nil,
                createdAt: nil,
                updatedAt: nil
            ),
            KanbanTask(
                id: "demo-task-04",
                title: "Kick-off meeting notes",
                detail: "Summarised and distributed to all stakeholders",
                status: .done,
                assignee: nil,
                sessionId: nil,
                createdAt: nil,
                updatedAt: nil
            ),
        ]
    )
}
