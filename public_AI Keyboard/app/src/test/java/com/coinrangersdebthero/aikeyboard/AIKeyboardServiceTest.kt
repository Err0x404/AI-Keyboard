package com.coinrangersdebthero.aikeyboard

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AIKeyboardServiceTest {
    @Test
    fun selectsChineseLayoutForChineseLocaleHint() {
        assertEquals(
            KeyboardMode.CHINESE,
            resolveKeyboardMode(EditorInfo.TYPE_CLASS_TEXT, "zh-TW")
        )
    }

    @Test
    fun selectsNumericLayoutForNumberAndPhoneInputTypes() {
        assertEquals(
            KeyboardMode.NUMERIC,
            resolveKeyboardMode(EditorInfo.TYPE_CLASS_NUMBER, "zh-TW")
        )
        assertEquals(
            KeyboardMode.NUMERIC,
            resolveKeyboardMode(EditorInfo.TYPE_CLASS_PHONE, null)
        )
    }

    @Test
    fun passwordFieldsDisablePredictiveFeaturesAndUsePasswordClassification() {
        assertEquals(
            true,
            isPasswordInputType(
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
        assertEquals(
            true,
            isPasswordInputType(
                EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )
        assertEquals(
            false,
            isPasswordInputType(
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
    }

    @Test
    fun urlAndEmailFieldsDisableAutomaticSpacing() {
        assertEquals(
            true,
            isUrlOrEmailInputType(
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI
            )
        )
        assertEquals(
            true,
            isUrlOrEmailInputType(
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
        assertEquals(
            false,
            isUrlOrEmailInputType(EditorInfo.TYPE_CLASS_TEXT)
        )
    }

    @Test
    fun defaultsToEnglishWithoutChineseHint() {
        assertEquals(
            KeyboardMode.ENGLISH,
            resolveKeyboardMode(EditorInfo.TYPE_CLASS_TEXT, "en-US")
        )
        assertEquals(
            KeyboardMode.ENGLISH,
            resolveKeyboardMode(0, null)
        )
    }

    @Test
    fun toolbarModeLabelNamesTheModeToSwitchToAndChineseControlsAreConditional() {
        assertEquals("英", modeSwitcherLabel(KeyboardMode.CHINESE))
        assertTrue(shouldShowChineseToolbarControls(KeyboardMode.CHINESE))

        assertEquals("中", modeSwitcherLabel(KeyboardMode.ENGLISH))
        assertFalse(shouldShowChineseToolbarControls(KeyboardMode.ENGLISH))
        assertFalse(shouldShowChineseToolbarControls(KeyboardMode.NUMERIC))
    }

    @Test
    fun traditionalChineseSchemaControlUsesTheDefaultNonHighlightedStyle() {
        assertFalse(shouldHighlightChineseSchemaControl(isSimplifiedChinese = false))
        assertTrue(shouldHighlightChineseSchemaControl(isSimplifiedChinese = true))
    }

    @Test
    fun schemaSwitcherNamesTheTargetInputMethodInBothInterfaceLanguages() {
        assertEquals(
            "拼",
            chineseSchemaSwitcherLabel(ChineseSchema.ZHUYIN, KeyboardUiLanguage.CHINESE)
        )
        assertEquals(
            "注",
            chineseSchemaSwitcherLabel(ChineseSchema.PINYIN, KeyboardUiLanguage.CHINESE)
        )
        assertEquals(
            "Py",
            chineseSchemaSwitcherLabel(ChineseSchema.ZHUYIN, KeyboardUiLanguage.ENGLISH)
        )
        assertEquals(
            "Zh",
            chineseSchemaSwitcherLabel(ChineseSchema.PINYIN, KeyboardUiLanguage.ENGLISH)
        )
    }

    @Test
    fun interfaceLanguageLocalizesModeLabelsAndPreferenceValues() {
        assertEquals(
            "EN",
            modeSwitcherLabel(KeyboardMode.CHINESE, KeyboardUiLanguage.ENGLISH)
        )
        assertEquals(
            "中",
            modeSwitcherLabel(KeyboardMode.ENGLISH, KeyboardUiLanguage.ENGLISH)
        )
        assertEquals(
            KeyboardUiLanguage.ENGLISH,
            KeyboardUiLanguage.fromPreference("english")
        )
        assertEquals(
            KeyboardUiLanguage.CHINESE,
            KeyboardUiLanguage.fromPreference("chinese")
        )
        assertEquals(
            KeyboardUiLanguage.ENGLISH,
            KeyboardUiLanguage.fromPreference(null)
        )
        assertEquals("en", appLocaleTag(KeyboardUiLanguage.ENGLISH))
        assertEquals("zh-TW", appLocaleTag(KeyboardUiLanguage.CHINESE))
        assertEquals(
            "Type to see suggestions",
            keyboardInterfaceText(
                KeyboardUiLanguage.ENGLISH,
                "輸入文字以取得聯想",
                "Type to see suggestions"
            )
        )
    }

    @Test
    fun selectedTextDetectionRecognizesNonEmptySelections() {
        assertFalse(hasSelectedText(null))
        assertFalse(hasSelectedText(""))
        assertTrue(hasSelectedText("selected text"))
    }

    @Test
    fun clipboardPreviewSummaryHidesBlankTextAndNormalizesWhitespace() {
        assertNull(summarizeClipboardText(" \n\t "))
        assertEquals(
            "Today at sea",
            summarizeClipboardText("  Today\n\tat   sea  ")
        )
    }

    @Test
    fun clipboardPreviewSummaryTruncatesByCodePointWithoutSplittingEmoji() {
        assertEquals("ab😀…", summarizeClipboardText("ab😀cd", maxCharacters = 4))
        assertEquals("abcd", summarizeClipboardText("abcd", maxCharacters = 4))
    }

    @Test
    fun clipboardHistoryKeepsFiveNewestUniqueItems() {
        val history = listOf("five", "four", "three", "two", "one")

        assertEquals(
            listOf("six", "five", "four", "three", "two"),
            updateClipboardHistory(history, "six")
        )
        val duplicate = updateClipboardHistory(history, " four ")
        assertEquals(listOf("four", "five", "three", "two", "one"), duplicate)
        assertEquals(1, duplicate.count { it == "four" })
        assertEquals(history, updateClipboardHistory(history, " "))
    }

    @Test
    fun scenarioReplyLanguageFollowsModeAndUnambiguousInputLanguage() {
        assertTrue(shouldUseEnglishScenarioReply(true, "請幫我回覆", null))
        assertTrue(shouldUseEnglishScenarioReply(false, "Thanks for your help", null))
        assertTrue(shouldUseEnglishScenarioReply(false, "", "Could we meet tomorrow?"))
        assertTrue(
            shouldUseEnglishScenarioReply(
                false,
                "Could you respond?",
                "請確認明天的會議時間"
            )
        )
        assertFalse(shouldUseEnglishScenarioReply(false, "謝謝 for your help", null))
        assertFalse(shouldUseEnglishScenarioReply(false, "", null))
    }

    @Test
    fun zhuyinDictionaryProvidesImmediateCandidatesForCommonSyllables() {
        assertTrue(zhuyinDictionary.size >= 100)
        assertEquals(listOf("我"), zhuyinDictionary["ㄨㄛˇ"])
        assertEquals(listOf("媽", "馬", "嗎", "麻"), zhuyinDictionary["ㄇㄚ"])
        assertEquals(listOf("你", "妳"), resolveZhuyinCandidates("ㄋㄧˇ"))
        assertEquals(listOf("我"), resolveZhuyinCandidates("ㄨㄛˇ"))
    }

    @Test
    fun zhuyinDictionaryResolvesInitialAbbreviationsAndMultiSyllableWords() {
        assertEquals("你好嗎？", resolveZhuyinCandidates("ㄋㄏㄇ").first())
        assertEquals("你好嗎？", resolveZhuyinCandidates("ㄋㄧㄏㄠㄇㄚ").first())
        assertEquals("你好嗎？", resolveZhuyinCandidates("ㄋㄧˇㄏㄠˇㄇㄚ˙").first())
        assertTrue(resolveZhuyinCandidates("ㄋㄧㄏㄠ").contains("你好"))
    }

    @Test
    fun smartPhrasesSupportZhuyinInitialsAndPartialSpellings() {
        assertEquals(
            "比方說",
            resolvePhoneticPhraseCandidates("ㄅㄈㄕ", ChineseSchema.ZHUYIN).first()
        )
        assertTrue(
            resolvePhoneticPhraseCandidates("ㄅㄈ", ChineseSchema.ZHUYIN)
                .contains("比方說")
        )
        assertEquals("比方說", resolveZhuyinCandidates("ㄅㄈㄕ").first())
    }

    @Test
    fun zhuyinInitialRetainsAllMatchingDictionaryCandidates() {
        val candidates = resolveZhuyinCandidates("ㄅ")

        assertTrue(candidates.size > 5)
        assertTrue(candidates.containsAll(listOf("八", "爸")))
    }

    @Test
    fun smartPhrasesSupportPinyinInitialsAndHonorSimplifiedPreference() {
        assertEquals(
            "比方說",
            resolvePhoneticPhraseCandidates("bfs", ChineseSchema.PINYIN).first()
        )
        assertTrue(
            resolvePhoneticPhraseCandidates("bifa", ChineseSchema.PINYIN)
                .contains("比方說")
        )
        assertEquals(
            "比方说",
            resolvePhoneticPhraseCandidates("bfs", ChineseSchema.PINYIN, simplified = true)
                .first()
        )
        assertTrue(isPhoneticInitialAbbreviation("bfs", ChineseSchema.PINYIN))
        assertFalse(isPhoneticInitialAbbreviation("bifa", ChineseSchema.PINYIN))
    }

    @Test
    fun candidateSourcesAreOrderedLocalThenAiThenStatic() {
        assertEquals(
            listOf("hello", "help", "held", "✨ helicopter", "here", "hero"),
            mergeCandidateSources(
                localWords = listOf("hello", "help", "held"),
                aiWord = "helicopter",
                staticWords = listOf("here", "hero"),
                isEnglish = true
            ).map { it.first }
        )
    }

    @Test
    fun candidateSourcesRetainMoreThanFiveDistinctOptions() {
        val candidates = mergeCandidateSources(
            localWords = (1..7).map { "local$it" },
            aiWord = null,
            staticWords = (1..7).map { "static$it" },
            isEnglish = false
        )

        assertEquals(14, candidates.size)
        assertEquals("static7", candidates.last().second)
    }

    @Test
    fun followingWordAiPredictionCanBePlacedFirst() {
        assertEquals(
            listOf("✨ welcome", "world", "what"),
            mergeCandidateSources(
                localWords = listOf("welcome", "world"),
                aiWord = "welcome",
                staticWords = listOf("what"),
                isEnglish = true,
                prioritizeAi = true
            ).map { it.first }
        )
    }

    @Test
    fun automaticAiSuggestionsAreMergedBetweenLocalAndStaticCandidates() {
        assertEquals(
            listOf("hello", "✨ how are you", "✨ hope you are well", "help"),
            mergeCandidateSources(
                localWords = listOf("hello"),
                aiWord = null,
                aiWords = listOf("how are you", "hope you are well"),
                staticWords = listOf("help"),
                isEnglish = true
            ).map { it.first }
        )
    }

    @Test
    fun aiSuggestionsCommitTheWholeComposingCandidate() {
        assertEquals("hello there", completeAiCandidate("hel", "lo there"))
        assertEquals("hello there", completeAiCandidate("hel", "hello there"))
        assertEquals("你好嗎", completeAiCandidate("你", "好嗎"))
    }
}
