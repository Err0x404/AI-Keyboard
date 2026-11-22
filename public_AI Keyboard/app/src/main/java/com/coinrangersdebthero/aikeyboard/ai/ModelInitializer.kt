package com.coinrangersdebthero.aikeyboard.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

enum class ModelInitializationState {
    CHECKING,
    READY,
    ERROR
}

data class ModelInitializationStatus(
    val state: ModelInitializationState,
    val errorMessage: String? = null
)

object ModelInitializer {
    const val MODEL_FILE_NAME = "gemma-3-1b-it.Q4_K_M.gguf"
    const val MODEL_DIRECTORY = "models"

    private val initializationMutex = Mutex()
    private val mutableStatus = MutableStateFlow(
        ModelInitializationStatus(ModelInitializationState.CHECKING)
    )
    val status: StateFlow<ModelInitializationStatus> = mutableStatus.asStateFlow()

    fun modelFile(context: Context): File =
        File(File(context.filesDir, MODEL_DIRECTORY), MODEL_FILE_NAME)

    fun isModelReady(context: Context): Boolean = isGgufFile(modelFile(context))

    suspend fun initialize(context: Context): File? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        initializationMutex.withLock {
            mutableStatus.value = ModelInitializationStatus(ModelInitializationState.CHECKING)
            try {
                val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)
                if (!modelDirectory.isDirectory && !modelDirectory.mkdirs()) {
                    throw IOException("Could not create model directory: $modelDirectory")
                }

                val modelFile = modelFile(appContext)
                if (!isGgufFile(modelFile)) {
                    val assetPath = bundledModelAssetPath(appContext)
                    if (assetPath == null) {
                        throw IOException(
                            "Bundled model asset $MODEL_FILE_NAME is missing. " +
                                "Add it to the configured models asset directory."
                        )
                    }
                    appContext.assets.open(assetPath).use { input ->
                        copyBundledModel(input, modelFile)
                    }
                }

                mutableStatus.value = ModelInitializationStatus(ModelInitializationState.READY)
                modelFile
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableStatus.value = ModelInitializationStatus(
                    state = ModelInitializationState.ERROR,
                    errorMessage = error.localizedMessage
                )
                null
            }
        }
    }

    private fun bundledModelAssetPath(context: Context): String? {
        val nestedAsset = "$MODEL_DIRECTORY/$MODEL_FILE_NAME"
        if (MODEL_FILE_NAME in context.assets.list(MODEL_DIRECTORY).orEmpty()) {
            return nestedAsset
        }
        return MODEL_FILE_NAME.takeIf {
            it in context.assets.list("").orEmpty()
        }
    }

    internal fun copyBundledModel(input: InputStream, destination: File) {
        val parent = destination.parentFile
            ?: throw IOException("Invalid model destination: $destination")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create model directory: $parent")
        }

        val temporary = File.createTempFile("model-", ".tmp", parent)
        try {
            temporary.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
            if (!isGgufFile(temporary)) {
                throw IOException("Bundled model is empty or has an invalid GGUF header.")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace invalid model file: $destination")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Could not move model into place: $destination")
            }
        } finally {
            temporary.delete()
        }
    }

    private fun isGgufFile(file: File): Boolean {
        if (!file.isFile || file.length() < GGUF_MAGIC.size) return false
        return file.inputStream().use { input ->
            val header = ByteArray(GGUF_MAGIC.size)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) return@use false
                offset += read
            }
            header.contentEquals(GGUF_MAGIC)
        }
    }

    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
}
