package com.coinrangersdebthero.aikeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolLayoutTest {
    @Test
    fun symbolsComeFirstAndEmojiUseTheLastTwoPages() {
        assertEquals(4, SYMBOL_PAGES.size)
        assertEquals(2, SYMBOL_PAGES.count { page -> !page.isEmoji })
        assertEquals(EMOJI_PAGE_INDEX, 2)
        assertEquals(EMOJI_PEOPLE_PAGE_INDEX, 3)
        assertTrue(SYMBOL_PAGES[SYMBOLS_PAGE_INDEX].title == "數字")
        assertTrue(SYMBOL_PAGES[ADVANCED_SYMBOLS_PAGE_INDEX].title == "特殊")
    }

    @Test
    fun emojiPagesShowSmileysThenGesturesAndPeople() {
        val smileys = SYMBOL_PAGES[EMOJI_PAGE_INDEX].items
        val people = SYMBOL_PAGES[EMOJI_PEOPLE_PAGE_INDEX].items

        assertTrue(smileys.contains("😀"))
        assertTrue(smileys.contains("😭"))
        assertTrue(smileys.contains("❤️"))
        assertTrue(smileys.contains("✨"))
        assertFalse(smileys.contains("👍"))

        assertTrue(people.contains("👍"))
        assertTrue(people.contains("🙏"))
        assertTrue(people.contains("👀"))
        assertTrue(people.contains("💃"))
        assertFalse(people.contains("😀"))
    }

    @Test
    fun symbolTabTitlesFollowTheSelectedInterfaceLanguage() {
        assertEquals(
            "Numbers",
            SYMBOL_PAGES[SYMBOLS_PAGE_INDEX].displayTitle(KeyboardUiLanguage.ENGLISH)
        )
        assertEquals(
            "數字",
            SYMBOL_PAGES[SYMBOLS_PAGE_INDEX].displayTitle(KeyboardUiLanguage.CHINESE)
        )
    }

    @Test
    fun everyPageFillsWholeRowsOfEightAndHasNoDuplicates() {
        SYMBOL_PAGES.forEach { page ->
            assertEquals(
                "${page.title} must fill complete rows of 8",
                0,
                page.items.size % 8
            )
            assertEquals(
                "${page.title} must not repeat emoji",
                page.items.size,
                page.items.distinct().size
            )
            assertTrue(page.items.none(String::isBlank))
        }
    }

    @Test
    fun fullWidthModeConvertsAsciiAndKeepsNativeSymbols() {
        assertEquals("１", applySymbolWidth("1", true))
        assertEquals("，", applySymbolWidth(",", true))
        assertEquals("。", applySymbolWidth(".", true))
        assertEquals("？", applySymbolWidth("?", true))
        assertEquals("＝", applySymbolWidth("=", true))
        assertEquals("＠", applySymbolWidth("@", true))
        assertEquals("∞", applySymbolWidth("∞", true))
        assertEquals("＋", applySymbolWidth("+", true))
        assertEquals("［］｛｝＼", applySymbolWidth("[]{}\\", true))
        assertEquals("©®™✓", applySymbolWidth("©®™✓", true))
    }

    @Test
    fun advancedSymbolsIncludeTheRequestedSpecialCharacters() {
        assertTrue(
            SYMBOL_PAGES[ADVANCED_SYMBOLS_PAGE_INDEX].items.containsAll(
                listOf(
                    "~", "`", "|", "•", "√", "π", "÷", "×", "§", "Δ", "£", "¢",
                    "€", "¥", "^", "°", "=", "{", "}", "\\", "%", "©", "®", "™",
                    "✓", "[", "]"
                )
            )
        )
    }

    @Test
    fun halfWidthModeKeepsTheOriginalItem() {
        assertEquals("1", applySymbolWidth("1", false))
        assertEquals(",", applySymbolWidth(",", false))
        assertEquals(".", applySymbolWidth(".", false))
        assertEquals("∞", applySymbolWidth("∞", false))
    }
}
