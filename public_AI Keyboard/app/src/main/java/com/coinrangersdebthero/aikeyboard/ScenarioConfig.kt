package com.coinrangersdebthero.aikeyboard

import androidx.compose.runtime.Immutable

@Immutable
data class ScenarioCategory(
    val id: Int,
    val title: String,
    val subOptions: List<ScenarioOption>
)

@Immutable
data class ScenarioOption(
    val id: Int,
    val title: String,
    val systemPrompt: String
)

object ScenarioConfig {
    fun getAllScenarios(): List<ScenarioCategory> = listOf(
        ScenarioCategory(
            id = 1,
            title = "工作商務",
            subOptions = listOf(
                ScenarioOption(
                    id = 1,
                    title = "正式郵件",
                    systemPrompt = "請以專業正式的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 16,
                    title = "工作回覆",
                    systemPrompt = "請根據剪貼簿內容與當前輸入，以清楚、專業且符合工作情境的語氣撰寫回覆。"
                ),
                ScenarioOption(
                    id = 2,
                    title = "簡潔高效",
                    systemPrompt = "請以簡潔明瞭的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 3,
                    title = "委婉拒絕",
                    systemPrompt = "請以委婉的語氣，根據剪貼簿內容與當前輸入，生成拒絕的回覆。"
                )
            )
        ),
        ScenarioCategory(
            id = 2,
            title = "社交聊天",
            subOptions = listOf(
                ScenarioOption(
                    id = 17,
                    title = "日常對話",
                    systemPrompt = "請以自然親切的日常對話語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 18,
                    title = "簡訊精簡",
                    systemPrompt = "請將內容整理成簡短、自然、適合簡訊傳送的回覆。"
                ),
                ScenarioOption(
                    id = 4,
                    title = "幽默接梗",
                    systemPrompt = "請以幽默風趣的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 5,
                    title = "高情商共情",
                    systemPrompt = "請以高情商的語氣，根據剪貼簿內容與當前輸入，生成共情的回覆。"
                ),
                ScenarioOption(
                    id = 6,
                    title = "破冰開場",
                    systemPrompt = "請以破冰開場的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                )
            )
        ),
        ScenarioCategory(
            id = 3,
            title = "戀愛約會",
            subOptions = listOf(
                ScenarioOption(
                    id = 7,
                    title = "調情曖昧",
                    systemPrompt = "請以調情曖昧的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 8,
                    title = "自信直球",
                    systemPrompt = "請以自信直球的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 9,
                    title = "溫柔關心",
                    systemPrompt = "請以溫柔關心的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                )
            )
        ),
        ScenarioCategory(
            id = 4,
            title = "客服銷售",
            subOptions = listOf(
                ScenarioOption(
                    id = 10,
                    title = "標準話術",
                    systemPrompt = "請以標準客服話術的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 11,
                    title = "熱情推薦",
                    systemPrompt = "請以熱情推薦的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 12,
                    title = "議價應對",
                    systemPrompt = "請以議價應對的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                )
            )
        ),
        ScenarioCategory(
            id = 5,
            title = "翻譯潤色",
            subOptions = listOf(
                ScenarioOption(
                    id = 13,
                    title = "翻譯成英文",
                    systemPrompt = "請將內容翻譯成英文，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 14,
                    title = "潤色通順",
                    systemPrompt = "請以潤色通順的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 15,
                    title = "總結要點",
                    systemPrompt = "請以總結要點的語氣，根據剪貼簿內容與當前輸入，生成回覆。"
                ),
                ScenarioOption(
                    id = 19,
                    title = "繁體轉簡體",
                    systemPrompt = "請將剪貼簿內容與當前輸入轉換為簡體中文，保留原意並只輸出轉換後的內容。"
                ),
                ScenarioOption(
                    id = 20,
                    title = "簡體轉繁體",
                    systemPrompt = "請將剪貼簿內容與當前輸入轉換為繁體中文，保留原意並只輸出轉換後的內容。"
                )
            )
        )
    )
}

internal fun scenarioCategoryDisplayTitle(title: String, language: KeyboardUiLanguage): String =
    if (language == KeyboardUiLanguage.CHINESE) title else when (title) {
        "工作商務" -> "Work"
        "社交聊天" -> "Social"
        "戀愛約會" -> "Dating"
        "客服銷售" -> "Customer service"
        "翻譯潤色" -> "Translation & editing"
        else -> title
    }

internal fun scenarioOptionDisplayTitle(
    option: ScenarioOption,
    language: KeyboardUiLanguage
): String = if (language == KeyboardUiLanguage.CHINESE) option.title else when (option.id) {
    1 -> "Formal email"
    2 -> "Concise reply"
    3 -> "Polite decline"
    4 -> "Witty response"
    5 -> "Empathetic reply"
    6 -> "Icebreaker"
    7 -> "Flirty reply"
    8 -> "Confident reply"
    9 -> "Caring reply"
    10 -> "Standard support"
    11 -> "Enthusiastic recommendation"
    12 -> "Negotiation"
    13 -> "Translate to English"
    14 -> "Polish text"
    15 -> "Summarize key points"
    16 -> "Work reply"
    17 -> "Everyday conversation"
    18 -> "Concise text"
    19 -> "Traditional to Simplified"
    20 -> "Simplified to Traditional"
    else -> option.title
}
