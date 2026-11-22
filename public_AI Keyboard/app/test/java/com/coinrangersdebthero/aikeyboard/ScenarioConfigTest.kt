package com.coinrangersdebthero.aikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioConfigTest {
    @Test
    fun providesAllFiveCategoriesAndScenarioExamples() {
        val scenarios = ScenarioConfig.getAllScenarios()
        val options = scenarios.flatMap { it.subOptions }

        assertEquals(
            listOf("工作商務", "社交聊天", "戀愛約會", "客服銷售", "翻譯潤色"),
            scenarios.map { it.title }
        )
        assertEquals(20, options.size)
        assertTrue(
            options.map { it.title }.containsAll(
                listOf(
                    "日常對話",
                    "工作回覆",
                    "簡訊精簡",
                    "繁體轉簡體",
                    "簡體轉繁體"
                )
            )
        )
    }

    @Test
    fun allScenarioAndOptionIdsAreUniqueAndPromptsArePresent() {
        val scenarios = ScenarioConfig.getAllScenarios()
        val options = scenarios.flatMap { it.subOptions }

        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
        assertEquals(options.size, options.map { it.id }.toSet().size)
        assertTrue(
            options.all {
                it.title.isNotBlank() && it.systemPrompt.isNotBlank()
            }
        )
    }

    @Test
    fun scenarioCategoriesAndOptionsHaveEnglishInterfaceLabels() {
        val category = ScenarioConfig.getAllScenarios().first()
        val option = category.subOptions.first()

        assertEquals(
            "Work",
            scenarioCategoryDisplayTitle(category.title, KeyboardUiLanguage.ENGLISH)
        )
        assertEquals(
            "Formal email",
            scenarioOptionDisplayTitle(option, KeyboardUiLanguage.ENGLISH)
        )
        assertEquals(
            option.title,
            scenarioOptionDisplayTitle(option, KeyboardUiLanguage.CHINESE)
        )
    }
}
