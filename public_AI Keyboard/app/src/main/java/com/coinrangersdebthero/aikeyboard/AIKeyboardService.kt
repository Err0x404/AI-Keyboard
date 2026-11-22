package com.coinrangersdebthero.aikeyboard

import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.coinrangersdebthero.aikeyboard.ui.theme.AIKeyboardTheme
import com.coinrangersdebthero.aikeyboard.ai.AiEngine
import com.coinrangersdebthero.aikeyboard.ai.ModelInitializer
import com.example.ime.RimeJniBridge
import com.example.ime.parseRimeComposition
import com.example.ime.rimeKeyCodeForAscii
import com.example.ime.rimeKeyCodeForZhuyin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class KeyboardMode {
    ENGLISH,
    CHINESE,
    NUMERIC,
    SYMBOLS,
    EMOJI
}

internal enum class ChineseSchema {
    ZHUYIN,
    PINYIN
}

internal enum class KeyboardUiLanguage {
    CHINESE,
    ENGLISH;

    fun preferenceValue(): String = name.lowercase(java.util.Locale.ROOT)

    companion object {
        fun fromPreference(value: String?): KeyboardUiLanguage =
            if (value == CHINESE.preferenceValue()) CHINESE else ENGLISH
    }
}

internal fun keyboardInterfaceText(
    language: KeyboardUiLanguage,
    chinese: String,
    english: String
): String = if (language == KeyboardUiLanguage.ENGLISH) english else chinese

internal fun hasSelectedText(selectedText: CharSequence?): Boolean =
    !selectedText.isNullOrEmpty()

internal fun summarizeClipboardText(text: String, maxCharacters: Int = 72): String? {
    require(maxCharacters > 0) { "maxCharacters must be positive." }
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return null
    val codePointCount = normalized.codePointCount(0, normalized.length)
    if (codePointCount <= maxCharacters) return normalized
    val endIndex = normalized.offsetByCodePoints(0, maxCharacters - 1)
    return normalized.substring(0, endIndex) + "…"
}

internal fun updateClipboardHistory(
    history: List<String>,
    clipboardText: String,
    maxEntries: Int = 5
): List<String> {
    require(maxEntries > 0) { "maxEntries must be positive." }
    val text = clipboardText.trim()
    if (text.isEmpty()) return history.distinct().take(maxEntries)
    return (listOf(text) + history.filterNot { it == text })
        .distinct()
        .take(maxEntries)
}

internal fun shouldUseEnglishScenarioReply(
    isEnglishMode: Boolean,
    contextText: String,
    clipboardText: String?
): Boolean {
    if (isEnglishMode) return true

    val languageText = contextText.takeIf { it.isNotBlank() } ?: clipboardText.orEmpty()
    val hasLatinLetters = languageText.any { it in 'a'..'z' || it in 'A'..'Z' }
    val hasCjkCharacters = languageText.any { character ->
        character in '\u3400'..'\u9fff' ||
            character in '\uf900'..'\ufaff' ||
            character in '\u3040'..'\u30ff' ||
            character in '\uac00'..'\ud7af'
    }
    return hasLatinLetters && !hasCjkCharacters
}

internal fun modeSwitcherLabel(
    mode: KeyboardMode,
    language: KeyboardUiLanguage = KeyboardUiLanguage.CHINESE
): String = when (language) {
    KeyboardUiLanguage.CHINESE -> if (mode == KeyboardMode.CHINESE) "英" else "中"
    KeyboardUiLanguage.ENGLISH -> if (mode == KeyboardMode.CHINESE) "EN" else "中"
}

internal fun shouldShowChineseToolbarControls(mode: KeyboardMode): Boolean =
    mode == KeyboardMode.CHINESE

internal fun shouldHighlightChineseSchemaControl(isSimplifiedChinese: Boolean): Boolean =
    isSimplifiedChinese

internal fun chineseSchemaSwitcherLabel(
    schema: ChineseSchema,
    language: KeyboardUiLanguage
): String = when (language) {
    KeyboardUiLanguage.CHINESE ->
        if (schema == ChineseSchema.PINYIN) "注" else "拼"
    KeyboardUiLanguage.ENGLISH ->
        if (schema == ChineseSchema.PINYIN) "Zh" else "Py"
}

internal val ENGLISH_QWERTY_ROWS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m")
)

internal val DAQIAN_ZHUYIN_ROWS = listOf(
    listOf("ㄅ", "ㄉ", "ˇ", "ˋ", "ㄓ", "ˊ", "˙", "ㄚ", "ㄞ", "ㄢ", "ㄦ"),
    listOf("ㄆ", "ㄊ", "ㄍ", "ㄐ", "ㄔ", "ㄗ", "ㄧ", "ㄛ", "ㄟ", "ㄣ"),
    listOf("ㄇ", "ㄋ", "ㄎ", "ㄑ", "ㄕ", "ㄘ", "ㄨ", "ㄜ", "ㄠ", "ㄤ"),
    listOf("ㄈ", "ㄌ", "ㄏ", "ㄒ", "ㄖ", "ㄙ", "ㄩ", "ㄝ", "ㄡ", "ㄥ")
)

internal object KeyboardPreferenceKeys {
    const val FILE_NAME = "keyboard_settings"
    const val AI_SCENARIO_ENABLED = "ai_scenario_enabled"
    const val SELECTED_SCENARIO_OPTION_ID = "selected_scenario_option_id"
    const val CHINESE_SCHEMA = "chinese_schema"
    const val CHINESE_SIMPLIFIED = "chinese_simplified"
    const val UI_LANGUAGE = "keyboard_ui_language"
    const val UI_LANGUAGE_USER_SELECTED = "keyboard_ui_language_user_selected"
}

internal fun resolveKeyboardMode(inputType: Int, hintLocaleTags: String?): KeyboardMode {
    val inputClass = inputType and EditorInfo.TYPE_MASK_CLASS
    if (
        inputClass == EditorInfo.TYPE_CLASS_NUMBER ||
        inputClass == EditorInfo.TYPE_CLASS_PHONE
    ) {
        return KeyboardMode.NUMERIC
    }

    val hasChineseHint = hintLocaleTags
        ?.split(",")
        ?.any { it.substringBefore('-').equals("zh", ignoreCase = true) }
        ?: false
    return if (hasChineseHint) KeyboardMode.CHINESE else KeyboardMode.ENGLISH
}

internal fun isPasswordInputType(inputType: Int): Boolean {
    val inputClass = inputType and EditorInfo.TYPE_MASK_CLASS
    val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
    return when (inputClass) {
        EditorInfo.TYPE_CLASS_TEXT -> variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
        EditorInfo.TYPE_CLASS_NUMBER ->
            variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

internal fun isUrlOrEmailInputType(inputType: Int): Boolean {
    if ((inputType and EditorInfo.TYPE_MASK_CLASS) != EditorInfo.TYPE_CLASS_TEXT) {
        return false
    }
    val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
    return variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
        variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
}

internal val zhuyinDictionary: HashMap<String, List<String>> = hashMapOf(
    "ㄇㄚ" to listOf("媽", "馬", "嗎", "麻"),
    "ㄇㄚˇ" to listOf("馬"),
    "ㄇㄚˋ" to listOf("罵"),
    "ㄇㄚ˙" to listOf("嗎"),
    "ㄅㄚ" to listOf("八", "吧", "爸", "把"),
    "ㄅㄚˇ" to listOf("把"),
    "ㄆㄚ" to listOf("趴", "啪"),
    "ㄆㄚˋ" to listOf("怕"),
    "ㄈㄚ" to listOf("發", "法"),
    "ㄉㄚ" to listOf("大", "答"),
    "ㄉㄚˋ" to listOf("大"),
    "ㄊㄚ" to listOf("他", "她", "它"),
    "ㄋㄚ" to listOf("那", "哪"),
    "ㄌㄚ" to listOf("啦", "拉"),
    "ㄍㄚ" to listOf("嘎"),
    "ㄎㄚ" to listOf("卡"),
    "ㄏㄚ" to listOf("哈"),
    "ㄓㄚ" to listOf("扎", "渣"),
    "ㄔㄚ" to listOf("查", "茶"),
    "ㄕㄚ" to listOf("沙", "傻"),
    "ㄖㄨˊ" to listOf("如"),
    "ㄖㄢˊ" to listOf("然"),
    "ㄗㄚ" to listOf("雜"),
    "ㄘㄚ" to listOf("擦"),
    "ㄙㄚ" to listOf("撒"),
    "ㄨㄛ" to listOf("我", "握", "窩"),
    "ㄨㄛˇ" to listOf("我"),
    "ㄨㄛˋ" to listOf("握", "臥"),
    "ㄋㄧ" to listOf("你", "妳"),
    "ㄋㄧˇ" to listOf("你", "妳"),
    "ㄋㄧˋ" to listOf("逆", "膩"),
    "ㄕ" to listOf("是", "時", "事"),
    "ㄕˋ" to listOf("是", "事", "市"),
    "ㄕˊ" to listOf("時", "十", "實"),
    "ㄏㄠ" to listOf("好", "號"),
    "ㄏㄠˇ" to listOf("好"),
    "ㄏㄠˋ" to listOf("號", "浩"),
    "ㄅㄨ" to listOf("不", "布"),
    "ㄅㄨˋ" to listOf("不", "部"),
    "ㄅㄨˊ" to listOf("不"),
    "ㄧㄡ" to listOf("有", "又"),
    "ㄧㄡˇ" to listOf("有", "友"),
    "ㄖㄣ" to listOf("人", "任"),
    "ㄖㄣˊ" to listOf("人"),
    "ㄓㄜ" to listOf("這", "著"),
    "ㄓㄜˋ" to listOf("這"),
    "ㄗㄞ" to listOf("在", "再"),
    "ㄗㄞˋ" to listOf("在", "再"),
    "ㄉㄜ" to listOf("的", "得"),
    "ㄉㄜ˙" to listOf("的"),
    "ㄌㄜ" to listOf("了", "樂"),
    "ㄌㄜ˙" to listOf("了"),
    "ㄇㄣ" to listOf("們", "門"),
    "ㄇㄣ˙" to listOf("們"),
    "ㄍㄜ" to listOf("個", "歌"),
    "ㄍㄜ˙" to listOf("個"),
    "ㄌㄞ" to listOf("來"),
    "ㄌㄞˊ" to listOf("來"),
    "ㄑㄩ" to listOf("去", "取"),
    "ㄑㄩˋ" to listOf("去"),
    "ㄎㄜ" to listOf("可", "課"),
    "ㄎㄜˇ" to listOf("可"),
    "ㄧ" to listOf("一", "以"),
    "ㄧˇ" to listOf("以"),
    "ㄨㄣ" to listOf("問", "文"),
    "ㄨㄣˋ" to listOf("問"),
    "ㄕㄣ" to listOf("什", "身"),
    "ㄕㄣˊ" to listOf("什"),
    "ㄇㄜ" to listOf("麼"),
    "ㄇㄜ˙" to listOf("麼"),
    "ㄕㄨㄛ" to listOf("說"),
    "ㄏㄨㄚ" to listOf("話", "花"),
    "ㄏㄨㄚˋ" to listOf("話", "畫"),
    "ㄐㄧㄣ" to listOf("今", "金"),
    "ㄊㄧㄢ" to listOf("天"),
    "ㄇㄧㄥ" to listOf("明", "名"),
    "ㄇㄧㄥˊ" to listOf("明", "名"),
    "ㄋㄧㄢ" to listOf("年"),
    "ㄋㄧㄢˊ" to listOf("年"),
    "ㄒㄧㄝ" to listOf("謝", "些"),
    "ㄒㄧㄝˋ" to listOf("謝", "謝謝", "些"),
    "ㄑㄧㄥ" to listOf("請", "清"),
    "ㄑㄧㄥˇ" to listOf("請"),
    "ㄗㄠ" to listOf("早"),
    "ㄗㄠˇ" to listOf("早"),
    "ㄢ" to listOf("安"),
    "ㄓㄨㄥ" to listOf("中", "鐘"),
    "ㄍㄨㄛ" to listOf("國", "過"),
    "ㄍㄨㄛˊ" to listOf("國"),
    "ㄒㄩㄝ" to listOf("學"),
    "ㄒㄩㄝˊ" to listOf("學"),
    "ㄕㄥ" to listOf("生", "聲"),
    "ㄏㄨㄟ" to listOf("會", "回"),
    "ㄏㄨㄟˋ" to listOf("會"),
    "ㄑㄧㄢ" to listOf("前", "千"),
    "ㄑㄧㄢˊ" to listOf("前", "錢"),
    "ㄏㄡ" to listOf("後", "候"),
    "ㄏㄡˋ" to listOf("後"),
    "ㄉㄨㄛ" to listOf("多"),
    "ㄕㄠ" to listOf("少", "燒"),
    "ㄕㄠˇ" to listOf("少"),
    "ㄔ" to listOf("吃", "持"),
    "ㄈㄢ" to listOf("飯", "番"),
    "ㄈㄢˋ" to listOf("飯"),
    "ㄏㄜ" to listOf("喝", "和"),
    "ㄕㄨㄟˇ" to listOf("水"),
    "ㄉㄧㄢ" to listOf("電", "店"),
    "ㄉㄧㄢˋ" to listOf("電", "店"),
    "ㄋㄠ" to listOf("腦", "鬧"),
    "ㄋㄠˇ" to listOf("腦"),
    "ㄕㄡ" to listOf("手", "收"),
    "ㄕㄡˇ" to listOf("手"),
    "ㄐㄧ" to listOf("機", "雞", "幾"),
    "ㄐㄧㄚ" to listOf("家", "加"),
    "ㄕㄤˋ" to listOf("上"),
    "ㄒㄧㄚˋ" to listOf("下"),
    "ㄒㄧㄠˇ" to listOf("小"),
    "ㄉㄧˋ" to listOf("地", "第"),
    "ㄧㄠˋ" to listOf("要", "藥"),
    "ㄊㄧㄥ" to listOf("聽", "廳"),
    "ㄍㄠ" to listOf("高", "糕"),
    "ㄒㄧㄣ" to listOf("心", "新"),
    "ㄇㄞˇ" to listOf("買"),
    "ㄇㄞˋ" to listOf("賣"),
    "ㄒㄧㄤˇ" to listOf("想", "響"),
    "ㄓㄨㄣˇ" to listOf("準"),
    "ㄇㄟˇ" to listOf("美", "每"),
    "ㄌㄧㄠˇ" to listOf("了", "瞭"),
    "ㄉㄨㄟˋ" to listOf("對"),
    "ㄘㄨㄛˋ" to listOf("錯"),
    "ㄎㄨㄞˋ" to listOf("快"),
    "ㄌㄜˋ" to listOf("樂"),
    "ㄨㄢˊ" to listOf("玩", "完"),
    "ㄎㄞ" to listOf("開"),
    "ㄍㄨㄟˋ" to listOf("貴", "桂"),
    "ㄘㄞˊ" to listOf("才", "財"),
    "ㄒㄧㄤ" to listOf("香", "相"),
    "ㄑㄧˊ" to listOf("其", "奇"),
    "ㄌㄧˇ" to listOf("裡", "理"),
    "ㄑㄧˋ" to listOf("氣", "器"),
    "ㄐㄧㄢˋ" to listOf("見", "件"),
    "ㄇㄧㄢˋ" to listOf("面", "麵"),
    "ㄊㄡˊ" to listOf("頭"),
    "ㄐㄧㄣˋ" to listOf("進", "近"),
    "ㄐㄧㄡˋ" to listOf("就", "舊"),
    "ㄍㄟˇ" to listOf("給"),
    "ㄉㄠˋ" to listOf("到", "道"),
    "ㄋㄥˊ" to listOf("能"),
    "ㄔㄤˊ" to listOf("常", "長"),
    "ㄆㄥˊ" to listOf("朋"),
    "ㄧㄢˇ" to listOf("眼"),
    "ㄧㄣˋ" to listOf("印"),
    "ㄍㄨㄢ" to listOf("關", "觀"),
    "ㄒㄧㄢ" to listOf("先", "仙"),
    "ㄒㄧㄢˋ" to listOf("現", "線"),
    "ㄏㄨㄢ" to listOf("歡"),
    "ㄅㄧㄢ" to listOf("邊", "編"),
    "ㄌㄧㄢˋ" to listOf("練", "戀"),
    "ㄐㄧㄠˋ" to listOf("叫", "教"),
    "ㄐㄧㄝˇ" to listOf("姐", "解"),
    "ㄗㄨㄛˋ" to listOf("做", "坐"),
    "ㄗㄨㄛˇ" to listOf("左"),
    "ㄒㄧㄥˊ" to listOf("行"),
    "ㄐㄧㄥ" to listOf("經", "京"),
    "ㄌㄧㄥˊ" to listOf("零", "靈"),
    "ㄍㄨㄥ" to listOf("工", "公"),
    "ㄈㄤ" to listOf("方", "房"),
    "ㄉㄨㄥ" to listOf("東", "冬"),
    "ㄕㄨ" to listOf("書", "叔"),
    "ㄩˇ" to listOf("雨", "語"),
    "ㄧㄥ" to listOf("英", "應"),
    "ㄓㄥˋ" to listOf("正", "證")
)

private val zhuyinPhraseDictionary = linkedMapOf(
    "ㄋㄏ" to listOf("你好", "您好"),
    "ㄋㄏㄇ" to listOf("你好嗎？", "你還好嗎？"),
    "ㄋㄧㄏㄠㄇㄚ" to listOf("你好嗎？", "你好嗎"),
    "ㄋㄧˇㄏㄠˇㄇㄚ˙" to listOf("你好嗎？", "你好嗎"),
    "ㄨㄛㄏㄣㄏㄠ" to listOf("我很好"),
    "ㄒㄧㄝㄒㄧㄝ" to listOf("謝謝")
)

private data class SmartPhraseEntry(
    val zhuyinSyllables: List<String>,
    val pinyinSyllables: List<String>,
    val traditionalCandidates: List<String>,
    val simplifiedCandidates: List<String> = traditionalCandidates
)

private val smartPhraseEntries = listOf(
    SmartPhraseEntry(
        listOf("ㄅㄧ", "ㄈㄤ", "ㄕㄨㄛ"),
        listOf("bi", "fang", "shuo"),
        listOf("比方說", "比如說", "比方"),
        listOf("比方说", "比如说", "比方")
    ),
    SmartPhraseEntry(
        listOf("ㄋㄧ", "ㄏㄠ", "ㄇㄚ"),
        listOf("ni", "hao", "ma"),
        listOf("你好嗎", "你好嗎？", "你好"),
        listOf("你好吗", "你好吗？", "你好")
    ),
    SmartPhraseEntry(
        listOf("ㄋㄧ", "ㄏㄠ"),
        listOf("ni", "hao"),
        listOf("你好", "您好")
    ),
    SmartPhraseEntry(listOf("ㄨㄛ", "ㄏㄣ", "ㄏㄠ"), listOf("wo", "hen", "hao"), listOf("我很好")),
    SmartPhraseEntry(
        listOf("ㄒㄧㄝ", "ㄒㄧㄝ"),
        listOf("xie", "xie"),
        listOf("謝謝", "多謝"),
        listOf("谢谢", "多谢")
    ),
    SmartPhraseEntry(
        listOf("ㄅㄨ", "ㄎㄜ", "ㄑㄧ"),
        listOf("bu", "ke", "qi"),
        listOf("不客氣"),
        listOf("不客气")
    ),
    SmartPhraseEntry(listOf("ㄗㄠ", "ㄢ"), listOf("zao", "an"), listOf("早安")),
    SmartPhraseEntry(
        listOf("ㄗㄞ", "ㄐㄧㄢ"),
        listOf("zai", "jian"),
        listOf("再見"),
        listOf("再见")
    ),
    SmartPhraseEntry(
        listOf("ㄇㄟ", "ㄍㄨㄢ", "ㄒㄧ"),
        listOf("mei", "guan", "xi"),
        listOf("沒關係"),
        listOf("没关系")
    ),
    SmartPhraseEntry(
        listOf("ㄨㄟ", "ㄕㄣ", "ㄇㄜ"),
        listOf("wei", "shen", "me"),
        listOf("為什麼"),
        listOf("为什么")
    ),
    SmartPhraseEntry(
        listOf("ㄗㄣ", "ㄇㄜ", "ㄅㄢ"),
        listOf("zen", "me", "ban"),
        listOf("怎麼辦"),
        listOf("怎么办")
    ),
    SmartPhraseEntry(
        listOf("ㄎㄜ", "ㄧ", "ㄇㄚ"),
        listOf("ke", "yi", "ma"),
        listOf("可以嗎"),
        listOf("可以吗")
    ),
    SmartPhraseEntry(listOf("ㄨㄛ", "ㄒㄧㄤ", "ㄧㄠ"), listOf("wo", "xiang", "yao"), listOf("我想要")),
    SmartPhraseEntry(
        listOf("ㄨㄛ", "ㄓ", "ㄉㄠ"),
        listOf("wo", "zhi", "dao"),
        listOf("我知道")
    ),
    SmartPhraseEntry(
        listOf("ㄉㄥ", "ㄧ", "ㄒㄧㄚ"),
        listOf("deng", "yi", "xia"),
        listOf("等一下")
    ),
    SmartPhraseEntry(listOf("ㄐㄧㄣ", "ㄊㄧㄢ"), listOf("jin", "tian"), listOf("今天")),
    SmartPhraseEntry(listOf("ㄇㄧㄥ", "ㄊㄧㄢ"), listOf("ming", "tian"), listOf("明天")),
    SmartPhraseEntry(listOf("ㄨㄢ", "ㄢ"), listOf("wan", "an"), listOf("晚安")),
    SmartPhraseEntry(
        listOf("ㄏㄠ", "ㄐㄧㄡ", "ㄅㄨ", "ㄐㄧㄢ"),
        listOf("hao", "jiu", "bu", "jian"),
        listOf("好久不見"),
        listOf("好久不见")
    ),
    SmartPhraseEntry(
        listOf("ㄒㄧㄣ", "ㄎㄨ", "ㄌㄜ"),
        listOf("xin", "ku", "le"),
        listOf("辛苦了")
    ),
    SmartPhraseEntry(
        listOf("ㄒㄩ", "ㄧㄠ", "ㄅㄤ", "ㄇㄤ", "ㄇㄚ"),
        listOf("xu", "yao", "bang", "mang", "ma"),
        listOf("需要幫忙嗎"),
        listOf("需要帮忙吗")
    )
)

internal fun resolvePhoneticPhraseCandidates(
    input: String,
    schema: ChineseSchema,
    simplified: Boolean = false
): List<String> {
    val query = normalizePhoneticInput(input, schema)
    if (query.isEmpty() || (schema == ChineseSchema.PINYIN && query.length < 2)) {
        return emptyList()
    }
    return smartPhraseEntries
        .asSequence()
        .filter { entry ->
            val fullSpelling = entry.spelling(schema)
            val initials = entry.initials(schema)
            fullSpelling.startsWith(query) || initials.startsWith(query)
        }
        .flatMap { entry ->
            (if (simplified) entry.simplifiedCandidates else entry.traditionalCandidates)
                .asSequence()
        }
        .distinct()
        .toList()
}

internal fun isPhoneticInitialAbbreviation(input: String, schema: ChineseSchema): Boolean {
    val query = normalizePhoneticInput(input, schema)
    if (query.isEmpty() || (schema == ChineseSchema.PINYIN && query.length < 2)) return false
    return smartPhraseEntries.any { it.initials(schema).startsWith(query) }
}

private fun SmartPhraseEntry.spelling(schema: ChineseSchema): String =
    when (schema) {
        ChineseSchema.ZHUYIN -> zhuyinSyllables.joinToString("")
        ChineseSchema.PINYIN -> pinyinSyllables.joinToString("")
    }

private fun SmartPhraseEntry.initials(schema: ChineseSchema): String =
    when (schema) {
        ChineseSchema.ZHUYIN -> zhuyinSyllables.joinToString("") { it.first().toString() }
        ChineseSchema.PINYIN -> pinyinSyllables.joinToString("") { it.first().toString() }
    }

private fun normalizePhoneticInput(input: String, schema: ChineseSchema): String =
    when (schema) {
        ChineseSchema.ZHUYIN -> input.filterNot { it.isWhitespace() || it in ZHUYIN_TONE_MARKS }
        ChineseSchema.PINYIN -> input.lowercase(java.util.Locale.ROOT).filter { it in 'a'..'z' }
    }

private const val ZHUYIN_TONE_MARKS = "ˉˊˇˋ˙"

internal fun resolveZhuyinCandidates(
    input: String,
    simplified: Boolean = false
): List<String> {
    if (input.isEmpty()) return emptyList()
    val exact = zhuyinDictionary[input].orEmpty()
    val phraseExact = zhuyinPhraseDictionary[input].orEmpty()
    val phrasePrefixes = if (input in zhuyinDictionary) {
        emptyList()
    } else {
        zhuyinPhraseDictionary.entries
            .asSequence()
            .filter { it.key != input && it.key.startsWith(input) }
            .flatMap { it.value.asSequence() }
            .toList()
    }
    val smartPhrases = if (input in zhuyinDictionary) {
        emptyList()
    } else {
        resolvePhoneticPhraseCandidates(input, ChineseSchema.ZHUYIN, simplified)
    }
    val joinedSyllables = resolveZhuyinSyllableSequence(input)
    val matches = zhuyinDictionary.entries
        .asSequence()
        .filter { it.key != input && it.key.startsWith(input) }
        .sortedBy { it.key }
        .flatMap { it.value.asSequence() }
        .distinct()
        .toList()
    return (exact + phraseExact + phrasePrefixes + smartPhrases + joinedSyllables + matches)
        .distinct()
}

private fun resolveZhuyinSyllableSequence(input: String): List<String> {
    val syllableEntries = zhuyinDictionary.entries
        .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.key.length }
            .thenBy { it.key })
    val syllablePaths = mutableListOf<List<List<String>>>()
    var visitedPaths = 0

    fun findSyllables(offset: Int, path: List<List<String>>) {
        if (visitedPaths >= MAX_ZHUYIN_PARSE_PATHS || syllablePaths.size >= MAX_ZHUYIN_PARSE_PATHS) {
            return
        }
        if (offset == input.length) {
            if (path.size > 1) syllablePaths += path
            return
        }
        visitedPaths += 1
        syllableEntries.forEach { (spelling, candidates) ->
            if (input.startsWith(spelling, offset)) {
                findSyllables(offset + spelling.length, path + listOf(candidates))
            }
        }
    }

    findSyllables(0, emptyList())
    val results = linkedSetOf<String>()

    fun combine(path: List<List<String>>, index: Int, prefix: String) {
        if (results.size >= MAX_ZHUYIN_SEQUENCE_CANDIDATES) return
        if (index == path.size) {
            results += prefix
            return
        }
        path[index].take(3).forEach { syllable ->
            combine(path, index + 1, prefix + syllable)
        }
    }

    syllablePaths.forEach { path ->
        combine(path, 0, "")
    }
    return results.toList()
}

private const val MAX_ZHUYIN_PARSE_PATHS = 128
private const val MAX_ZHUYIN_SEQUENCE_CANDIDATES = 12

private val englishStaticDictionary = listOf(
    "hello", "help", "helpful", "helicopter", "helmet", "hero", "her", "here",
    "heart", "heavy", "happy", "happen", "have", "home", "hope", "how", "human",
    "house", "history", "high", "hold", "hour", "honest", "holiday", "honey"
)

internal fun mergeCandidateSources(
    localWords: List<String>,
    aiWord: String?,
    staticWords: List<String>,
    isEnglish: Boolean,
    autoSpace: Boolean = true,
    prioritizeAi: Boolean = false,
    aiWords: List<String> = emptyList()
): List<Pair<String, String>> {
    val localCandidates = localWords.map { it to if (isEnglish && autoSpace) "$it " else it }
    val aiCandidates = (listOfNotNull(aiWord) + aiWords)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map {
            "✨ $it" to if (isEnglish && autoSpace) "$it " else it
        }
    val staticCandidates = staticWords.map {
        it to if (isEnglish && autoSpace) "$it " else it
    }
    val orderedCandidates = if (prioritizeAi) {
        aiCandidates + localCandidates + staticCandidates
    } else {
        localCandidates + aiCandidates + staticCandidates
    }
    return orderedCandidates
        .distinctBy { it.second }
}

internal fun completeAiCandidate(prefix: String, suggestion: String): String {
    val candidate = suggestion.trim()
    return if (candidate.startsWith(prefix, ignoreCase = true)) candidate else prefix + candidate
}

private data class KeyboardCandidate(
    val label: String,
    val commitText: String,
    val saveToLocalDict: Boolean = true
)

class AIKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val composeLifecycleRegistry = LifecycleRegistry(this)
    private val composeViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = composeLifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = composeViewModelStore

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var keyboardRoot: LinearLayout? = null
    private var candidateRow: ComposeView? = null
    private var scenarioButton: TextView? = null
    private var clipboardButton: TextView? = null
    private var clipboardPreviewView: LinearLayout? = null
    private var clipboardScrollView: ScrollView? = null
    private var clipboardContentContainer: LinearLayout? = null
    private var clipboardHistoryButton: TextView? = null
    private var keyboardMode = KeyboardMode.ENGLISH
    private var keyboardUiLanguage = KeyboardUiLanguage.CHINESE
    private var chineseSchema = ChineseSchema.ZHUYIN
    private var isSimplifiedChinese = false
    private var isShifted = false
    private var isPasswordField = false
    private var autoSpaceEnabled = true
    private var currentInputText = ""
    private var isRimeReady = false
    private var isRimeCompositionActive = false
    private var candidates by mutableStateOf(emptyList<KeyboardCandidate>())
    private var isPredicting by mutableStateOf(false)
    private var aiStatusMessage by mutableStateOf<String?>(null)
    private var predictionJob: Job? = null
    private var scenarioJob: Job? = null
    private var clipboardReadJob: Job? = null
    private var cachedClipboardText: String? = null
    private var clipboardHistory = emptyList<String>()
    private var isClipboardHistoryExpanded = false
    private var isClipboardPanelVisible = false
    private var isClipboardClearedByUser = false
    private var isHistoricalClipboardSelected = false
    private var isScenarioClipboardContextActive = false
    private var scenarioClipboardContextToken = 0
    private var clipboardListenerRegistered = false
    private val clipboardManager by lazy {
        getSystemService(ClipboardManager::class.java)
    }
    private val clipboardChangedListener =
        ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }
    private var scenarioPopup: PopupWindow? = null
    private var previousKeyboardMode = KeyboardMode.ENGLISH
    private var symbolPage = SYMBOLS_PAGE_INDEX
    private var isFullWidthSymbols = true
    private val deleteRepeatHandler = Handler(Looper.getMainLooper())
    private var deleteRepeatRunnable: Runnable? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var composeRecomposer: Recomposer
    private var recomposerJob: Job? = null
    private val aiScenarioPreferences by lazy {
        getSharedPreferences(KeyboardPreferenceKeys.FILE_NAME, MODE_PRIVATE)
    }
    private val aiScenarioPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            when (key) {
                KeyboardPreferenceKeys.UI_LANGUAGE -> {
                    val language = KeyboardUiLanguage.fromPreference(
                        preferences.getString(KeyboardPreferenceKeys.UI_LANGUAGE, null)
                    )
                    applyKeyboardUiLanguage(language)
                }
                KeyboardPreferenceKeys.AI_SCENARIO_ENABLED -> {
                    if (!preferences.getBoolean(KeyboardPreferenceKeys.AI_SCENARIO_ENABLED, true)) {
                        predictionJob?.cancel()
                        predictionJob = null
                        scenarioJob?.cancel()
                        scenarioJob = null
                        scenarioPopup?.dismiss()
                        isPredicting = false
                        aiStatusMessage = null
                        candidates = candidates.filter {
                            it.saveToLocalDict && !it.label.startsWith(AI_CANDIDATE_PREFIX)
                        }
                    }
                    renderCandidateBar()
                }
            }
        }
    private val localWordPreferences by lazy {
        getSharedPreferences(LOCAL_WORD_PREFERENCES, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        keyboardUiLanguage = KeyboardUiLanguage.fromPreference(
            aiScenarioPreferences.getString(KeyboardPreferenceKeys.UI_LANGUAGE, null)
        )
        chineseSchema = if (
            aiScenarioPreferences.getString(KeyboardPreferenceKeys.CHINESE_SCHEMA, null) ==
            "pinyin"
        ) {
            ChineseSchema.PINYIN
        } else {
            ChineseSchema.ZHUYIN
        }
        isSimplifiedChinese = aiScenarioPreferences.getBoolean(
            KeyboardPreferenceKeys.CHINESE_SIMPLIFIED,
            false
        )
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        composeLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val composeContext = AndroidUiDispatcher.CurrentThread
        composeRecomposer = Recomposer(composeContext)
        recomposerJob = CoroutineScope(composeContext).launch {
            composeRecomposer.runRecomposeAndApplyChanges()
        }
        AiEngine.initialize(applicationContext)
        if (ModelInitializer.isModelReady(this)) {
            // Warm the model up in the background so the first scenario reply does
            // not pay the whole load cost while the user waits.
            serviceScope.launch(Dispatchers.IO) {
                try {
                    AiEngine.loadModel()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Model warm-up failed; it will retry on first request.", error)
                }
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            val initialized = try {
                RimeJniBridge.initRime(applicationContext)
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Rime initialization failed; using the built-in Zhuyin dictionary.",
                    error
                )
                false
            } catch (error: LinkageError) {
                Log.e(
                    TAG,
                    "Rime native library is unavailable; using the built-in Zhuyin dictionary.",
                    error
                )
                false
            }
            withContext(Dispatchers.Main.immediate) {
                isRimeReady = initialized
                if (initialized) applyChineseRimeSettings()
            }
        }
        aiScenarioPreferences.registerOnSharedPreferenceChangeListener(
            aiScenarioPreferenceListener
        )
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        scenarioJob?.cancel()
        scenarioPopup?.dismiss()
        isScenarioClipboardContextActive = false
        scenarioClipboardContextToken += 1
        isClipboardHistoryExpanded = false
        isClipboardClearedByUser = false
        isHistoricalClipboardSelected = false
        isClipboardPanelVisible = false
        clipboardReadJob?.cancel()
        cachedClipboardText = null
        updateClipboardPreview()
        if (attribute?.inputType?.let(::isPasswordInputType) == true) {
            unregisterClipboardListener()
            updateClipboardPreview()
            return
        }
        refreshClipboardText()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            setBackgroundColor(Color.rgb(242, 244, 248))
        }
        keyboardRoot = root
        renderKeyboard(root)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        clearComposingInput()
        isShifted = false
        isClipboardPanelVisible = false
        isClipboardHistoryExpanded = false
        val inputType = info?.inputType ?: 0
        isPasswordField = isPasswordInputType(inputType)
        autoSpaceEnabled = !isUrlOrEmailInputType(inputType)
        val isNumericField = (inputType and EditorInfo.TYPE_MASK_CLASS) in setOf(
            EditorInfo.TYPE_CLASS_NUMBER,
            EditorInfo.TYPE_CLASS_PHONE
        )
        val selectedUiLanguage = aiScenarioPreferences.getString(
            KeyboardPreferenceKeys.UI_LANGUAGE,
            null
        )
        keyboardMode = when {
            isPasswordField && (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER ->
                KeyboardMode.NUMERIC
            isPasswordField -> KeyboardMode.ENGLISH
            isNumericField -> KeyboardMode.NUMERIC
            aiScenarioPreferences.getBoolean(
                KeyboardPreferenceKeys.UI_LANGUAGE_USER_SELECTED,
                false
            ) && selectedUiLanguage != null ->
                if (
                    KeyboardUiLanguage.fromPreference(selectedUiLanguage) ==
                    KeyboardUiLanguage.CHINESE
                ) {
                    KeyboardMode.CHINESE
                } else {
                    KeyboardMode.ENGLISH
                }
            else -> resolveKeyboardMode(inputType, info?.hintLocales?.toLanguageTags())
        }
        if (!isPasswordField) syncKeyboardUiLanguageWithMode()
        if (isPasswordField) {
            unregisterClipboardListener()
            clipboardReadJob?.cancel()
            cachedClipboardText = null
            updateClipboardPreview()
        } else {
            registerClipboardListener()
            refreshClipboardText()
        }
        keyboardRoot?.let(::renderKeyboard)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopDeleteRepeat()
        unregisterClipboardListener()
        clipboardReadJob?.cancel()
        isScenarioClipboardContextActive = false
        scenarioClipboardContextToken += 1
        isClipboardPanelVisible = false
        isClipboardHistoryExpanded = false
        updateClipboardPreview()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        stopDeleteRepeat()
        predictionJob?.cancel()
        scenarioJob?.cancel()
        unregisterClipboardListener()
        clipboardReadJob?.cancel()
        scenarioPopup?.dismiss()
        aiScenarioPreferences.unregisterOnSharedPreferenceChangeListener(
            aiScenarioPreferenceListener
        )
        serviceScope.cancel()
        recomposerJob?.cancel()
        if (::composeRecomposer.isInitialized) composeRecomposer.cancel()
        candidateRow?.disposeComposition()
        composeViewModelStore.clear()
        keyboardRoot = null
        candidateRow = null
        composeLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onFinishInput() {
        scenarioJob?.cancel()
        clipboardReadJob?.cancel()
        cachedClipboardText = null
        isScenarioClipboardContextActive = false
        scenarioClipboardContextToken += 1
        isClipboardPanelVisible = false
        isClipboardHistoryExpanded = false
        updateClipboardPreview()
        scenarioPopup?.dismiss()
        super.onFinishInput()
    }

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered || isPasswordField) return
        clipboardManager?.addPrimaryClipChangedListener(clipboardChangedListener)
        clipboardListenerRegistered = clipboardManager != null
    }

    private fun unregisterClipboardListener() {
        if (!clipboardListenerRegistered) return
        clipboardManager?.removePrimaryClipChangedListener(clipboardChangedListener)
        clipboardListenerRegistered = false
    }

    private fun refreshClipboardText() {
        if (isPasswordField || isClipboardClearedByUser || isHistoricalClipboardSelected) {
            if (isPasswordField || isClipboardClearedByUser) cachedClipboardText = null
            updateClipboardPreview()
            return
        }
        clipboardReadJob?.cancel()
        clipboardReadJob = serviceScope.launch(Dispatchers.IO) {
            val text = try {
                clipboardManager
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(this@AIKeyboardService)
                    ?.toString()
                    ?.trim()
                    ?.take(MAX_CLIPBOARD_CHARS)
                    ?.takeIf { it.isNotBlank() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not read clipboard for scenario context.", error)
                null
            }
            withContext(Dispatchers.Main.immediate) {
                cachedClipboardText = text
                if (text != null) {
                    clipboardHistory = updateClipboardHistory(clipboardHistory, text)
                }
                updateClipboardPreview()
            }
        }
    }

    private fun onClipboardChanged() {
        serviceScope.launch {
            isClipboardClearedByUser = false
            isHistoricalClipboardSelected = false
            refreshClipboardText()
        }
    }

    private fun updateClipboardPreview() {
        val preview = clipboardPreviewView ?: return
        if (isPasswordField || !isClipboardPanelVisible) {
            preview.visibility = View.GONE
            return
        }
        clipboardHistoryButton?.text = keyboardInterfaceText(
            keyboardUiLanguage,
            if (isClipboardHistoryExpanded) "返回內容" else "選擇",
            if (isClipboardHistoryExpanded) "Current text" else "Select"
        )
        val content = clipboardContentContainer ?: return
        content.removeAllViews()
        if (isClipboardHistoryExpanded) {
            if (clipboardHistory.isEmpty()) {
                content.addView(
                    createClipboardTextView(
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "沒有剪貼簿紀錄",
                            "No clipboard history"
                        )
                    )
                )
            } else {
                clipboardHistory.forEach { item ->
                    content.addView(createClipboardHistoryItem(item))
                }
            }
        } else {
            content.addView(
                createClipboardTextView(
                    cachedClipboardText ?: keyboardInterfaceText(
                        keyboardUiLanguage,
                        "剪貼簿目前沒有文字",
                        "No text in the clipboard"
                    )
                )
            )
        }
        clipboardScrollView?.scrollTo(0, 0)
        preview.visibility = View.VISIBLE
    }

    private fun toggleClipboardPanel() {
        if (isPasswordField) return
        isScenarioClipboardContextActive = false
        scenarioClipboardContextToken += 1
        isClipboardHistoryExpanded = false
        isClipboardPanelVisible = !isClipboardPanelVisible
        updateClipboardPreview()
        if (isClipboardPanelVisible) refreshClipboardText()
    }

    private fun openScenarioCategories(anchor: View) {
        if (!isPasswordField) {
            scenarioClipboardContextToken += 1
            isScenarioClipboardContextActive = true
            isClipboardPanelVisible = true
            isClipboardHistoryExpanded = false
            updateClipboardPreview()
            refreshClipboardText()
        }
        showScenarioCategories(anchor)
    }

    private fun endScenarioClipboardContext(token: Int) {
        if (token != scenarioClipboardContextToken || !isScenarioClipboardContextActive) return
        isScenarioClipboardContextActive = false
        isClipboardPanelVisible = false
        isClipboardHistoryExpanded = false
        updateClipboardPreview()
    }

    private fun clearClipboardHistory() {
        scenarioClipboardContextToken += 1
        isScenarioClipboardContextActive = false
        scenarioJob?.cancel()
        scenarioPopup?.dismiss()
        clipboardReadJob?.cancel()
        clipboardReadJob = null
        clipboardHistory = emptyList()
        cachedClipboardText = null
        isClipboardHistoryExpanded = false
        isClipboardClearedByUser = true
        isHistoricalClipboardSelected = false
        isClipboardPanelVisible = false
        updateClipboardPreview()
    }

    private fun selectClipboardHistoryEntry(text: String) {
        isHistoricalClipboardSelected = true
        isClipboardClearedByUser = false
        cachedClipboardText = text
        isClipboardHistoryExpanded = false
        if (isRimeCompositionActive) {
            RimeJniBridge.clearComposition()
            isRimeCompositionActive = false
        }
        currentInputText = ""
        currentInputConnection?.commitText(text, 1)
        candidates = emptyList()
        isPredicting = false
        aiStatusMessage = null
        updateClipboardPreview()
        renderCandidateBar()
    }

    private fun createClipboardTextView(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(43, 61, 93))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isFocusable = false
            isFocusableInTouchMode = false
        }

    private fun createClipboardHistoryItem(text: String): TextView =
        createClipboardTextView(summarizeClipboardText(text) ?: "").apply {
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.rgb(242, 246, 252))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { selectClipboardHistoryEntry(text) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = keyboardInterfaceText(
                keyboardUiLanguage,
                "選取剪貼簿項目：${summarizeClipboardText(text).orEmpty()}",
                "Select clipboard item: ${summarizeClipboardText(text).orEmpty()}"
            )
        }

    private fun createClipboardScrollButton(
        symbol: String,
        description: String,
        distance: Int
    ): TextView = createToolbarButton(symbol, description) {
        clipboardScrollView?.smoothScrollBy(0, distance)
    }.apply {
        textSize = 16f
        isFocusable = false
    }

    private fun createClipboardPreview(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(135, 155, 190))
                cornerRadius = dp(8).toFloat()
            }
            isFocusable = false
        }
        clipboardPreviewView = panel

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        clipboardHistoryButton = createToolbarButton(
            keyboardInterfaceText(keyboardUiLanguage, "選擇", "Select"),
            keyboardInterfaceText(
                keyboardUiLanguage,
                "瀏覽剪貼簿紀錄",
                "Browse clipboard history"
            )
        ) {
            isClipboardHistoryExpanded = !isClipboardHistoryExpanded
            updateClipboardPreview()
        }.apply {
            textSize = 12f
            isFocusable = false
        }
        actions.addView(
            clipboardHistoryButton,
            LinearLayout.LayoutParams(dp(82), dp(30)).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )
        actions.addView(
            createToolbarButton(
                keyboardInterfaceText(keyboardUiLanguage, "清空", "Clear"),
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    "清除剪貼簿顯示與紀錄",
                    "Clear clipboard display and history"
                )
            ) {
                clearClipboardHistory()
            }.apply {
                textSize = 12f
                isFocusable = false
            },
            LinearLayout.LayoutParams(dp(72), dp(30))
        )
        panel.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30)
            )
        )

        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val clipboardScrollView = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = true
        }
        this.clipboardScrollView = clipboardScrollView
        val clipboardContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }
        this.clipboardContentContainer = clipboardContentContainer
        clipboardScrollView.addView(
            clipboardContentContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        contentRow.addView(
            clipboardScrollView,
            LinearLayout.LayoutParams(0, dp(58), 1f)
        )
        val scrollButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollButtons.addView(
            createClipboardScrollButton("↑", "Scroll clipboard up", -dp(48)),
            LinearLayout.LayoutParams(dp(36), dp(28)).apply {
                setMargins(dp(4), 0, 0, dp(2))
            }
        )
        scrollButtons.addView(
            createClipboardScrollButton("↓", "Scroll clipboard down", dp(48)),
            LinearLayout.LayoutParams(dp(36), dp(28)).apply {
                setMargins(dp(4), dp(2), 0, 0)
            }
        )
        contentRow.addView(
            scrollButtons,
            LinearLayout.LayoutParams(dp(40), dp(58))
        )
        panel.addView(
            contentRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                setMargins(0, dp(2), 0, 0)
            }
        )
        updateClipboardPreview()
        return panel
    }

    private fun renderKeyboard(root: LinearLayout) {
        root.removeAllViews()
        root.addView(createToolbar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        root.addView(
            createClipboardPreview(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(2), 0, dp(2), dp(3))
            }
        )

        candidateRow = createComposeView().apply {
            setContent {
                AIKeyboardTheme {
                    KeyboardCandidateBar(
                        suggestions = candidates.map { it.label },
                        isSuggesting = isPredicting,
                        statusMessage = aiStatusMessage,
                        interfaceLanguage = keyboardUiLanguage,
                        onCandidateSelected = { index ->
                            candidates.getOrNull(index)?.let(::selectCandidate)
                        }
                    )
                }
            }
        }
        root.addView(candidateRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        renderCandidateBar()

        when (keyboardMode) {
            KeyboardMode.ENGLISH -> renderEnglishKeyboard(root)
            KeyboardMode.CHINESE -> renderChineseKeyboard(root)
            KeyboardMode.NUMERIC -> renderNumericKeyboard(root)
            KeyboardMode.SYMBOLS, KeyboardMode.EMOJI -> renderSymbolsKeyboard(root)
        }
    }

    private fun renderEnglishKeyboard(root: LinearLayout) {
        if (isPasswordField) {
            addCharacterRow(root, listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")) {
                commitLiteralText(it)
            }
        }
        addCharacterRow(root, ENGLISH_QWERTY_ROWS[0]) {
            handleEnglishCharacter(it)
        }
        addCharacterRow(root, ENGLISH_QWERTY_ROWS[1]) {
            handleEnglishCharacter(it)
        }
        val bottomRow = if (isPasswordField) {
            ENGLISH_QWERTY_ROWS[2] + "⌫"
        } else {
            listOf("⇧") + ENGLISH_QWERTY_ROWS[2] + "⌫"
        }
        addActionRow(root, bottomRow) { key ->
            when (key) {
                "⇧" -> {
                    isShifted = !isShifted
                    keyboardRoot?.let(::renderKeyboard)
                }
                "中文" -> switchKeyboardMode(KeyboardMode.CHINESE)
                "⌫" -> deletePreviousCharacter()
                else -> handleEnglishCharacter(key)
            }
        }
        addActionRow(
            root,
            if (isPasswordField) {
                listOf(",", "Space", ".", "Enter")
            } else {
                listOf("☺", "?123", ",", "Space", ".", "Enter")
            }
        ) { key, anchor ->
            when (key) {
                "☺" -> openSymbolLayer(EMOJI_PAGE_INDEX)
                "?123" -> openSymbolLayer(SYMBOLS_PAGE_INDEX)
                else -> handleCommonKey(key, anchor)
            }
        }
    }

    private fun renderChineseKeyboard(root: LinearLayout) {
        if (chineseSchema == ChineseSchema.PINYIN) {
            ENGLISH_QWERTY_ROWS.forEach { keys ->
                addCharacterRow(root, keys, ::handlePinyinCharacter)
            }
        } else {
            DAQIAN_ZHUYIN_ROWS.forEach { keys ->
                addCharacterRow(root, keys, ::handleZhuyinCharacter)
            }
        }
        addActionRow(root, listOf("☺", "?123", ",", "Space", "。", "⌫", "Enter")) { key, anchor ->
            when (key) {
                "☺" -> openSymbolLayer(EMOJI_PAGE_INDEX)
                "?123" -> openSymbolLayer(SYMBOLS_PAGE_INDEX)
                "⌫" -> deletePreviousCharacter()
                else -> handleCommonKey(key, anchor)
            }
        }
    }

    private fun createToolbar(): LinearLayout {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        toolbar.addView(
            createToolbarButton(
                "⚙️",
                keyboardInterfaceText(keyboardUiLanguage, "開啟鍵盤設定", "Open keyboard settings"),
                highlighted = true
            ) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.apply { textSize = 18f },
            LinearLayout.LayoutParams(dp(42), dp(40)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        )
        toolbar.addView(
            createToolbarButton(
                "⌨",
                keyboardInterfaceText(keyboardUiLanguage, "切換輸入法", "Switch keyboard")
            ) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            },
            LinearLayout.LayoutParams(dp(42), dp(40)).apply {
                setMargins(dp(2), 0, dp(4), 0)
            }
        )
        scenarioButton = createToolbarButton(
            scenarioButtonLabel(),
            keyboardInterfaceText(keyboardUiLanguage, "選擇輸入情境", "Choose a scenario")
        ) {
            openScenarioCategories(it)
        }.apply { isEnabled = !isPasswordField }
        toolbar.addView(
            scenarioButton,
            LinearLayout.LayoutParams(0, dp(40), 1.25f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        )
        clipboardButton = createToolbarButton(
            keyboardInterfaceText(keyboardUiLanguage, "剪貼簿", "Clipboard"),
            keyboardInterfaceText(keyboardUiLanguage, "顯示或隱藏剪貼簿", "Show or hide clipboard")
        ) {
            toggleClipboardPanel()
        }.apply {
            textSize = 12f
            isEnabled = !isPasswordField
        }
        toolbar.addView(
            clipboardButton,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        )
        if (shouldShowChineseToolbarControls(keyboardMode)) {
            toolbar.addView(
                createToolbarButton(
                    when (keyboardUiLanguage) {
                        KeyboardUiLanguage.CHINESE -> if (isSimplifiedChinese) "简" else "繁"
                        KeyboardUiLanguage.ENGLISH -> if (isSimplifiedChinese) "SC" else "TC"
                    },
                    keyboardInterfaceText(
                        keyboardUiLanguage,
                        if (isSimplifiedChinese) "切換為繁體候選" else "切換為簡體候選",
                        if (isSimplifiedChinese) {
                            "Switch to Traditional Chinese candidates"
                        } else {
                            "Switch to Simplified Chinese candidates"
                        }
                    )
                ) {
                    toggleSimplification()
                }.apply { isEnabled = !isPasswordField },
                LinearLayout.LayoutParams(dp(42), dp(40)).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            )
            toolbar.addView(
                createToolbarButton(
                    chineseSchemaSwitcherLabel(chineseSchema, keyboardUiLanguage),
                    keyboardInterfaceText(
                        keyboardUiLanguage,
                        if (chineseSchema == ChineseSchema.PINYIN) {
                            "目前為拼音，點擊切換為注音"
                        } else {
                            "目前為注音，點擊切換為拼音"
                        },
                        if (chineseSchema == ChineseSchema.PINYIN) {
                            "Currently Pinyin; switch to Zhuyin"
                        } else {
                            "Currently Zhuyin; switch to Pinyin"
                        }
                    ),
                    highlighted = shouldHighlightChineseSchemaControl(isSimplifiedChinese)
                ) {
                    toggleChineseSchema()
                }.apply { isEnabled = !isPasswordField },
                LinearLayout.LayoutParams(dp(42), dp(40)).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
            )
        }
        toolbar.addView(
            createToolbarButton(
                modeSwitcherLabel(keyboardMode, keyboardUiLanguage),
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    if (keyboardMode == KeyboardMode.CHINESE) "切換至英文鍵盤" else "切換至中文鍵盤",
                    if (keyboardMode == KeyboardMode.CHINESE) "Switch to English keyboard" else "Switch to Chinese keyboard"
                )
            ) {
                switchKeyboardMode(
                    if (keyboardMode == KeyboardMode.CHINESE) {
                        KeyboardMode.ENGLISH
                    } else {
                        KeyboardMode.CHINESE
                    }
                )
            }.apply { isEnabled = !isPasswordField },
            LinearLayout.LayoutParams(dp(52), dp(40)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        )
        return toolbar
    }

    private fun createToolbarButton(
        label: String,
        description: String,
        highlighted: Boolean = false,
        onClick: (View) -> Unit
    ): TextView = TextView(this).apply {
        text = label
        contentDescription = description
        gravity = android.view.Gravity.CENTER
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        textSize = 14f
        setTextColor(
            if (highlighted) Color.WHITE else Color.rgb(43, 61, 93)
        )
        background = GradientDrawable().apply {
            setColor(
                if (highlighted) Color.rgb(71, 118, 230) else Color.rgb(225, 232, 244)
            )
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener(onClick)
    }

    private fun scenarioButtonLabel(): String {
        val selectedId = aiScenarioPreferences.getInt(
            KeyboardPreferenceKeys.SELECTED_SCENARIO_OPTION_ID,
            -1
        )
        val selectedTitle = ScenarioConfig.getAllScenarios()
            .flatMap { it.subOptions }
            .firstOrNull { it.id == selectedId }
            ?.let { scenarioOptionDisplayTitle(it, keyboardUiLanguage) }
        val scenarioLabel = keyboardInterfaceText(keyboardUiLanguage, "情境", "Scenario")
        return selectedTitle?.let { "$scenarioLabel: $it" } ?: scenarioLabel
    }

    private fun refreshScenarioButton() {
        scenarioButton?.text = scenarioButtonLabel()
    }

    private fun renderNumericKeyboard(root: LinearLayout) {
        addCharacterRow(root, listOf("1", "2", "3"), ::commitLiteralText)
        addCharacterRow(root, listOf("4", "5", "6"), ::commitLiteralText)
        addCharacterRow(root, listOf("7", "8", "9"), ::commitLiteralText)
        val bottomRow = if (isPasswordField) {
            listOf("0", "⌫")
        } else {
            listOf("ABC", "0", "⌫")
        }
        addActionRow(root, bottomRow) { key ->
            when (key) {
                "ABC" -> switchKeyboardMode(KeyboardMode.ENGLISH)
                "⌫" -> deletePreviousCharacter()
                else -> commitLiteralText(key)
            }
        }
        addActionRow(
            root,
            if (isPasswordField) listOf(",", "Space", "Enter")
            else listOf("☺", ",", "Space", "Enter")
        ) { key, anchor ->
            when (key) {
                "☺" -> openSymbolLayer(EMOJI_PAGE_INDEX)
                else -> handleCommonKey(key, anchor)
            }
        }
    }

    private fun renderSymbolsKeyboard(root: LinearLayout) {
        addSymbolTabRow(root)
        root.addView(
            buildSymbolGrid(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SYMBOL_GRID_HEIGHT_DP)
            )
        )
        val backLabel = symbolBackLabel()
        val page = SYMBOL_PAGES[symbolPage.coerceIn(0, SYMBOL_PAGES.lastIndex)]
        val bottomKeys = if (page.isEmoji) {
            listOf(backLabel, "Space", "⌫", "Enter")
        } else {
            listOf(backLabel, symbolWidthLabel(), "Space", "⌫", "Enter")
        }
        addActionRow(root, bottomKeys) { key ->
            when {
                key == backLabel -> returnToPreviousKeyboard()
                key == symbolWidthLabel() || key == symbolWidthOtherLabel() -> toggleSymbolWidth()
                key == "⌫" -> deletePreviousCharacter()
                else -> handleCommonKey(key)
            }
        }
    }

    private fun symbolWidthLabel(): String =
        if (isFullWidthSymbols) {
            keyboardInterfaceText(keyboardUiLanguage, SYMBOL_WIDTH_FULL_LABEL, "Full")
        } else {
            keyboardInterfaceText(keyboardUiLanguage, SYMBOL_WIDTH_HALF_LABEL, "Half")
        }

    private fun symbolWidthOtherLabel(): String =
        if (isFullWidthSymbols) {
            keyboardInterfaceText(keyboardUiLanguage, SYMBOL_WIDTH_HALF_LABEL, "Half")
        } else {
            keyboardInterfaceText(keyboardUiLanguage, SYMBOL_WIDTH_FULL_LABEL, "Full")
        }

    private fun toggleSymbolWidth() {
        isFullWidthSymbols = !isFullWidthSymbols
        renderCurrentKeyboard()
    }

    private fun addSymbolTabRow(root: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(
            createSymbolTabButton("‹", false) { changeSymbolPage(-1) },
            LinearLayout.LayoutParams(dp(30), dp(38))
        )
        SYMBOL_PAGES.forEachIndexed { index, page ->
            row.addView(
                createSymbolTabButton(
                    "${index + 1}/${SYMBOL_PAGES.size} ${page.displayTitle(keyboardUiLanguage)}",
                    index == symbolPage
                ) {
                    if (symbolPage != index) {
                        symbolPage = index
                        renderCurrentKeyboard()
                    }
                },
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                }
            )
        }
        row.addView(
            createSymbolTabButton("›", false) { changeSymbolPage(1) },
            LinearLayout.LayoutParams(dp(30), dp(38))
        )
        root.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
        )
    }

    private fun buildSymbolGrid(): View {
        val grid = GridLayout(this).apply {
            columnCount = SYMBOL_GRID_COLUMNS
            setPadding(dp(2), 0, dp(2), dp(2))
        }
        val page = SYMBOL_PAGES[symbolPage.coerceIn(0, SYMBOL_PAGES.lastIndex)]
        page.items.forEachIndexed { index, item ->
            val display = if (page.isEmoji) item else applySymbolWidth(item, isFullWidthSymbols)
            val button = Button(this).apply {
                text = display
                isAllCaps = false
                textSize = if (display.codePointCount(0, display.length) == 1 && display[0].code > 0x2000) 19f else 17f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setTextColor(Color.rgb(40, 48, 62))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(9).toFloat()
                }
                setOnClickListener {
                    finishCurrentComposition()
                    currentInputConnection?.commitText(display, 1)
                }
            }
            grid.addView(
                button,
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(48)
                    columnSpec = GridLayout.spec(index % SYMBOL_GRID_COLUMNS, 1f)
                    rowSpec = GridLayout.spec(index / SYMBOL_GRID_COLUMNS)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            )
        }
        return ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            setBackgroundColor(Color.rgb(242, 244, 248))
            addView(
                grid,
                android.widget.FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val detector = GestureDetector(
                this@AIKeyboardService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent): Boolean = true

                    override fun onFling(
                        first: MotionEvent?,
                        second: MotionEvent,
                        velocityX: Float,
                        velocityY: Float
                    ): Boolean {
                        if (kotlin.math.abs(velocityX) < SYMBOL_SWIPE_VELOCITY_PX) return false
                        if (kotlin.math.abs(velocityY) > kotlin.math.abs(velocityX)) return false
                        changeSymbolPage(if (velocityX < 0) 1 else -1)
                        return true
                    }
                }
            )
            setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                false
            }
        }
    }

    private fun createSymbolTabButton(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = android.view.Gravity.CENTER
        isSingleLine = true
        textSize = 11f
        setTextColor(
            if (selected) Color.WHITE else Color.rgb(43, 61, 93)
        )
        background = GradientDrawable().apply {
            setColor(if (selected) Color.rgb(71, 118, 230) else Color.rgb(222, 230, 244))
            cornerRadius = dp(10).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun symbolBackLabel(): String = when (previousKeyboardMode) {
        KeyboardMode.NUMERIC -> "123"
        else -> "ABC/中"
    }

    private fun changeSymbolPage(delta: Int) {
        val pageCount = SYMBOL_PAGES.size
        val next = (symbolPage + delta + pageCount) % pageCount
        if (next == symbolPage) return
        symbolPage = next
        if (keyboardMode != KeyboardMode.SYMBOLS && keyboardMode != KeyboardMode.EMOJI) {
            keyboardMode = KeyboardMode.SYMBOLS
        }
        renderCurrentKeyboard()
    }

    private fun openSymbolLayer(page: Int) {
        finishCurrentComposition()
        if (
            keyboardMode != KeyboardMode.SYMBOLS &&
            keyboardMode != KeyboardMode.EMOJI
        ) {
            previousKeyboardMode = keyboardMode
        }
        keyboardMode = KeyboardMode.SYMBOLS
        symbolPage = page.coerceIn(0, SYMBOL_PAGES.lastIndex)
        isShifted = false
        renderCurrentKeyboard()
    }

    private fun returnToPreviousKeyboard() {
        keyboardMode = when (previousKeyboardMode) {
            KeyboardMode.SYMBOLS, KeyboardMode.EMOJI -> KeyboardMode.ENGLISH
            else -> previousKeyboardMode
        }
        syncKeyboardUiLanguageWithMode()
        renderCurrentKeyboard()
    }

    private fun renderCurrentKeyboard() {
        keyboardRoot?.let(::renderKeyboard)
    }

    private fun addCharacterRow(
        root: LinearLayout,
        keys: List<String>,
        onKey: (String) -> Unit
    ) {
        addActionRow(root, keys, onKey)
    }

    private fun addActionRow(
        root: LinearLayout,
        keys: List<String>,
        onKey: (String) -> Unit
    ) {
        addActionRow(root, keys) { key, _ -> onKey(key) }
    }

    private fun addActionRow(
        root: LinearLayout,
        keys: List<String>,
        onKey: (String, View) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        keys.forEach { key ->
            val isActionKey = key in setOf(
                "⇧", "⌫", "ABC", "中", "123", "中文", "?123", "Enter", "Space", "☺",
                "‹", "›"
            ) || key == symbolWidthLabel() || key == symbolWidthOtherLabel()
            val button = Button(this).apply {
                text = when {
                    key.matches(Regex("[a-z]")) && isShifted ->
                        key.uppercase(java.util.Locale.ROOT)
                    key == "Space" -> keyboardInterfaceText(
                        keyboardUiLanguage,
                        "空白",
                        "Space"
                    )
                    key == "Enter" -> keyboardInterfaceText(
                        keyboardUiLanguage,
                        "換行",
                        "Enter"
                    )
                    else -> key
                }
                contentDescription = when (key) {
                    symbolWidthLabel() -> keyboardInterfaceText(
                        keyboardUiLanguage,
                        if (isFullWidthSymbols) "目前為全形符號，點擊切換為半形" else "目前為半形符號，點擊切換為全形",
                        if (isFullWidthSymbols) "Currently full-width; switch to half-width" else "Currently half-width; switch to full-width"
                    )
                    else -> null
                }
                isAllCaps = false
                textSize = if (key.length > 2) 12f else 16f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setTextColor(Color.rgb(40, 48, 62))
                setPadding(dp(2), 0, dp(2), 0)
                background = GradientDrawable().apply {
                    setColor(
                        if (isActionKey) Color.rgb(218, 227, 242) else Color.WHITE
                    )
                    cornerRadius = dp(9).toFloat()
                }
                setOnClickListener { onKey(key, it) }
                if (key == "⌫") {
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                view.isPressed = true
                                startDeleteRepeat(view)
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                            MotionEvent.ACTION_POINTER_UP -> {
                                view.isPressed = false
                                stopDeleteRepeat()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (
                                    event.x < 0 || event.x >= view.width ||
                                    event.y < 0 || event.y >= view.height
                                ) {
                                    view.isPressed = false
                                    stopDeleteRepeat()
                                }
                                true
                            }
                            else -> true
                        }
                    }
                }
            }
            val layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            row.addView(button, layoutParams)
        }
        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )
    }

    private fun renderCandidateBar() {
        candidateRow?.invalidate()
    }

    private fun createComposeView(): ComposeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@AIKeyboardService)
        setViewTreeViewModelStoreOwner(this@AIKeyboardService)
        setViewTreeSavedStateRegistryOwner(this@AIKeyboardService)
        setParentCompositionContext(composeRecomposer)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    private fun createImePopup(
        content: View?,
        width: Int,
        height: Int
    ): PopupWindow = PopupWindow(content, width, height).apply {
        isFocusable = false
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        setBackgroundDrawable(ColorDrawable(Color.WHITE))
        elevation = dp(8).toFloat()
    }

    private fun showScenarioCategories(anchor: View) {
        if (isPasswordField) {
            Toast.makeText(
                this,
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    "密碼欄位不支援 AI 情境回覆。",
                    "AI scenario replies are unavailable in password fields."
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!ModelInitializer.isModelReady(this)) {
            showModelRequiredPrompt(anchor, scenarioClipboardContextToken)
            return
        }
        predictionJob?.cancel()
        predictionJob = null
        val entries = ScenarioConfig.getAllScenarios().flatMap { category ->
            category.subOptions.map { option -> category.title to option }
        }
        showScenarioMenu(anchor, entries, scenarioClipboardContextToken)
    }

    private fun showScenarioMenu(
        anchor: View,
        entries: List<Pair<String, ScenarioOption>>,
        clipboardContextToken: Int
    ) {
        scenarioPopup?.dismiss()
        val content = createComposeView()
        val popup = createImePopup(
            content,
            dp(SCENARIO_MENU_WIDTH_DP),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        scenarioPopup = popup
        var responseSelected = false
        content.setContent {
            AIKeyboardTheme {
                ScenarioMenu(
                    entries = entries,
                    interfaceLanguage = keyboardUiLanguage,
                    selectedOptionId = aiScenarioPreferences.getInt(
                        KeyboardPreferenceKeys.SELECTED_SCENARIO_OPTION_ID,
                        -1
                    ),
                    onEntrySelected = { option ->
                        responseSelected = true
                        popup.dismiss()
                        aiScenarioPreferences.edit()
                            .putInt(
                                KeyboardPreferenceKeys.SELECTED_SCENARIO_OPTION_ID,
                                option.id
                            )
                            .apply()
                        refreshScenarioButton()
                            requestScenarioResponse(option, clipboardContextToken)
                    }
                )
            }
        }
        popup.setOnDismissListener {
            if (scenarioPopup === popup) scenarioPopup = null
            if (!responseSelected) {
                endScenarioClipboardContext(clipboardContextToken)
            }
        }
        popup.showAsDropDown(anchor, 0, dp(4))
    }

    private fun showModelRequiredPrompt(anchor: View, clipboardContextToken: Int) {
        scenarioPopup?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = keyboardInterfaceText(
                keyboardUiLanguage,
                "需要先準備 AI 模型",
                "Prepare the AI model first"
            )
            textSize = 17f
            setTextColor(Color.rgb(33, 43, 60))
        })
        content.addView(TextView(this).apply {
            text = keyboardInterfaceText(
                keyboardUiLanguage,
                "情境回覆需要本機 Gemma 模型。請先開啟 App 完成模型初始化；" +
                    "測試版會從 App 內建複製模型，正式版將提供下載。",
                "Scenario replies require the local Gemma model. Open the app and finish " +
                    "model setup first."
            )
            textSize = 13f
            setTextColor(Color.rgb(70, 80, 98))
            setPadding(0, dp(8), 0, dp(12))
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val popup = createImePopup(
            content,
            dp(SCENARIO_MENU_WIDTH_DP),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        scenarioPopup = popup
        actions.addView(Button(this).apply {
            text = keyboardInterfaceText(keyboardUiLanguage, "取消", "Cancel")
            isAllCaps = false
            setOnClickListener { popup.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(Button(this).apply {
            text = keyboardInterfaceText(keyboardUiLanguage, "前往 App 準備", "Open app setup")
            isAllCaps = false
            setOnClickListener {
                popup.dismiss()
                startActivity(
                    Intent(this@AIKeyboardService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        content.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )
        popup.setOnDismissListener {
            if (scenarioPopup === popup) scenarioPopup = null
            endScenarioClipboardContext(clipboardContextToken)
        }
        popup.showAsDropDown(anchor, 0, dp(4))
    }

    private fun requestScenarioResponse(
        option: ScenarioOption,
        clipboardContextToken: Int = scenarioClipboardContextToken
    ) {
        if (isPasswordField) {
            Toast.makeText(
                this,
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    "密碼欄位不支援 AI 情境回覆。",
                    "AI scenario replies are unavailable in password fields."
                ),
                Toast.LENGTH_SHORT
            ).show()
            endScenarioClipboardContext(clipboardContextToken)
            return
        }
        if (!isAiScenarioPredictionEnabled()) {
            Toast.makeText(
                this,
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    "請先在主畫面啟用 AI 情境預測。",
                    "Enable AI scenario replies in the app first."
                ),
                Toast.LENGTH_SHORT
            ).show()
            endScenarioClipboardContext(clipboardContextToken)
            return
        }
        val inputConnection = currentInputConnection
        if (inputConnection == null) {
            Toast.makeText(
                this,
                keyboardInterfaceText(keyboardUiLanguage, "目前沒有可用的輸入欄位。", "No input field is available."),
                Toast.LENGTH_SHORT
            ).show()
            endScenarioClipboardContext(clipboardContextToken)
            return
        }
        val contextText = inputConnection.getTextBeforeCursor(MAX_SCENARIO_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()
            .takeLast(MAX_SCENARIO_CONTEXT_CHARS)
            .trim()
        val isEnglishMode = keyboardMode == KeyboardMode.ENGLISH ||
            (
                keyboardMode == KeyboardMode.SYMBOLS ||
                    keyboardMode == KeyboardMode.EMOJI
                ) && previousKeyboardMode == KeyboardMode.ENGLISH

        predictionJob?.cancel()
        scenarioJob?.cancel()
        isPredicting = false
        aiStatusMessage = null
        candidates = listOf(
            KeyboardCandidate(
                keyboardInterfaceText(keyboardUiLanguage, "正在生成", "Generating") +
                    " ${scenarioOptionDisplayTitle(option, keyboardUiLanguage)}…",
                "",
                saveToLocalDict = false
            )
        )
        renderCandidateBar()
        scenarioJob = serviceScope.launch {
            try {
                clipboardReadJob?.join()
                val clipboard = cachedClipboardText
                if (contextText.isBlank() && clipboard.isNullOrBlank()) {
                    candidates = emptyList()
                    renderCandidateBar()
                    Toast.makeText(
                        this@AIKeyboardService,
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "請先輸入文字或複製內容，再產生情境回覆。",
                            "Enter or copy text before generating a scenario reply."
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val suggestions = AiEngine.generateScenario(
                    option,
                    contextText,
                    clipboard,
                    replyInEnglish = shouldUseEnglishScenarioReply(
                        isEnglishMode,
                        contextText,
                        clipboard
                    )
                )
                if (
                    isPasswordField ||
                    !isAiScenarioPredictionEnabled()
                ) return@launch
                if (suggestions.isEmpty()) {
                    throw IllegalStateException(
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "模型沒有產生可用的情境回覆，請重試或縮短輸入內容。",
                            "The model returned no usable replies. Retry or shorten the text."
                        )
                    )
                }
                candidates = suggestions.take(5).map {
                    KeyboardCandidate(it, it, saveToLocalDict = false)
                }
                renderCandidateBar()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Scenario response generation failed.", error)
                if (!isPasswordField && isAiScenarioPredictionEnabled()) {
                    candidates = emptyList()
                    renderCandidateBar()
                    Toast.makeText(
                        this@AIKeyboardService,
                        error.localizedMessage ?: keyboardInterfaceText(
                            keyboardUiLanguage,
                            "AI 情境回覆產生失敗。",
                            "AI scenario reply failed."
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                endScenarioClipboardContext(clipboardContextToken)
            }
        }
    }

    private fun handleEnglishCharacter(character: String) {
        val normalizedCharacter = character.lowercase(java.util.Locale.ROOT)
        if (
            normalizedCharacter.length != 1 ||
            normalizedCharacter[0] !in 'a'..'z'
        ) {
            commitLiteralText(character)
            return
        }
        scenarioJob?.cancel()
        currentInputText += if (isShifted) {
            character.uppercase(java.util.Locale.ROOT)
        } else {
            normalizedCharacter
        }
        currentInputConnection?.setComposingText(currentInputText, 1)
        updateCandidates(currentInputText)
        renderCandidateBar()
        requestPrediction(currentInputText)
    }

    private fun handleZhuyinCharacter(character: String) {
        scenarioJob?.cancel()
        val rimeKeyCode = rimeKeyCodeForZhuyin(character)
        if (
            rimeKeyCode != null &&
            isRimeReady &&
            (isRimeCompositionActive || currentInputText.isEmpty()) &&
            RimeJniBridge.processKey(rimeKeyCode)
        ) {
            isRimeCompositionActive = true
            updateRimeComposition()
            return
        }
        if (isRimeCompositionActive) {
            RimeJniBridge.clearComposition()
            isRimeCompositionActive = false
        }

        currentInputText += character
        currentInputConnection?.setComposingText(currentInputText, 1)
        updateCandidates(currentInputText)
        renderCandidateBar()
        val candidate = resolveZhuyinCandidates(currentInputText, isSimplifiedChinese)
            .firstOrNull() ?: return
        val textBeforeCursor = currentInputConnection
            ?.getTextBeforeCursor(MAX_AUTO_SUGGESTION_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()
        val committedContext = textBeforeCursor
            .removeSuffix(currentInputText)
            .takeLast(MAX_AUTO_SUGGESTION_CONTEXT_CHARS)
        requestPrediction(
            input = currentInputText,
            suggestionContext = committedContext + candidate,
            commitPrefix = candidate
        )
    }

    private fun handlePinyinCharacter(character: String) {
        scenarioJob?.cancel()
        if (isShifted) {
            commitLiteralText(character.uppercase(java.util.Locale.ROOT))
            return
        }
        val rimeKeyCode = rimeKeyCodeForAscii(character.lowercase(java.util.Locale.ROOT))
        if (
            rimeKeyCode != null &&
            isRimeReady &&
            (isRimeCompositionActive || currentInputText.isEmpty()) &&
            RimeJniBridge.processKey(rimeKeyCode)
        ) {
            isRimeCompositionActive = true
            updateRimeComposition()
            return
        }
        if (isRimeCompositionActive) {
            RimeJniBridge.clearComposition()
            isRimeCompositionActive = false
        }
        currentInputText += character.lowercase(java.util.Locale.ROOT)
        currentInputConnection?.setComposingText(currentInputText, 1)
        updateCandidates(currentInputText)
        renderCandidateBar()
    }

    private fun selectCandidate(candidate: KeyboardCandidate) {
        if (candidate.commitText.isEmpty()) return
        predictionJob?.cancel()
        scenarioJob?.cancel()
        if (isRimeCompositionActive) {
            val selectedIndex = parseRimeComposition(
                RimeJniBridge.getCompositionAndCandidates()
            ).candidates.indexOf(candidate.commitText)
            if (selectedIndex >= 0) RimeJniBridge.selectCandidate(selectedIndex)
            RimeJniBridge.clearComposition()
            isRimeCompositionActive = false
        }
        if (candidate.saveToLocalDict) saveWordToLocalDict(candidate.commitText)
        currentInputConnection?.commitText(candidate.commitText, 1)
        currentInputText = ""
        isPredicting = false
        aiStatusMessage = null
        candidates = emptyList()
        renderCandidateBar()
    }

    private fun handleCommonKey(key: String, anchor: View? = null) {
        when (key) {
            "Space" -> {
                if (!commitZhuyinComposition()) handleSpaceKey()
            }
            "?123" -> openSymbolLayer(SYMBOLS_PAGE_INDEX)
            "☺" -> openSymbolLayer(EMOJI_PAGE_INDEX)
            "Enter" -> {
                if (commitZhuyinComposition()) return
                finishCurrentComposition()
                performEnterAction()
            }
            else -> commitLiteralText(key)
        }
    }

    private fun commitLiteralText(text: String) {
        finishCurrentComposition()
        currentInputConnection?.commitText(text, 1)
    }

    private fun finishCurrentComposition() {
        predictionJob?.cancel()
        scenarioJob?.cancel()
        val inputConnection = currentInputConnection
        if (
            keyboardMode == KeyboardMode.CHINESE &&
            (currentInputText.isNotEmpty() || isRimeCompositionActive)
        ) {
            if (isRimeCompositionActive) {
                val rimeCandidates = parseRimeComposition(
                    RimeJniBridge.getCompositionAndCandidates()
                ).candidates
                val candidate = candidates.firstOrNull()?.commitText
                    ?.takeIf(String::isNotBlank)
                    ?: rimeCandidates.firstOrNull()
                if (candidate != null) {
                    val selectedIndex = rimeCandidates.indexOf(candidate)
                    if (selectedIndex >= 0) RimeJniBridge.selectCandidate(selectedIndex)
                    inputConnection?.commitText(candidate, 1)
                } else {
                    inputConnection?.setComposingText("", 1)
                    inputConnection?.finishComposingText()
                }
                RimeJniBridge.clearComposition()
                isRimeCompositionActive = false
            } else {
                val candidate = candidates.firstOrNull()?.commitText
                    ?.takeIf(String::isNotBlank)
                    ?: resolvePhoneticPhraseCandidates(
                        currentInputText,
                        chineseSchema,
                        isSimplifiedChinese
                    ).firstOrNull()
                if (candidate != null) {
                    inputConnection?.commitText(candidate, 1)
                } else {
                    inputConnection?.setComposingText("", 1)
                    inputConnection?.finishComposingText()
                }
            }
        } else {
            inputConnection?.finishComposingText()
        }
        currentInputText = ""
        isPredicting = false
        aiStatusMessage = null
        candidates = emptyList()
        renderCandidateBar()
    }

    private fun commitZhuyinComposition(): Boolean {
        if (
            keyboardMode != KeyboardMode.CHINESE ||
            (currentInputText.isEmpty() && !isRimeCompositionActive)
        ) return false
        finishCurrentComposition()
        return true
    }

    private fun clearComposingInput() {
        predictionJob?.cancel()
        scenarioJob?.cancel()
        if (isRimeCompositionActive) RimeJniBridge.clearComposition()
        isRimeCompositionActive = false
        currentInputText = ""
        isPredicting = false
        aiStatusMessage = null
        candidates = emptyList()
    }

    private fun deletePreviousCharacter() {
        scenarioJob?.cancel()
        val inputConnection = currentInputConnection ?: return
        if (hasSelectedText(inputConnection.getSelectedText(0))) {
            predictionJob?.cancel()
            if (isRimeCompositionActive) RimeJniBridge.clearComposition()
            isRimeCompositionActive = false
            currentInputText = ""
            inputConnection.commitText("", 1)
            isPredicting = false
            aiStatusMessage = null
            candidates = emptyList()
            renderCandidateBar()
            return
        }
        if (isRimeCompositionActive) {
            if (RimeJniBridge.processKey(0xFF08)) {
                updateRimeComposition()
                return
            }
            RimeJniBridge.clearComposition()
            isRimeCompositionActive = false
        }
        if (currentInputText.isNotEmpty()) {
            currentInputText = currentInputText.dropLast(1)
            predictionJob?.cancel()
            if (currentInputText.isEmpty()) {
                currentInputConnection?.setComposingText("", 1)
                currentInputConnection?.finishComposingText()
                isPredicting = false
                aiStatusMessage = null
                candidates = emptyList()
            } else {
                currentInputConnection?.setComposingText(currentInputText, 1)
                if (keyboardMode == KeyboardMode.ENGLISH) {
                    updateCandidates(currentInputText)
                    requestPrediction(currentInputText)
                } else {
                    updateCandidates(currentInputText)
                }
            }
            renderCandidateBar()
            return
        }

        if (!inputConnection.deleteSurroundingTextInCodePoints(1, 0)) {
            inputConnection.deleteSurroundingText(1, 0)
        }
    }

    private fun startDeleteRepeat(view: View) {
        stopDeleteRepeat()
        deletePreviousCharacter()
        val repeatRunnable = object : Runnable {
            override fun run() {
                if (!view.isPressed) {
                    stopDeleteRepeat()
                    return
                }
                deletePreviousCharacter()
                deleteRepeatHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
            }
        }
        deleteRepeatRunnable = repeatRunnable
        deleteRepeatHandler.postDelayed(repeatRunnable, BACKSPACE_LONG_PRESS_DELAY_MS)
    }

    private fun stopDeleteRepeat() {
        deleteRepeatRunnable?.let(deleteRepeatHandler::removeCallbacks)
        deleteRepeatRunnable = null
    }

    private fun updateRimeComposition() {
        val composition = parseRimeComposition(RimeJniBridge.getCompositionAndCandidates())
        currentInputText = composition.preedit
        currentInputConnection?.setComposingText(currentInputText, 1)
        val phraseCandidates = when (chineseSchema) {
            ChineseSchema.ZHUYIN ->
                resolveZhuyinCandidates(currentInputText, isSimplifiedChinese)
            ChineseSchema.PINYIN ->
                resolvePhoneticPhraseCandidates(
                    currentInputText,
                    chineseSchema,
                    isSimplifiedChinese
                )
        }
        val orderedCandidates = if (
            isPhoneticInitialAbbreviation(currentInputText, chineseSchema)
        ) {
            phraseCandidates + composition.candidates
        } else {
            composition.candidates + phraseCandidates
        }
        candidates = orderedCandidates.distinct().map { KeyboardCandidate(it, it) }
        renderCandidateBar()
    }

    private fun requestPrediction(
        input: String,
        suggestionContext: String = input,
        commitPrefix: String = input
    ) {
        if (
            isPasswordField ||
            (keyboardMode != KeyboardMode.ENGLISH && keyboardMode != KeyboardMode.CHINESE) ||
            input.isBlank() ||
            !isAiScenarioPredictionEnabled()
        ) return
        predictionJob?.cancel()
        val inputSnapshot = input
        val editorContext = currentInputConnection
            ?.getTextBeforeCursor(MAX_AUTO_SUGGESTION_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()
            .takeLast(MAX_AUTO_SUGGESTION_CONTEXT_CHARS)
        val context = if (suggestionContext == inputSnapshot) {
            if (editorContext.endsWith(inputSnapshot)) {
                editorContext
            } else {
                editorContext + inputSnapshot
            }
        } else {
            suggestionContext
        }.takeLast(MAX_AUTO_SUGGESTION_CONTEXT_CHARS)
        isPredicting = true
        aiStatusMessage = keyboardInterfaceText(
            keyboardUiLanguage,
            "AI 推論中…",
            "AI generating…"
        )
        renderCandidateBar()
        predictionJob = serviceScope.launch {
            delay(PREDICTION_DEBOUNCE_MS)
            try {
                if (!isAiScenarioPredictionEnabled()) return@launch
                val suggestions = withContext(Dispatchers.IO) {
                    AiEngine.suggest(context)
                }
                if (
                    isAiScenarioPredictionEnabled() &&
                    (
                        keyboardMode == KeyboardMode.ENGLISH ||
                            keyboardMode == KeyboardMode.CHINESE
                        ) &&
                    currentInputText == inputSnapshot
                ) {
                        isPredicting = false
                        aiStatusMessage = if (suggestions.isEmpty()) {
                            keyboardInterfaceText(keyboardUiLanguage, "AI 暫無候選", "No AI suggestions")
                        } else {
                            null
                        }
                        if (suggestions.isNotEmpty()) {
                            val commitSuggestions = suggestions.map {
                                completeAiCandidate(commitPrefix, it)
                            }
                            updateCandidates(inputSnapshot, aiWords = commitSuggestions)
                    }
                    renderCandidateBar()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "English word prediction failed.", error)
                if (currentInputText == inputSnapshot) {
                        isPredicting = false
                        aiStatusMessage = keyboardInterfaceText(
                            keyboardUiLanguage,
                            "AI 不可用：${error.localizedMessage ?: "模型或 JNI 尚未就緒"}",
                            "AI unavailable: ${error.localizedMessage ?: "model or JNI is not ready"}"
                        )
                        renderCandidateBar()
                }
            }
        }
    }

    private fun saveWordToLocalDict(word: String) {
        if (isPasswordField) return
        val normalizedWord = word.trim().lowercase(java.util.Locale.ROOT)
        if (normalizedWord.isEmpty()) return
        val preferences = localWordPreferences
        preferences.edit()
            .putInt(normalizedWord, preferences.getInt(normalizedWord, 0) + 1)
            .apply()
    }

    private fun getHighFrequencyWords(prefix: String): List<String> {
        if (isPasswordField) return emptyList()
        val normalizedPrefix = prefix.trim().lowercase(java.util.Locale.ROOT)
        return localWordPreferences.all
            .asSequence()
            .filter { (word, count) ->
                word.startsWith(normalizedPrefix) && count is Int && count > 0
            }
            .sortedWith(
                compareByDescending<Map.Entry<String, *>> {
                    it.value as? Int ?: 0
                }.thenBy { it.key }
            )
            .take(3)
            .map { it.key }
            .toList()
    }

    private fun updateCandidates(
        prefix: String,
        aiWord: String? = null,
        prioritizeAi: Boolean = false,
        aiWords: List<String> = emptyList()
    ) {
        if (isPasswordField) {
            candidates = emptyList()
            return
        }
        val staticWords = when (keyboardMode) {
            KeyboardMode.ENGLISH -> englishStaticDictionary.filter {
                it.startsWith(prefix, ignoreCase = true)
            }
            KeyboardMode.CHINESE -> if (chineseSchema == ChineseSchema.PINYIN) {
                resolvePhoneticPhraseCandidates(prefix, chineseSchema, isSimplifiedChinese)
            } else {
                resolveZhuyinCandidates(prefix, isSimplifiedChinese)
            }
            KeyboardMode.NUMERIC,
            KeyboardMode.SYMBOLS,
            KeyboardMode.EMOJI -> emptyList()
        }
        candidates = mergeCandidateSources(
            localWords = getHighFrequencyWords(prefix),
            aiWord = aiWord,
            staticWords = staticWords,
            isEnglish = keyboardMode == KeyboardMode.ENGLISH,
            autoSpace = autoSpaceEnabled,
            prioritizeAi = prioritizeAi,
            aiWords = aiWords
        ).map { (label, text) -> KeyboardCandidate(label, text) }
    }

    private fun handleSpaceKey() {
        val inputConnection = currentInputConnection ?: return
        finishCurrentComposition()
        inputConnection.commitText(" ", 1)
        if (isPasswordField || keyboardMode != KeyboardMode.ENGLISH) return

        val context = inputConnection.getTextBeforeCursor(50, 0)
            ?.toString()
            .orEmpty()
        if (context.isBlank()) return
        updateCandidates("")
        renderCandidateBar()
        requestFollowingWordPrediction(context)
    }

    private fun requestFollowingWordPrediction(context: String) {
        if (!isAiScenarioPredictionEnabled()) return
        predictionJob?.cancel()
        val boundedContext = context.takeLast(50)
        predictionJob = serviceScope.launch {
            delay(PREDICTION_DEBOUNCE_MS)
            try {
                if (!isAiScenarioPredictionEnabled()) return@launch
                val prediction = AiEngine.predictFollowingWord(boundedContext)
                if (
                    prediction != null &&
                    isAiScenarioPredictionEnabled() &&
                    !isPasswordField &&
                    keyboardMode == KeyboardMode.ENGLISH &&
                    currentInputText.isEmpty()
                ) {
                    updateCandidates("", prediction, prioritizeAi = true)
                    renderCandidateBar()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Next-word prediction failed.", error)
            }
        }
    }

    private fun isAiScenarioPredictionEnabled(): Boolean =
        aiScenarioPreferences.getBoolean(KeyboardPreferenceKeys.AI_SCENARIO_ENABLED, true)

    private fun performEnterAction() {
        val inputConnection = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (
            action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED ||
            !inputConnection.performEditorAction(action)
        ) {
            inputConnection.commitText("\n", 1)
        }
    }

    private fun switchKeyboardMode(mode: KeyboardMode) {
        finishCurrentComposition()
        isShifted = false
        keyboardMode = mode
        if (mode == KeyboardMode.ENGLISH || mode == KeyboardMode.CHINESE) {
            aiScenarioPreferences.edit()
                .putBoolean(KeyboardPreferenceKeys.UI_LANGUAGE_USER_SELECTED, true)
                .apply()
        }
        syncKeyboardUiLanguageWithMode()
        keyboardRoot?.let(::renderKeyboard)
    }

    private fun applyKeyboardUiLanguage(language: KeyboardUiLanguage) {
        if (keyboardUiLanguage == language) return
        val languageMode = if (language == KeyboardUiLanguage.CHINESE) {
            KeyboardMode.CHINESE
        } else {
            KeyboardMode.ENGLISH
        }
        val changesInputMode = !isPasswordField &&
            (keyboardMode == KeyboardMode.CHINESE || keyboardMode == KeyboardMode.ENGLISH)
        if (changesInputMode && keyboardMode != languageMode) finishCurrentComposition()
        keyboardUiLanguage = language
        when (keyboardMode) {
            KeyboardMode.CHINESE, KeyboardMode.ENGLISH -> keyboardMode = languageMode
            KeyboardMode.SYMBOLS, KeyboardMode.EMOJI -> previousKeyboardMode = languageMode
            KeyboardMode.NUMERIC -> Unit
        }
        keyboardRoot?.let(::renderKeyboard)
    }

    private fun syncKeyboardUiLanguageWithMode() {
        val language = when (keyboardMode) {
            KeyboardMode.CHINESE -> KeyboardUiLanguage.CHINESE
            KeyboardMode.ENGLISH -> KeyboardUiLanguage.ENGLISH
            else -> return
        }
        keyboardUiLanguage = language
        aiScenarioPreferences.edit()
            .putString(KeyboardPreferenceKeys.UI_LANGUAGE, language.preferenceValue())
            .apply()
    }

    private fun applyChineseRimeSettings() {
        if (!isRimeReady) return
        val schemaId = if (chineseSchema == ChineseSchema.PINYIN) {
            RimeJniBridge.SCHEMA_PINYIN
        } else {
            RimeJniBridge.SCHEMA_BOPOMOFO
        }
        if (
            RimeJniBridge.currentSchemaId != schemaId &&
            !RimeJniBridge.selectSchema(schemaId)
        ) {
            Log.w(TAG, "Could not select $schemaId; falling back to bopomofo.")
            chineseSchema = ChineseSchema.ZHUYIN
            RimeJniBridge.selectSchema(RimeJniBridge.SCHEMA_BOPOMOFO)
        }
        if (!RimeJniBridge.setOption("simplification", isSimplifiedChinese)) {
            Log.w(TAG, "Could not apply the simplification preference.")
        }
    }

    private fun toggleChineseSchema() {
        val targetSchema = if (chineseSchema == ChineseSchema.ZHUYIN) {
            ChineseSchema.PINYIN
        } else {
            ChineseSchema.ZHUYIN
        }
        if (!setChineseSchema(targetSchema)) return
        if (keyboardMode != KeyboardMode.CHINESE) {
            switchKeyboardMode(KeyboardMode.CHINESE)
        }
        Toast.makeText(
            this,
            keyboardInterfaceText(
                keyboardUiLanguage,
                if (targetSchema == ChineseSchema.PINYIN) "已切換至拼音輸入" else "已切換至注音輸入",
                if (targetSchema == ChineseSchema.PINYIN) "Switched to Pinyin" else "Switched to Zhuyin"
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setChineseSchema(schema: ChineseSchema): Boolean {
        if (schema == chineseSchema) return true
        finishCurrentComposition()
        if (isRimeReady) {
            val schemaId = if (schema == ChineseSchema.PINYIN) {
                RimeJniBridge.SCHEMA_PINYIN
            } else {
                RimeJniBridge.SCHEMA_BOPOMOFO
            }
            if (!RimeJniBridge.selectSchema(schemaId)) {
                Toast.makeText(
                    this,
                    keyboardInterfaceText(
                        keyboardUiLanguage,
                        "輸入方案尚未就緒，請稍後再試。",
                        "Input scheme is not ready. Try again shortly."
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }
            RimeJniBridge.setOption("simplification", isSimplifiedChinese)
        }
        chineseSchema = schema
        persistChinesePreferences()
        keyboardRoot?.let(::renderKeyboard)
        return true
    }

    private fun toggleSimplification() {
        val next = !isSimplifiedChinese
        if (isRimeReady && !RimeJniBridge.setOption("simplification", next)) {
            Toast.makeText(
                this,
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    "目前輸入方案不支援繁簡切換。",
                    "This input scheme does not support Simplified/Traditional switching."
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        isSimplifiedChinese = next
        persistChinesePreferences()
        keyboardRoot?.let(::renderKeyboard)
        Toast.makeText(
            this,
            keyboardInterfaceText(
                keyboardUiLanguage,
                if (next) "已切換為簡體候選" else "已切換為繁體候選",
                if (next) "Switched to Simplified Chinese candidates" else "Switched to Traditional Chinese candidates"
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun persistChinesePreferences() {
        aiScenarioPreferences.edit()
            .putString(
                KeyboardPreferenceKeys.CHINESE_SCHEMA,
                if (chineseSchema == ChineseSchema.PINYIN) "pinyin" else "zhuyin"
            )
            .putBoolean(KeyboardPreferenceKeys.CHINESE_SIMPLIFIED, isSimplifiedChinese)
            .apply()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "AIKeyboardService"
        const val PREDICTION_DEBOUNCE_MS = 450L
        const val LOCAL_WORD_PREFERENCES = "local_word_frequency"
        const val AI_CANDIDATE_PREFIX = "✨ "
        const val MAX_CLIPBOARD_CHARS = 160
        const val MAX_SCENARIO_CONTEXT_CHARS = 160
        const val MAX_AUTO_SUGGESTION_CONTEXT_CHARS = 160
        const val SCENARIO_MENU_WIDTH_DP = 280
        const val SYMBOL_GRID_COLUMNS = 8
        const val SYMBOL_GRID_HEIGHT_DP = 168
        const val SYMBOL_SWIPE_VELOCITY_PX = 600f
        const val SYMBOL_WIDTH_FULL_LABEL = "全形"
        const val SYMBOL_WIDTH_HALF_LABEL = "半形"
        const val BACKSPACE_LONG_PRESS_DELAY_MS = 400L
        const val BACKSPACE_REPEAT_INTERVAL_MS = 55L
    }
}
