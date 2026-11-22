package com.coinrangersdebthero.aikeyboard

/**
 * One page of the inline symbol / emoji layer.
 *
 * [isEmoji] pages send their items verbatim; symbol pages are additionally
 * converted by [applySymbolWidth] so the user can toggle full-width output.
 */
internal data class SymbolPage(
    val title: String,
    val items: List<String>,
    val isEmoji: Boolean = false
)

internal fun SymbolPage.displayTitle(language: KeyboardUiLanguage): String =
    if (language == KeyboardUiLanguage.CHINESE) title else when (title) {
        "數字" -> "Numbers"
        "特殊" -> "Symbols"
        "表情" -> "Emojis"
        "人物" -> "People"
        else -> title
    }

internal const val SYMBOLS_PAGE_INDEX = 0
internal const val ADVANCED_SYMBOLS_PAGE_INDEX = 1
internal const val EMOJI_PAGE_INDEX = 2
internal const val EMOJI_PEOPLE_PAGE_INDEX = 3

/**
 * Page 1/2 are symbols, page 3/4 are emoji.
 *
 * Emoji are split following the "笑臉與人物" overview: page 3 keeps smileys,
 * emotions, hearts and effects, page 4 keeps gestures, people and body parts.
 */
internal val SYMBOL_PAGES = listOf(
    SymbolPage(
        title = "數字",
        items = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8",
            "9", "0", "@", "#", "$", "&", "*", "_",
            "-", "+", "(", ")", "/", "=", "?", "!",
            ",", ".", ":", ";", "'", "\"", "<", ">",
            "，", "。", "、", "；", "：", "？", "！", "…",
            "「", "」", "『", "』", "《", "》", "【", "】"
        )
    ),
    SymbolPage(
        title = "特殊",
        items = listOf(
            "±", "×", "÷", "≠", "≤", "≥", "∞", "√",
            "∫", "∑", "Δ", "∂", "∆", "∇", "∈", "∉",
            "∩", "∪", "∵", "∴", "°", "′", "″", "℃",
            "℉", "‰", "€", "£", "¥", "₩", "¢", "₹",
            "₽", "※", "§", "¶", "†", "‡", "•", "·",
            "★", "☆", "♠", "♣", "♥", "♦", "♪", "♯",
            "~", "`", "|", "^", "=", "{", "}", "\\",
            "%", "π", "©", "®", "™", "✓", "[", "]"
        )
    ),
    SymbolPage(
        title = "表情",
        isEmoji = true,
        items = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
            "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
            "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪",
            "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "😐", "😑",
            "😶", "😏", "😒", "🙄", "😬", "😌", "😔", "😪",
            "😴", "😷", "🤒", "🤕", "😵", "🤯", "😎", "🤓",
            "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "💀",
            "💔", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤",
            "💕", "💞", "💓", "💗", "💖", "💘", "✨", "💯"
        )
    ),
    SymbolPage(
        title = "人物",
        isEmoji = true,
        items = listOf(
            "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙",
            "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️",
            "🖖", "👋", "🤝", "🙏", "✍️", "💅", "🤳", "💪",
            "🦵", "🦶", "👂", "👃", "👀", "👁️", "🧠", "🦷",
            "🦴", "👅", "👶", "🧒", "👦", "👧", "🧑", "👨",
            "👩", "🧓", "👴", "👵", "🙅", "🙆", "💁", "🙋",
            "🤦", "🤷", "👮", "🕵️", "👷", "🤴", "👸", "👳",
            "👲", "🧕", "💂", "🕴️", "🤵", "👰", "🤰", "🤱",
            "💃", "🕺", "👯", "🧙", "🧚", "🧜", "🧞", "🧟"
        )
    )
)

/**
 * Converts a symbol page item between half-width and full-width forms.
 *
 * Only ASCII punctuation, digits and letters change; symbols that have no
 * full-width counterpart (∞, ※, ★…) pass through unchanged. Chinese
 * typography uses `，` and `。`, so those are produced for `,` and `.`.
 */
internal fun applySymbolWidth(item: String, fullWidth: Boolean): String {
    if (!fullWidth) return item
    return buildString(item.length) {
        item.forEach { character ->
            append(
                when (character) {
                    ',' -> '，'
                    '.' -> '。'
                    '?' -> '？'
                    '!' -> '！'
                    ':' -> '：'
                    ';' -> '；'
                    '(' -> '（'
                    ')' -> '）'
                    '[' -> '［'
                    ']' -> '］'
                    '{' -> '｛'
                    '}' -> '｝'
                    '<' -> '＜'
                    '>' -> '＞'
                    '@' -> '＠'
                    '#' -> '＃'
                    '$' -> '＄'
                    '%' -> '％'
                    '&' -> '＆'
                    '*' -> '＊'
                    '+' -> '＋'
                    '-' -> '－'
                    '=' -> '＝'
                    '_' -> '＿'
                    '/' -> '／'
                    '\\' -> '＼'
                    '|' -> '｜'
                    '~' -> '～'
                    '`' -> '｀'
                    '^' -> '＾'
                    '\'' -> '＇'
                    '"' -> '＂'
                    in '0'..'9' -> (character - '0' + 0xFF10).toChar()
                    in 'a'..'z' -> (character - 'a' + 0xFF41).toChar()
                    in 'A'..'Z' -> (character - 'A' + 0xFF21).toChar()
                    else -> character
                }
            )
        }
    }
}
