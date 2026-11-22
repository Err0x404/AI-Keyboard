package com.example.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeOptionMappingTest {
    @Test
    fun simplificationUsesOptionDeclaredByCurrentBopomofoSchema() {
        val schema = """
            switches:
              - options:
                  - zh_hant
                  - zh_hans
        """.trimIndent()

        assertEquals("zh_hans", RimeResourceInstaller.simplificationOptionFromSchema(schema))
        assertEquals("zh_hans", mapOptionName("simplification", "zh_hans"))
    }

    @Test
    fun legacySimplificationOptionRemainsSupported() {
        val schema = """
            switches:
              - name: simplification
        """.trimIndent()

        assertEquals(
            "simplification",
            RimeResourceInstaller.simplificationOptionFromSchema(schema)
        )
        assertEquals("other_option", mapOptionName("other_option", "zh_hans"))
    }

    @Test
    fun pinyinSchemaUsesItsOwnSimplificationSwitch() {
        val schema = """
            switches:
              - name: simplification
                states: [ 漢字, 汉字 ]
        """.trimIndent()

        assertEquals(
            "simplification",
            RimeResourceInstaller.simplificationOptionFromSchema(schema)
        )
        assertEquals(
            "simplification",
            mapOptionName("simplification", "simplification")
        )
    }

    @Test
    fun asciiKeysMapToTheirPhysicalKeyCodes() {
        assertEquals('a'.code, rimeKeyCodeForAscii("a"))
        assertEquals('z'.code, rimeKeyCodeForAscii("z"))
        assertEquals(null, rimeKeyCodeForAscii("ab"))
    }

    @Test
    fun incompleteAssetTreeReportsCoreResourcesAndOpenCcDictionariesAreDiscoverable() {
        val missing = RimeResourceInstaller.missingRequiredAssets(
            setOf("default.custom.yaml", "default.yaml")
        )
        assertTrue(missing.contains("bopomofo.schema.yaml"))
        assertTrue(missing.contains("terra_pinyin.schema.yaml"))
        assertTrue(missing.contains("essay.txt"))

        assertEquals(
            setOf("opencc/STCharacters.ocd2", "opencc/STPhrases.ocd2"),
            RimeResourceInstaller.openCcDictionaryPaths(
                """{"conversion_chain":[{"dict":{"file":"STCharacters.ocd2"}},{"dict":{"file":"STPhrases.ocd2"}}]}"""
            )
        )
    }

    @Test
    fun zhuyinKeysMapToDaqianPhysicalKeysAndCompositionIsParsed() {
        assertEquals('1'.code, rimeKeyCodeForZhuyin("ㄅ"))
        assertEquals('2'.code, rimeKeyCodeForZhuyin("ㄉ"))
        assertEquals('s'.code, rimeKeyCodeForZhuyin("ㄋ"))
        assertEquals('c'.code, rimeKeyCodeForZhuyin("ㄏ"))
        assertEquals('a'.code, rimeKeyCodeForZhuyin("ㄇ"))
        assertEquals('8'.code, rimeKeyCodeForZhuyin("ㄚ"))
        assertEquals('9'.code, rimeKeyCodeForZhuyin("ㄞ"))
        assertEquals('0'.code, rimeKeyCodeForZhuyin("ㄢ"))
        assertEquals('-'.code, rimeKeyCodeForZhuyin("ㄦ"))
        assertEquals('6'.code, rimeKeyCodeForZhuyin("ˊ"))
        assertEquals('3'.code, rimeKeyCodeForZhuyin("ˇ"))
        assertEquals('4'.code, rimeKeyCodeForZhuyin("ˋ"))
        assertEquals('7'.code, rimeKeyCodeForZhuyin("˙"))
        assertEquals(null, rimeKeyCodeForZhuyin("ㄋㄏ"))

        assertEquals(
            RimeCompositionState("ㄋㄏ", listOf("你好嗎？", "你們好")),
            parseRimeComposition("ㄋㄏ\n你好嗎？\n你們好")
        )
        assertEquals(
            RimeCompositionState("", emptyList()),
            parseRimeComposition("\n")
        )
    }
}
