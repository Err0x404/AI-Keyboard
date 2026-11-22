package com.example.ime

internal data class RimeCompositionState(
    val preedit: String,
    val candidates: List<String>
)

private const val RIME_DAQIAN_KEYS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347"
private const val RIME_DAQIAN_ZHUYIN =
    "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙"

private val rimeZhuyinKeyCodes = buildMap {
    RIME_DAQIAN_ZHUYIN.forEachIndexed { index, symbol ->
        put(symbol, RIME_DAQIAN_KEYS[index].code)
    }
}

internal fun rimeKeyCodeForZhuyin(symbol: String): Int? =
    symbol.singleOrNull()?.let(rimeZhuyinKeyCodes::get)

internal fun rimeKeyCodeForAscii(character: String): Int? =
    character.singleOrNull()?.code

internal fun parseRimeComposition(encoded: String): RimeCompositionState {
    val lines = encoded.split('\n')
    return RimeCompositionState(
        preedit = lines.firstOrNull().orEmpty(),
        candidates = lines.drop(1).filter(String::isNotBlank)
    )
}
