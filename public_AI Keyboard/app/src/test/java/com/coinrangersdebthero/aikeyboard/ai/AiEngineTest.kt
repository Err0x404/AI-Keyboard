package com.coinrangersdebthero.aikeyboard.ai

import com.coinrangersdebthero.aikeyboard.ScenarioConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEngineTest {
    @Test
    fun suggestionPromptUsesGemmaTurnFormatAndKeepsInput() {
        val prompt = AiEngine.buildSuggestionPrompt("你")

        assertTrue(prompt.startsWith("<start_of_turn>user\n"))
        assertTrue(prompt.contains("text completion engine"))
        assertTrue(prompt.contains("one per line"))
        assertTrue(prompt.endsWith("你<end_of_turn>\n<start_of_turn>model\n"))
    }

    @Test
    fun nextWordPromptAndCompletionLimitMatchKeyboardPredictionContract() {
        val prompt = AiEngine.buildNextWordPrompt("hel")

        assertTrue(prompt.startsWith("<start_of_turn>user\n"))
        assertTrue(prompt.contains("complete a single English word"))
        assertTrue(prompt.endsWith("hel<end_of_turn>\n<start_of_turn>model\n"))

        val parameters = AiEngine.buildCompletionParameters(prompt, 8)
        assertEquals(8, parameters["n_predict"])
        assertEquals(0.2, parameters["temperature"])
        assertEquals(true, parameters["emit_partial_completion"])
        assertEquals(
            listOf("<end_of_turn>", "<start_of_turn>", "<eos>", "<|im_end|>", "<|im_start|>"),
            parameters["stop"]
        )
    }

    @Test
    fun followingWordPromptRequestsOnlyNextEnglishWordAndUsesLastFiftyCharacters() {
        val context = "x".repeat(60)
        val prompt = AiEngine.buildFollowingWordPrompt(context)

        assertTrue(prompt.contains("single most likely next English word"))
        assertTrue(prompt.endsWith("${"x".repeat(50)}<end_of_turn>\n<start_of_turn>model\n"))
    }

    @Test
    fun nextWordPredictionDoesNotProcessZhuyinComposition() = runBlocking {
        assertEquals(null, AiEngine.predictNextWord("ㄓㄨㄢ"))
    }

    @Test
    fun completedWordIsFilteredAndIncludesTheTypedPrefix() {
        assertEquals("hello", AiEngine.extractCompletedWord("lo, world", "hel"))
        assertEquals("hello", AiEngine.extractCompletedWord("hello.", "hel"))
        assertEquals(null, AiEngine.extractCompletedWord("hel", "hel"))
        assertEquals("next", AiEngine.extractFirstWord("next, word!"))
    }

    @Test
    fun scenarioPromptCombinesInputAndClipboardWithSelectedInstruction() {
        val option = ScenarioConfig.getAllScenarios().first().subOptions.first()
        val prompt = AiEngine.buildScenarioPrompt(option, "請確認會議時間", "週五下午開會")

        assertTrue(prompt.contains(option.systemPrompt))
        assertTrue(prompt.contains("根據 [請確認會議時間] [週五下午開會]"))
        assertTrue(prompt.contains("請使用繁體中文"))
        assertTrue(prompt.contains("只輸出 3 則可以直接送出的短回覆"))
    }

    @Test
    fun scenarioContextFallsBackToInputWhenClipboardIsBlank() {
        assertEquals(
            "根據 [請確認會議時間]",
            AiEngine.buildScenarioContext("請確認會議時間", " \n ")
        )
    }

    @Test
    fun scenarioContextUsesClipboardWhenInputIsBlank() {
        assertEquals(
            "根據 [週五下午開會]",
            AiEngine.buildScenarioContext(" ", "週五下午開會")
        )
    }

    @Test
    fun scenarioPromptRequestsEnglishRepliesWhenEnglishModeOrInputIsDetected() {
        val option = ScenarioConfig.getAllScenarios().first().subOptions.first()
        val prompt = AiEngine.buildScenarioPrompt(option, "Could you reply?", "", true)

        assertTrue(prompt.contains("Output exactly 3 concise, ready-to-send replies in English"))
        assertTrue(prompt.contains("keep every reply in English"))
    }

    @Test
    fun dangerousScenarioContextRequiresCalmRelevantChineseReplies() {
        val option = ScenarioConfig.getAllScenarios().first().subOptions.first()
        val prompt = AiEngine.buildScenarioPrompt(
            option,
            "有人在7樓垂下，請確認狀況",
            "窗戶故障"
        )

        assertTrue(prompt.contains("根據 [有人在7樓垂下，請確認狀況] [窗戶故障]"))
        assertTrue(prompt.contains("保持冷靜、客觀、切題"))
        assertTrue(prompt.contains("好消息"))
        assertTrue(prompt.contains("感到興奮"))
        assertTrue(prompt.contains("不可套用無關罐頭句"))
    }

    @Test
    fun dangerousClipboardContextRequiresCalmRelevantEnglishReplies() {
        val option = ScenarioConfig.getAllScenarios().first().subOptions.first()

        assertTrue(AiEngine.hasSafetySensitiveContext("", "A fall from the 7th floor"))
        val prompt = AiEngine.buildScenarioPrompt(
            option,
            "Please check this",
            "There was an accident near the building",
            replyInEnglish = true
        )

        assertTrue(prompt.contains("Stay calm, objective and on-topic"))
        assertTrue(prompt.contains("good news"))
        assertTrue(prompt.contains("Base every reply on the supplied input and clipboard context"))
    }

    @Test
    fun parsesDistinctNumberedSuggestionsUpToTheLimit() {
        val suggestions = AiEngine.parseSuggestions(
            "1. 你好\n- 你好嗎\n3、你今天好嗎\n你好\n4. 你有空嗎\n5. 你吃飯了嗎\n6. 忙嗎"
        )

        assertEquals(
            listOf("你好", "你好嗎", "你今天好嗎", "你有空嗎", "你吃飯了嗎"),
            suggestions
        )
    }

    @Test
    fun stripsGemmaControlTokensFromGeneratedReplies() {
        val reply = AiEngine.parseSuggestions(
            "<start_of_turn>model\n好的，我馬上處理。<end_of_turn>\n"
        )

        assertEquals(listOf("好的，我馬上處理。"), reply)
    }
}
