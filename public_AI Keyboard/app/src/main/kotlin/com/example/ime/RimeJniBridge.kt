package com.example.ime

import android.content.Context
import android.util.Log
import java.io.File

object RimeJniBridge {
    const val SCHEMA_BOPOMOFO = "bopomofo"
    const val SCHEMA_PINYIN = "terra_pinyin"

    private const val TAG = "RimeJniBridge"

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var simplificationOption = "simplification"

    @Volatile
    var currentSchemaId = SCHEMA_BOPOMOFO
        private set

    fun initRime(context: Context): Boolean {
        applicationContext = context.applicationContext
        RimeResourceInstaller.installResources(context)
        simplificationOption = RimeResourceInstaller.simplificationOption(
            context,
            SCHEMA_BOPOMOFO
        ).orEmpty()
        currentSchemaId = SCHEMA_BOPOMOFO
        loadNativeLibrary()

        val sharedDataDir = File(context.filesDir, "rime")
        val userDataDir = File(sharedDataDir, "user")
        if (!userDataDir.isDirectory && !userDataDir.mkdirs()) {
            throw IllegalStateException("Unable to create Rime user data directory: $userDataDir")
        }

        return nativeInitRime(sharedDataDir.absolutePath, userDataDir.absolutePath).also {
            if (!it) Log.e(TAG, "Failed to initialize librime.")
        }
    }

    fun processKey(keyCode: Int, mask: Int = 0): Boolean =
        nativeProcessKey(keyCode, mask).also {
            if (!it) Log.e(TAG, "Failed to process key: $keyCode")
        }

    fun getCompositionAndCandidates(): String =
        nativeGetCompositionAndCandidates().orEmpty()

    fun selectCandidate(index: Int): Boolean {
        if (index < 0 || !libraryLoaded) return false
        return nativeSelectCandidate(index).also {
            if (!it) Log.e(TAG, "Failed to select Rime candidate at index: $index")
        }
    }

    fun selectSchema(schemaId: String): Boolean {
        if (schemaId != SCHEMA_BOPOMOFO && schemaId != SCHEMA_PINYIN) return false
        if (!libraryLoaded) return false
        val selected = nativeSelectSchema(schemaId).also {
            if (!it) Log.e(TAG, "Failed to select Rime schema: $schemaId")
        }
        if (selected) {
            currentSchemaId = schemaId
            simplificationOption = applicationContext
                ?.let { RimeResourceInstaller.simplificationOption(it, schemaId) }
                .orEmpty()
        }
        return selected
    }

    fun simplificationOptionName(): String? =
        simplificationOption.takeIf { it.isNotBlank() }

    fun setOption(option: String, value: Boolean): Boolean {
        if (option.isBlank()) return false
        if (option == "simplification" && simplificationOption.isBlank()) return false
        val nativeOption = mapOptionName(option, simplificationOption)
        return nativeSetOption(nativeOption, value).also {
            if (!it) Log.e(TAG, "Failed to set Rime option: $nativeOption")
        }
    }

    fun getOption(option: String): Boolean {
        if (option.isBlank()) return false
        if (option == "simplification" && simplificationOption.isBlank()) return false
        return nativeGetOption(mapOptionName(option, simplificationOption))
    }

    fun clearComposition() {
        if (libraryLoaded) nativeClearComposition()
    }

    fun destroyRime() {
        if (libraryLoaded) nativeDestroyRime()
    }

    @Synchronized
    private fun loadNativeLibrary() {
        if (libraryLoaded) return
        System.loadLibrary("rime_jni")
        libraryLoaded = true
    }

    private external fun nativeInitRime(sharedDataDir: String, userDataDir: String): Boolean
    private external fun nativeProcessKey(keyCode: Int, mask: Int): Boolean
    private external fun nativeSelectCandidate(index: Int): Boolean
    private external fun nativeSelectSchema(schemaId: String): Boolean
    private external fun nativeGetCompositionAndCandidates(): String?
    private external fun nativeSetOption(option: String, value: Boolean): Boolean
    private external fun nativeGetOption(option: String): Boolean
    private external fun nativeClearComposition()
    private external fun nativeDestroyRime()
}

internal fun mapOptionName(option: String, simplificationOption: String): String =
    if (option == "simplification") simplificationOption else option
