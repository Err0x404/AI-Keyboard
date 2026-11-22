package com.coinrangersdebthero.aikeyboard.ai

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.coinrangersdebthero.aikeyboard.ScenarioOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.util.Locale

object AiEngine {
    private const val TAG = "AiEngine"

    /**
     * Gemma 3 1B supports long contexts, but on-device we only need enough room for
     * the scenario instructions plus the generated reply. 1024 tokens filled up
     * after a couple of requests and every later completion failed silently.
     */
    private const val CONTEXT_LENGTH = 2048
    private const val SUGGESTION_TIMEOUT_MS = 40 * 1000L
    private const val SUGGESTION_MAX_TOKENS = 48
    private const val SCENARIO_TIMEOUT_MS = 90 * 1000L
    private const val MAX_SUGGESTIONS = 5
    private const val SCENARIO_MAX_TOKENS = 160
    private const val MAX_TOKENS = 16
    private const val DEFAULT_TEMPERATURE = 0.2
    private const val CREATIVE_TEMPERATURE = 0.75
    private const val MAX_CANDIDATE_LENGTH = 80

    /**
     * Gemma 3 instruct models use the `<start_of_turn>` / `<end_of_turn>` control
     * tokens. The previous ChatML stop tokens were never produced by the model, so
     * generation ran until it hit the token limit and the reply was truncated or
     * empty.
     */
    private val STOP_SEQUENCES = listOf(
        "<end_of_turn>",
        "<start_of_turn>",
        "<eos>",
        "<|im_end|>",
        "<|im_start|>"
    )

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var activeGeneration: StringBuilder? = null

    private var llama: LlamaAndroid? = null
    private var modelContextId: Int? = null

    fun initialize(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    fun isModelLoaded(): Boolean = llama != null && modelContextId != null

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        val context = applicationContext
            ?: throw IllegalStateException("AiEngine.initialize(context) must be called first.")
        operationMutex.withLock {
            ensureModelLoaded(context)
        }
    }

    suspend fun suggest(contextText: String): List<String> {
        if (contextText.isBlank()) return emptyList()
        val response = generate(
            prompt = buildSuggestionPrompt(contextText.takeLast(80)),
            timeoutMs = SUGGESTION_TIMEOUT_MS,
            maxTokens = SUGGESTION_MAX_TOKENS,
            temperature = CREATIVE_TEMPERATURE
        )
        return parseSuggestions(response)
    }

    suspend fun predictNextWord(currentInputText: String): String? {
        if (!currentInputText.matches(Regex("[a-zA-Z]+"))) return null
        val prefix = currentInputText.lowercase(Locale.ROOT)
        val response = generate(
            prompt = buildNextWordPrompt(prefix),
            timeoutMs = SUGGESTION_TIMEOUT_MS,
            maxTokens = MAX_TOKENS
        )
        return extractCompletedWord(response, prefix)
    }

    suspend fun predictFollowingWord(contextText: String): String? {
        if (contextText.isBlank()) return null
        val context = contextText.takeLast(50)
        val response = generate(
            prompt = buildFollowingWordPrompt(context),
            timeoutMs = SUGGESTION_TIMEOUT_MS,
            maxTokens = MAX_TOKENS
        )
        return extractFirstWord(response)
    }

    suspend fun generateScenario(
        option: ScenarioOption,
        contextText: String,
        clipboardText: String?,
        replyInEnglish: Boolean = false
    ): List<String> {
        if (contextText.isBlank() && clipboardText.isNullOrBlank()) return emptyList()
        val prompt = buildScenarioPrompt(
            option = option,
            contextText = contextText.takeLast(160),
            clipboardText = clipboardText.orEmpty().takeLast(160),
            replyInEnglish = replyInEnglish
        )
        return parseSuggestions(
            generate(
                prompt = prompt,
                timeoutMs = SCENARIO_TIMEOUT_MS,
                maxTokens = SCENARIO_MAX_TOKENS,
                temperature = CREATIVE_TEMPERATURE
            )
        )
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            resetEngine()
        }
    }

    private suspend fun generate(
        prompt: String,
        timeoutMs: Long,
        maxTokens: Int = MAX_TOKENS,
        temperature: Double = DEFAULT_TEMPERATURE
    ): String =
        withContext(Dispatchers.IO) {
            val context = applicationContext
                ?: throw IllegalStateException("AiEngine.initialize(context) must be called first.")

            operationMutex.withLock {
                try {
                    runCompletion(context, prompt, timeoutMs, maxTokens, temperature)
                } catch (error: CancellationException) {
                    throw error
                } catch (first: Exception) {
                    // A stale or exhausted llama context (or a half-initialised
                    // engine after a failed warm-up) used to leave every later
                    // request empty. Rebuild the engine once and retry so the
                    // keyboard recovers instead of silently producing nothing.
                    Log.w(TAG, "Generation failed; rebuilding the local model once.", first)
                    resetEngine()
                    try {
                        runCompletion(context, prompt, timeoutMs, maxTokens, temperature)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (second: Exception) {
                        resetEngine()
                        throw IllegalStateException(
                            second.localizedMessage ?: "本機模型推論失敗，請稍後再試。",
                            second
                        )
                    }
                }
            }
        }

    private suspend fun runCompletion(
        context: Context,
        prompt: String,
        timeoutMs: Long,
        maxTokens: Int,
        temperature: Double
    ): String {
        val engine = ensureModelLoaded(context)
        val contextId = checkNotNull(modelContextId)
        val output = StringBuilder()
        activeGeneration = output
        val completion = engineScope.async {
            engine.launchCompletion(
                contextId,
                buildCompletionParameters(prompt, maxTokens, temperature)
            )
        }

        try {
            // launchCompletion only streams tokens when
            // "emit_partial_completion" is enabled, but it also returns the
            // full text. Prefer the callback while it is running, then fall
            // back to the returned payload.
            val result = withTimeout(timeoutMs) { completion.await() }
            val streamed = synchronized(output) { output.toString() }
            val returned = (result?.get("text") as? String).orEmpty()
            return cleanResponse(returned.ifBlank { streamed })
        } catch (error: TimeoutCancellationException) {
            completion.cancel()
            withContext(NonCancellable + Dispatchers.IO) {
                engine.stopCompletion(contextId)
            }
            throw IllegalStateException("本機模型推論逾時。", error)
        } catch (error: CancellationException) {
            completion.cancel()
            withContext(NonCancellable + Dispatchers.IO) {
                engine.stopCompletion(contextId)
            }
            throw error
        } finally {
            activeGeneration = null
        }
    }

    /** Drops the current llama context so the next request starts from a clean state. */
    private fun resetEngine() {
        val engine = llama
        val contextId = modelContextId
        llama = null
        modelContextId = null
        if (engine != null && contextId != null) {
            try {
                engine.releaseContext(contextId)
            } catch (error: Exception) {
                Log.w(TAG, "Releasing the llama context failed.", error)
            }
        }
    }

    private fun ensureModelLoaded(context: Context): LlamaAndroid {
        val existingEngine = llama
        if (existingEngine != null && modelContextId != null) {
            return existingEngine
        }

        val modelFile = ModelInitializer.modelFile(context)
        if (!modelFile.isFile || modelFile.length() == 0L) {
            throw IllegalStateException(
                "The local model is not ready. Open the app and wait for model initialization."
            )
        }

        val descriptor = context.contentResolver.openFileDescriptor(
            Uri.fromFile(modelFile),
            "r"
        ) ?: throw IllegalStateException("Could not open the local model file.")
        val modelFd = descriptor.use(ParcelFileDescriptor::detachFd)
        val engine = LlamaAndroid(context.contentResolver)
        var startedContextId: Int? = null
        try {
            val result = engine.startEngine(
                buildModelParameters(modelFile, modelFd)
            ) { token ->
                activeGeneration?.let { output ->
                    synchronized(output) {
                        output.append(token)
                    }
                }
            } ?: throw IllegalStateException(
                "本機模型載入失敗，請確認裝置可用記憶體是否足夠後再試。"
            )
            val contextId = (result["contextId"] as? Number)?.toInt()
                ?: throw IllegalStateException("The model loader did not return a context ID.")
            startedContextId = contextId
            llama = engine
            modelContextId = contextId
            return engine
        } catch (error: Exception) {
            startedContextId?.let(engine::releaseContext)
            throw error
        }
    }

    internal fun buildNextWordPrompt(currentInputText: String): String =
        buildChatPrompt(
            systemPrompt = "You complete a single English word. Keep the already typed " +
                "letters unchanged and output only the completed word.",
            userContent = currentInputText
        )

    internal fun buildFollowingWordPrompt(contextText: String): String =
        buildChatPrompt(
            systemPrompt = "Predict the single most likely next English word. " +
                "Do not rewrite, translate or explain. Output only that word.",
            userContent = contextText.takeLast(50)
        )

    internal fun buildCompletionParameters(
        prompt: String,
        maxTokens: Int,
        temperature: Double = DEFAULT_TEMPERATURE
    ): Map<String, Any> {
        require(maxTokens > 0) { "maxTokens must be positive." }
        return mapOf(
            "prompt" to prompt,
            "n_predict" to maxTokens,
            "temperature" to temperature,
            "top_k" to 40,
            "top_p" to 0.92,
            "min_p" to 0.05,
            "penalty_last_n" to 128,
            "penalty_repeat" to 1.12,
            "penalty_freq" to 0.0,
            "penalty_present" to 0.0,
            "n_threads" to 4,
            "stop" to STOP_SEQUENCES,
            // Without this flag the native layer never invokes the token callback.
            "emit_partial_completion" to true
        )
    }

    private fun buildModelParameters(modelFile: File, modelFd: Int): Map<String, Any> =
        mapOf(
            "model" to Uri.fromFile(modelFile).toString(),
            "model_fd" to modelFd,
            // Memory-mapping keeps the 806 MB Gemma file off the heap. Copying it
            // with use_mmap=false took long enough to trip the first-request
            // timeout on some devices, which looked like "the model never answers".
            "use_mmap" to true,
            "use_mlock" to false,
            "n_ctx" to CONTEXT_LENGTH,
            "embedding" to false,
            "n_batch" to 256,
            "n_threads" to 4,
            "n_gpu_layers" to 0,
            "vocab_only" to false,
            "lora" to "",
            "lora_scaled" to 1.0,
            "rope_freq_base" to 0.0,
            "rope_freq_scale" to 0.0
        )

    internal fun buildSuggestionPrompt(contextText: String): String = buildChatPrompt(
        systemPrompt = "You are an input method text completion engine. Continue the " +
            "user's text naturally in the same language. Output up to 3 short " +
            "continuations, one per line, with no numbering, quotes or explanation.",
        userContent = contextText.takeLast(80)
    )

    internal fun buildScenarioPrompt(
        option: ScenarioOption,
        contextText: String,
        clipboardText: String,
        replyInEnglish: Boolean = false
    ): String {
        val hasSafetyContext = hasSafetySensitiveContext(contextText, clipboardText)
        val responseRules = if (replyInEnglish) {
            "Output exactly 3 concise, ready-to-send replies in English, one per line. " +
                "Do not number, quote, or explain. Follow the selected scenario's intent, " +
                "but keep every reply in English."
        } else {
            "請使用繁體中文，只輸出 3 則可以直接送出的短回覆，每行一則；" +
                "不要編號、不要引號、不要解釋。"
        }
        val relevanceRules = if (replyInEnglish) {
            "Base every reply on the supplied input and clipboard context. Stay directly " +
                "relevant; do not use unrelated templates, invent details, or assume outcomes."
        } else {
            "每則回覆都必須直接根據輸入文字與剪貼簿脈絡，切題回應；不可套用無關罐頭句、" +
                "捏造細節或臆測結果。"
        }
        val safetyRules = if (!hasSafetyContext) {
            ""
        } else if (replyInEnglish) {
            " The context may describe a dangerous, accidental, falling, or faulty situation. " +
                "Stay calm, objective and on-topic; check immediate safety or ask a concise " +
                "factual question when appropriate. Do not express excitement, call it good " +
                "news, or assume anyone is safe or injured."
        } else {
            "內容可能涉及高處、墜落、意外、故障或危險。請保持冷靜、客觀、切題；必要時先確認" +
                "人員安全或詢問事實。禁止使用「好消息」或「感到興奮」等不合時宜的語氣，也不得" +
                "臆測事故結果。"
        }
        return buildChatPrompt(
            systemPrompt = "${option.systemPrompt}\n$responseRules\n$relevanceRules$safetyRules",
            userContent = buildScenarioContext(contextText, clipboardText)
        )
    }

    internal fun hasSafetySensitiveContext(
        contextText: String,
        clipboardText: String?
    ): Boolean {
        val context = "$contextText\n${clipboardText.orEmpty()}".lowercase(Locale.ROOT)
        return CHINESE_SAFETY_MARKERS.any(context::contains) ||
            ENGLISH_SAFETY_MARKER.containsMatchIn(context)
    }

    private val CHINESE_SAFETY_MARKERS = listOf(
        "高處", "高处", "樓頂", "楼顶", "垂落", "垂下", "墜落", "坠落",
        "掉落", "跌落", "摔", "意外", "事故", "故障", "危險", "危险",
        "異常", "异常", "緊急", "紧急", "失控", "斷裂", "断裂", "漏水",
        "起火", "冒煙", "冒烟", "損壞", "损坏"
    )
    private val ENGLISH_SAFETY_MARKER = Regex(
        """\b(fall|fell|fallen|falling|drop|dropped|dropping|accident|malfunction|fault|danger|dangerous|unsafe|hazard|emergency|broken|abnormal|damage|leak|fire|smoke|collapse|crash)\b"""
    )

    internal fun buildScenarioContext(contextText: String, clipboardText: String): String {
        val input = contextText.trim()
        val clipboard = clipboardText.trim()
        return when {
            input.isNotEmpty() && clipboard.isNotEmpty() ->
                "根據 [$input] [$clipboard]"
            input.isNotEmpty() -> "根據 [$input]"
            clipboard.isNotEmpty() -> "根據 [$clipboard]"
            else -> "根據 []"
        }
    }

    /** Gemma 3 instruction format. */
    private fun buildChatPrompt(systemPrompt: String, userContent: String): String =
        buildString {
            append("<start_of_turn>user\n")
            append(systemPrompt)
            append("\n\n")
            append(userContent)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }

    internal fun parseSuggestions(response: String): List<String> {
        return response
            .lineSequence()
            .map { line ->
                line.replace("<start_of_turn>model", "")
                    .replace("<start_of_turn>user", "")
                    .replace("<end_of_turn>", "")
                    .replace("<eos>", "")
                    .replace(Regex("^\\s*(?:[-*•]|\\d+[.)、,])\\s*"), "")
                    .removeSurrounding("\"")
                    .removeSurrounding("「", "」")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("以下") || it.startsWith("Here") }
            .map { it.take(MAX_CANDIDATE_LENGTH) }
            .distinct()
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    private fun cleanResponse(response: String): String {
        var cleaned = response
        if (cleaned.contains("<start_of_turn>model")) {
            cleaned = cleaned.substringAfter("<start_of_turn>model\n", "").ifBlank {
                cleaned.substringAfter("<start_of_turn>model", "")
            }
        }
        if (cleaned.contains("<|im_start|>assistant")) {
            cleaned = cleaned.substringAfter("<|im_start|>assistant\n", "").ifBlank {
                cleaned.substringAfter("<|im_start|>assistant", "")
            }
        }
        return cleaned
            .substringBefore("<end_of_turn>")
            .substringBefore("<start_of_turn>")
            .substringBefore("<|im_end|>")
            .substringBefore("<|im_start|>")
            .replace("<eos>", "")
            .trim()
    }

    internal fun extractCompletedWord(response: String, currentInputText: String): String? {
        val prefix = currentInputText.lowercase(Locale.ROOT)
        val firstWord = extractFirstWord(response) ?: return null
        val completedWord = if (firstWord.startsWith(prefix)) firstWord else prefix + firstWord
        return completedWord.takeIf {
            it.length > prefix.length && it.all { character -> character in 'a'..'z' }
        }
    }

    internal fun extractFirstWord(response: String): String? =
        Regex("[A-Za-z]+")
            .find(cleanResponse(response))
            ?.value
            ?.lowercase(Locale.ROOT)
}
