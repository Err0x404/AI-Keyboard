package com.coinrangersdebthero.aikeyboard

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coinrangersdebthero.aikeyboard.ai.AiEngine
import com.coinrangersdebthero.aikeyboard.ai.ModelInitializer
import com.coinrangersdebthero.aikeyboard.ui.theme.AIKeyboardTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

internal fun appLocaleTag(language: KeyboardUiLanguage): String = when (language) {
    KeyboardUiLanguage.ENGLISH -> "en"
    KeyboardUiLanguage.CHINESE -> "zh-TW"
}

private enum class ModelStatusKind {
    NOT_INITIALIZED,
    CHECKING,
    READY,
    INITIALIZATION_ERROR,
    LOADED,
    LOADING_ERROR
}

class MainActivity : ComponentActivity() {
    private var isKeyboardEnabled by mutableStateOf(false)
    private var isKeyboardSelected by mutableStateOf(false)
    private var modelStatusText by mutableStateOf("Not initialized")
    private var modelStatusKind = ModelStatusKind.NOT_INITIALIZED
    private var modelStatusDetail = ""
    private var isModelInitializing by mutableStateOf(false)
    private var modelInitializationJob: Job? = null
    private var isModelLoading by mutableStateOf(false)
    private var modelLoadError by mutableStateOf<String?>(null)
    private var modelLoadJob: Job? = null
    private var isAiScenarioPredictionEnabled by mutableStateOf(true)
    private var keyboardUiLanguage by mutableStateOf(KeyboardUiLanguage.ENGLISH)

    override fun attachBaseContext(newBase: Context) {
        val language = KeyboardUiLanguage.fromPreference(
            newBase.getSharedPreferences(KeyboardPreferenceKeys.FILE_NAME, MODE_PRIVATE)
                .getString(KeyboardPreferenceKeys.UI_LANGUAGE, null)
        )
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(appLocaleTag(language)))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setLocales(LocaleList.forLanguageTags(appLocaleTag(language)))
            }
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiEngine.initialize(applicationContext)
        isAiScenarioPredictionEnabled = getSharedPreferences(
            KeyboardPreferenceKeys.FILE_NAME,
            MODE_PRIVATE
        ).getBoolean(KeyboardPreferenceKeys.AI_SCENARIO_ENABLED, true)
        keyboardUiLanguage = KeyboardUiLanguage.fromPreference(
            getSharedPreferences(KeyboardPreferenceKeys.FILE_NAME, MODE_PRIVATE)
                .getString(KeyboardPreferenceKeys.UI_LANGUAGE, null)
        )
        updateModelStatusText()
        applyApplicationLocale(keyboardUiLanguage)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                refreshKeyboardStatus()
            }
        }
        setContent {
            AIKeyboardTheme {
                KeyboardSetupScreen(
                    isKeyboardEnabled = isKeyboardEnabled,
                    isKeyboardSelected = isKeyboardSelected,
                    modelStatusText = modelStatusText,
                    isModelInitializing = isModelInitializing,
                    isModelLoading = isModelLoading,
                    modelLoadError = modelLoadError,
                    isAiScenarioPredictionEnabled = isAiScenarioPredictionEnabled,
                    keyboardUiLanguage = keyboardUiLanguage,
                    onRefreshKeyboardStatus = ::refreshKeyboardStatus,
                    onSelectModel = ::loadSelectedModel,
                    onAiScenarioPredictionChange = ::persistAiScenarioPredictionEnabled,
                    onKeyboardUiLanguageChange = ::persistKeyboardUiLanguage,
                    onDismissModelLoadError = { modelLoadError = null },
                    onOpenKeyboardSettings = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onChooseKeyboard = {
                        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    },
                    onClearUserDictionary = ::clearUserDictionary
                )
            }
        }
        startModelInitialization()
    }

    private fun refreshKeyboardStatus() {
        keyboardUiLanguage = KeyboardUiLanguage.fromPreference(
            getSharedPreferences(KeyboardPreferenceKeys.FILE_NAME, MODE_PRIVATE)
                .getString(KeyboardPreferenceKeys.UI_LANGUAGE, null)
        )
        val component = ComponentName(this, AIKeyboardService::class.java)
        val imeId = component.flattenToShortString()
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        isKeyboardEnabled = inputMethodManager.enabledInputMethodList.any {
            it.id == imeId
        }
        val currentInputMethod = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        isKeyboardSelected = currentInputMethod == imeId
    }

    private fun persistAiScenarioPredictionEnabled(enabled: Boolean) {
        isAiScenarioPredictionEnabled = enabled
        getSharedPreferences(KeyboardPreferenceKeys.FILE_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KeyboardPreferenceKeys.AI_SCENARIO_ENABLED, enabled)
            .apply()
    }

    private fun persistKeyboardUiLanguage(language: KeyboardUiLanguage) {
        keyboardUiLanguage = language
        updateModelStatusText()
        getSharedPreferences(KeyboardPreferenceKeys.FILE_NAME, MODE_PRIVATE)
            .edit()
            .putString(KeyboardPreferenceKeys.UI_LANGUAGE, language.preferenceValue())
            .putBoolean(KeyboardPreferenceKeys.UI_LANGUAGE_USER_SELECTED, true)
            .apply()
        applyApplicationLocale(language)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate()
    }

    private fun applyApplicationLocale(language: KeyboardUiLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val localeManager = getSystemService(LocaleManager::class.java)
        val locales = LocaleList.forLanguageTags(appLocaleTag(language))
        if (localeManager.applicationLocales != locales) {
            localeManager.applicationLocales = locales
        }
    }

    private fun updateModelStatus(
        kind: ModelStatusKind,
        detail: String = ""
    ) {
        modelStatusKind = kind
        modelStatusDetail = detail
        updateModelStatusText()
    }

    private fun updateModelStatusText() {
        val chinese = when (modelStatusKind) {
            ModelStatusKind.NOT_INITIALIZED -> "未初始化"
            ModelStatusKind.CHECKING -> "正在檢查本機模型，請稍候..."
            ModelStatusKind.READY -> "模型已就緒 ($modelStatusDetail)"
            ModelStatusKind.INITIALIZATION_ERROR -> "未初始化：$modelStatusDetail"
            ModelStatusKind.LOADED -> "模型已載入 ($modelStatusDetail)"
            ModelStatusKind.LOADING_ERROR -> "AI 暫時不可用：$modelStatusDetail"
        }
        val english = when (modelStatusKind) {
            ModelStatusKind.NOT_INITIALIZED -> "Not initialized"
            ModelStatusKind.CHECKING -> "Checking the local model..."
            ModelStatusKind.READY -> "Model ready ($modelStatusDetail)"
            ModelStatusKind.INITIALIZATION_ERROR -> "Not initialized: $modelStatusDetail"
            ModelStatusKind.LOADED -> "Model loaded ($modelStatusDetail)"
            ModelStatusKind.LOADING_ERROR -> "AI temporarily unavailable: $modelStatusDetail"
        }
        modelStatusText = keyboardInterfaceText(keyboardUiLanguage, chinese, english)
    }

    private fun startModelInitialization() {
        if (modelInitializationJob?.isActive == true) return

        modelInitializationJob = lifecycleScope.launch {
            isModelInitializing = true
            updateModelStatus(ModelStatusKind.CHECKING)
            try {
                val modelFile = requireModelFile()
                updateModelStatus(ModelStatusKind.READY, formatModelSize(modelFile.length()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateModelStatus(
                    ModelStatusKind.INITIALIZATION_ERROR,
                    error.localizedMessage ?: keyboardInterfaceText(
                        keyboardUiLanguage,
                        "模型檔案無法讀取",
                        "The model file could not be read"
                    )
                )
            } finally {
                isModelInitializing = false
                modelInitializationJob = null
            }
        }
    }

    private fun loadSelectedModel() {
        if (modelLoadJob?.isActive == true) return

        modelLoadJob = lifecycleScope.launch {
            isModelLoading = true
            modelLoadError = null
            try {
                modelInitializationJob?.join()
                val modelFile = requireModelFile()
                if (!modelFile.isFile || modelFile.length() <= 0L) {
                    throw IOException(
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "本機模型檔案不存在或內容為空。",
                            "The local model file is missing or empty."
                        )
                    )
                }
                AiEngine.loadModel()
                updateModelStatus(ModelStatusKind.LOADED, formatModelSize(modelFile.length()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = error.localizedMessage ?: keyboardInterfaceText(
                    keyboardUiLanguage,
                    "模型載入時發生未知錯誤。",
                    "An unknown error occurred while loading the model."
                )
                val message = keyboardInterfaceText(
                    keyboardUiLanguage,
                    "AI 暫時不可用：$detail",
                    "AI temporarily unavailable: $detail"
                )
                modelLoadError = message
                updateModelStatus(ModelStatusKind.LOADING_ERROR, detail)
            } finally {
                isModelLoading = false
                modelLoadJob = null
            }
        }
    }

    private suspend fun requireModelFile(): File =
        ModelInitializer.initialize(applicationContext)
            ?: throw IOException(
            ModelInitializer.status.value.errorMessage ?: keyboardInterfaceText(
                keyboardUiLanguage,
                "本機模型檔案無法使用。",
                "The local model file is unavailable."
            )
            )

    private fun formatModelSize(bytes: Long): String {
        val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.getDefault(), "%.1f MB", megabytes)
    }

    private fun clearUserDictionary() {
        val userDir = File(filesDir, "rime/user")
        val cleared = runCatching {
            userDir.deleteRecursively()
        }.getOrDefault(false)
        android.widget.Toast.makeText(
            this,
            if (cleared) {
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    "已清除本機使用者詞庫，重新開啟鍵盤後生效。",
                    "Local user dictionary cleared. Reopen the keyboard for the change to take effect."
                )
            } else {
                keyboardInterfaceText(
                    keyboardUiLanguage,
                    "沒有可清除的使用者詞庫。",
                    "There is no user dictionary to clear."
                )
            },
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
private fun KeyboardSetupScreen(
    isKeyboardEnabled: Boolean,
    isKeyboardSelected: Boolean,
    modelStatusText: String,
    isModelInitializing: Boolean,
    isModelLoading: Boolean,
    modelLoadError: String?,
    isAiScenarioPredictionEnabled: Boolean,
    keyboardUiLanguage: KeyboardUiLanguage,
    onRefreshKeyboardStatus: () -> Unit,
    onSelectModel: () -> Unit,
    onAiScenarioPredictionChange: (Boolean) -> Unit,
    onKeyboardUiLanguageChange: (KeyboardUiLanguage) -> Unit,
    onDismissModelLoadError: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onChooseKeyboard: () -> Unit,
    onClearUserDictionary: () -> Unit
) {
    var sampleText by remember { mutableStateOf("") }
    var showLicenses by remember { mutableStateOf(false) }
    var showPrivacyUsage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, onRefreshKeyboardStatus) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshKeyboardStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = keyboardInterfaceText(
                    keyboardUiLanguage,
                    "設定 AI 鍵盤",
                    "Set up AI Keyboard"
                ),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = stringResource(R.string.usage_disclaimer_statement),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = keyboardInterfaceText(
                    keyboardUiLanguage,
                    "完成下列步驟後，就能在文字輸入欄切換到 AI 鍵盤。",
                    "Follow these steps to enable AI Keyboard in text fields."
                ),
                style = MaterialTheme.typography.bodyLarge
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = keyboardInterfaceText(
                            keyboardUiLanguage,
                            "App 與鍵盤介面語言",
                            "App and keyboard language"
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = keyboardInterfaceText(
                            keyboardUiLanguage,
                            "選擇介面語言與鍵盤配置；變更會立即套用並同步至輸入法服務。",
                            "Choose the interface language and keyboard layout. Changes apply immediately and sync with the input method."
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = keyboardUiLanguage == KeyboardUiLanguage.CHINESE,
                            onClick = {
                                onKeyboardUiLanguageChange(KeyboardUiLanguage.CHINESE)
                            }
                        )
                        Text(
                            keyboardInterfaceText(
                                keyboardUiLanguage,
                                "中文介面（注音／拼音）",
                                "Chinese interface (Zhuyin / Pinyin)"
                            )
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = keyboardUiLanguage == KeyboardUiLanguage.ENGLISH,
                            onClick = {
                                onKeyboardUiLanguageChange(KeyboardUiLanguage.ENGLISH)
                            }
                        )
                        Text(
                            keyboardInterfaceText(
                                keyboardUiLanguage,
                                "英文介面（QWERTY）",
                                "English interface (QWERTY)"
                            )
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = keyboardInterfaceText(
                            keyboardUiLanguage,
                            "1. 啟用鍵盤",
                            "1. Enable the keyboard"
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isKeyboardEnabled) {
                            keyboardInterfaceText(
                                keyboardUiLanguage,
                                "狀態：已在系統設定中啟用",
                                "Status: Enabled in system settings"
                            )
                        } else {
                            keyboardInterfaceText(
                                keyboardUiLanguage,
                                "狀態：尚未啟用",
                                "Status: Not enabled"
                            )
                        }
                    )
                    Button(
                        onClick = onOpenKeyboardSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isKeyboardEnabled) {
                                keyboardInterfaceText(
                                    keyboardUiLanguage,
                                    "管理鍵盤設定",
                                    "Manage keyboard settings"
                                )
                            } else {
                                keyboardInterfaceText(
                                    keyboardUiLanguage,
                                    "前往設定啟用",
                                    "Go to settings to enable"
                                )
                            }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = keyboardInterfaceText(
                            keyboardUiLanguage,
                            "2. 選擇鍵盤",
                            "2. Choose the keyboard"
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when {
                            isKeyboardSelected -> keyboardInterfaceText(
                                keyboardUiLanguage,
                                "狀態：AI 鍵盤目前已選用",
                                "Status: AI Keyboard is selected"
                            )
                            isKeyboardEnabled -> keyboardInterfaceText(
                                keyboardUiLanguage,
                                "狀態：已啟用，尚未選用",
                                "Status: Enabled, but not selected"
                            )
                            else -> keyboardInterfaceText(
                                keyboardUiLanguage,
                                "請先完成步驟 1",
                                "Complete step 1 first"
                            )
                        }
                    )
                    Button(
                        onClick = onChooseKeyboard,
                        enabled = isKeyboardEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (isKeyboardSelected) {
                                keyboardInterfaceText(
                                    keyboardUiLanguage,
                                    "重新選擇鍵盤",
                                    "Choose keyboard again"
                                )
                            } else {
                                keyboardInterfaceText(
                                    keyboardUiLanguage,
                                    "選擇 AI 鍵盤",
                                    "Choose AI Keyboard"
                                )
                            }
                        )
                    }
                }
            }

            LocalModelSelector(
                interfaceLanguage = keyboardUiLanguage,
                onModelSelected = onSelectModel
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "啟用 AI 情境預測",
                            "Enable AI scenario suggestions"
                        )
                    )
                    Switch(
                        checked = isAiScenarioPredictionEnabled,
                        onCheckedChange = onAiScenarioPredictionChange
                    )
                }
            }
            ModelStatusCard(
                statusText = modelStatusText,
                isInitializing = isModelInitializing,
                interfaceLanguage = keyboardUiLanguage
            )

            Text(
                text = keyboardInterfaceText(
                    keyboardUiLanguage,
                    "Android 會要求使用者自行在系統設定中啟用並選擇輸入法；本 App 不會代替使用者更改此設定。",
                    "Android requires you to enable and select the input method in system settings. This app will not change that setting for you."
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = keyboardInterfaceText(
                            keyboardUiLanguage,
                            "隱私與資料管理",
                            "Privacy and data"
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = keyboardInterfaceText(
                            keyboardUiLanguage,
                            "預設不連網、不記錄輸入內容；情境回覆與預測都在本機完成。",
                            "Offline by default; typed content is not recorded. Scenario replies and predictions run on-device."
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onClearUserDictionary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            keyboardInterfaceText(
                                keyboardUiLanguage,
                                "清除本機使用者詞庫",
                                "Clear local user dictionary"
                            )
                        )
                    }
                }
            }
            OutlinedTextField(
                value = sampleText,
                onValueChange = { sampleText = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "測試鍵盤輸入",
                            "Test keyboard input"
                        )
                    )
                },
                supportingText = {
                    Text(
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "點選此欄位後，可從系統鍵盤切換器選擇 AI 鍵盤。",
                            "Tap this field, then choose AI Keyboard from the system keyboard picker."
                        )
                    )
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = stringResource(R.string.usage_disclaimer_notes_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.usage_disclaimer_notes_body),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = stringResource(R.string.about_app_title),
                style = MaterialTheme.typography.titleMedium
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.about_app_version,
                            packageInfo.versionName.orEmpty(),
                            PackageInfoCompat.getLongVersionCode(packageInfo)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { showPrivacyUsage = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.privacy_usage_button))
                    }
                    Button(
                        onClick = { showLicenses = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.copyright_source_button))
                    }
                }
            }
        }
    }

    if (isModelLoading || modelLoadError != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isModelLoading) onDismissModelLoadError()
            },
            title = {
                Text(
                    if (isModelLoading) {
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "正在驗證並載入模型...",
                            "Verifying and loading model..."
                        )
                    } else {
                        keyboardInterfaceText(
                            keyboardUiLanguage,
                            "AI 暫時不可用",
                            "AI temporarily unavailable"
                        )
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isModelLoading) {
                        CircularProgressIndicator()
                    }
                    Text(
                        modelLoadError ?: keyboardInterfaceText(
                            keyboardUiLanguage,
                            "正在檢查 filesDir/models 的模型檔案並初始化 AiEngine，請稍候。",
                            "Checking the model in filesDir/models and initializing AiEngine..."
                        )
                    )
                }
            },
            confirmButton = {
                if (!isModelLoading) {
                    TextButton(onClick = onDismissModelLoadError) {
                        Text(keyboardInterfaceText(keyboardUiLanguage, "關閉", "Close"))
                    }
                }
            }
        )
    }

    if (showLicenses) {
        SourceLicenseDialog(
            interfaceLanguage = keyboardUiLanguage,
            onDismiss = { showLicenses = false }
        )
    }
    if (showPrivacyUsage) {
        PrivacyUsageDialog(onDismiss = { showPrivacyUsage = false })
    }
}

@Composable
private fun PrivacyUsageDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.privacy_usage_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.privacy_usage_details),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.usage_disclaimer_statement),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.about_app_close))
            }
        }
    )
}

@Composable
private fun SourceLicenseDialog(
    interfaceLanguage: KeyboardUiLanguage,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.copyright_source_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 520.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = scrollState.value > 0,
                        onClick = {
                            coroutineScope.launch { scrollState.animateScrollTo(0) }
                        }
                    ) {
                        Text(keyboardInterfaceText(interfaceLanguage, "↑ 上滑", "↑ Up"))
                    }
                    TextButton(
                        enabled = scrollState.value < scrollState.maxValue,
                        onClick = {
                            coroutineScope.launch {
                                scrollState.animateScrollTo(scrollState.maxValue)
                            }
                        }
                    ) {
                        Text(keyboardInterfaceText(interfaceLanguage, "↓ 下滑", "↓ Down"))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = stringResource(R.string.copyright_source_notice),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(keyboardInterfaceText(interfaceLanguage, "關閉", "Close"))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalModelSelector(
    interfaceLanguage: KeyboardUiLanguage,
    onModelSelected: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("") }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = {},
            readOnly = true,
            label = {
                Text(
                    keyboardInterfaceText(
                        interfaceLanguage,
                        "鍵盤模型",
                        "Keyboard model"
                    )
                )
            },
            placeholder = {
                Text(
                    keyboardInterfaceText(
                        interfaceLanguage,
                        "選擇模型",
                        "Select a model"
                    )
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        keyboardInterfaceText(
                            interfaceLanguage,
                            "本機模型",
                            "Local models"
                        )
                    )
                },
                onClick = {},
                enabled = false
            )
            DropdownMenuItem(
                text = { Text("Gemma 3 1B IT Q4_K_M") },
                onClick = {
                    selectedModel = ModelInitializer.MODEL_FILE_NAME
                    expanded = false
                    onModelSelected()
                }
            )
        }
    }
}

@Composable
private fun ModelStatusCard(
    statusText: String,
    isInitializing: Boolean,
    interfaceLanguage: KeyboardUiLanguage
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = keyboardInterfaceText(
                    interfaceLanguage,
                    "本機模型狀態",
                    "Local model status"
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            if (isInitializing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
