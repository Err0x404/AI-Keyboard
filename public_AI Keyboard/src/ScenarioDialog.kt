</think>

package com.example.keyboard

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

// 情境面板對話框
class ScenarioDialog(context: Activity) : Dialog(context) {
    private lateinit var categoryTextView: TextView
    private lateinit var optionTextView: TextView
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button

    private var selectedScenario: ScenarioOption? = null

    init {
        setContentView(LayoutInflater.from(context).inflate(R.layout.dialog_scenario, null))
        categoryTextView = findViewById(R.id.categoryTextView)
        optionTextView = findViewById(R.id.optionTextView)
        confirmButton = findViewById(R.id.confirmButton)
        cancelButton = findViewById(R.id.cancelButton)

        confirmButton.setOnClickListener {
            if (selectedScenario != null) {
                // 回傳選擇的 ScenarioOption
                dismiss()
            }
        }

        cancelButton.setOnClickListener {
            dismiss()
        }
    }

    fun showScenario(scenario: ScenarioCategory) {
        categoryTextView.text = scenario.title
        optionTextView.text = scenario.subOptions.joinToString("\n") {
            "• ${it.title}"
        }

        // 設定選項點擊監聽器
        optionTextView.setOnClickListener {
            // 這裡可以加入選項點擊的邏輯，例如選擇某個 ScenarioOption
        }
    }

    fun getSelectedScenario(): ScenarioOption? {
        return selectedScenario
    }

    // 在這裡插入 Compose UI 元件
    setContent {
        ScenarioList(scenarios = ScenarioConfig.getAllScenarios(), onScenarioSelected = { selectedOption ->
            // 生成 System Prompt
            val systemPrompt = selectedOption.systemPrompt

            // 獲取 currentInput 和 clipboardText
            val currentInput = "" // 這裡需要根據實際情況獲取 currentInput
            val clipboardText = "" // 這裡需要根據實際情況獲取 clipboardText

            // 將 currentInput 和 clipboardText 送入 AI 推論
        val completePrompt = "System Prompt: $systemPrompt\n\nCurrent Input: $currentInput\n\nClipboard Text: $clipboardText"

            // 在這裡調用 AI 推論模型
            val aiModel = AIModel(context)
            val generatedResponses = aiModel.generateResponse(completePrompt)

            // 顯示生成的回應
            AIResponseList(responses = generatedResponses, onInsertResponse = { response ->
                insertResponse(response)
        })
        })
    }

    // 實現 AI 推論邏輯
    fun generateAIResponses(systemPrompt: String, currentInput: String, clipboardText: String): List<String> {
        // 將 systemPrompt、currentInput 和 clipboardText 組合成一個完整的 Prompt
        val completePrompt = "System Prompt: $systemPrompt\n\nCurrent Input: $currentInput\n\nClipboard Text: $clipboardText"

        // 使用 AI 推論模型生成回應
        // 這裡需要根據實際情況實現 AI 推論邏輯
        // 例如，使用 AI 模型根據 completePrompt 生成回應
        return listOf("生成的回應 1", "生成的回應 2", "生成的回應 3")
}

    // 在這裡實現一鍵輸入功能
    fun insertResponse(response: String) {
        // 假設 currentInput 是當前輸入框的內容
        val currentInput = ""
        // 將 response 插入到 currentInput 中
        val updatedInput = currentInput + response
        // 這裡需要根據實際情況更新當前輸入框的內容
}
}

@Composable
fun ScenarioList(scenarios: List<ScenarioCategory>, onScenarioSelected: (ScenarioOption) -> Unit) {
    LazyColumn {
        items(scenarios) { category ->
            Text(text = category.title, style = MaterialTheme.typography.h6, modifier = Modifier.padding(16.dp))
            category.subOptions.forEach { option ->
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier
                        .padding(start = 24.dp, top = 8.dp)
                        .clickable {
                            onScenarioSelected(option)
                        }
                )
            }
        }
    }
}

@Composable
fun AIResponseList(responses: List<String>, onInsertResponse: (String) -> Unit) {
    LazyColumn {
        items(responses) { response ->
            Text(
                text = response,
                style = MaterialTheme.typography.body1,
                modifier = Modifier.padding(16.dp).clickable {
                    onInsertResponse(response)
                }
            )
        }
    }
}

