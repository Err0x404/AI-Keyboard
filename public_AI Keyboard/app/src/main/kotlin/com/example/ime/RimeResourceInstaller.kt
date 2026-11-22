package com.example.ime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException

object RimeResourceInstaller {
    @Synchronized
    fun installResources(context: Context): File {
        val assetFiles = collectAssetFiles(context, ASSET_DIRECTORY)
        if (assetFiles.isEmpty()) {
            throw IOException("No Rime resources found in assets/$ASSET_DIRECTORY.")
        }

        val missingFiles = missingRequiredAssets(assetFiles)
        if (missingFiles.isNotEmpty()) {
            throw IOException(
                "Missing Rime assets: ${missingFiles.sorted().joinToString()}"
            )
        }

        val schemaTexts = REQUIRED_SCHEMAS.map { schemaId ->
            readAssetText(context, "$ASSET_DIRECTORY/$schemaId.schema.yaml")
        }
        val customConfig = readAssetText(context, "$ASSET_DIRECTORY/default.custom.yaml")
        val missingSchemaEntries = REQUIRED_SCHEMAS.filterNot { schemaId ->
            schemaEntryRegex(schemaId).containsMatchIn(customConfig)
        }
        if (missingSchemaEntries.isNotEmpty()) {
            throw IOException(
                "default.custom.yaml must select these schemas: " +
                    missingSchemaEntries.joinToString()
            )
        }
        schemaTexts.forEach { schemaText ->
            validateOpenCcResources(context, schemaText, assetFiles)
            validateSchemaDependencies(schemaText, assetFiles)
        }

        val destination = File(context.filesDir, ASSET_DIRECTORY)
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("Unable to create Rime resource directory: $destination")
        }
        val stamp = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .lastUpdateTime
            .toString()
        val stampFile = File(destination, ASSET_STAMP_FILE)
        val resourcesCurrent = destination.isDirectory &&
            stampFile.isFile &&
            stampFile.readText().trim() == stamp &&
            REQUIRED_ASSET_FILES.all { File(destination, it).isFile }
        if (!resourcesCurrent) {
            copyDirectory(context, ASSET_DIRECTORY, destination, "")
            stampFile.writeText(stamp)
        }
        return destination
    }

    fun simplificationOption(
        context: Context,
        schemaId: String = "bopomofo"
    ): String? {
        val schemaText = readAssetText(context, "$ASSET_DIRECTORY/$schemaId.schema.yaml")
        return simplificationOptionFromSchema(schemaText)
    }

    internal fun simplificationOptionFromSchema(schemaText: String): String? = when {
        SIMPLIFICATION_OPTION.containsMatchIn(schemaText) -> "simplification"
        ZH_HANS_OPTION.containsMatchIn(schemaText) -> "zh_hans"
        else -> null
    }

    internal fun missingRequiredAssets(assetFiles: Set<String>): Set<String> =
        REQUIRED_ASSET_FILES - assetFiles

    internal fun openCcDictionaryPaths(configText: String): Set<String> =
        OPENCC_DICTIONARY.findAll(configText)
            .map { normalizeOpenCcPath(it.groupValues[1]) }
            .toSet()

    private fun collectAssetFiles(context: Context, assetPath: String): Set<String> {
        val assetFiles = mutableSetOf<String>()
        collectAssetFiles(context, assetPath, "", assetFiles)
        return assetFiles
    }

    private fun collectAssetFiles(
        context: Context,
        assetPath: String,
        relativePath: String,
        assetFiles: MutableSet<String>
    ) {
        val entries = context.assets.list(assetPath).orEmpty().sorted()
        if (entries.isEmpty()) {
            if (relativePath.isNotEmpty()) assetFiles += relativePath
            return
        }

        entries.forEach { entry ->
            if (entry.isBlank() || entry == "." || entry == ".." ||
                entry.contains('/') || entry.contains('\\')
            ) {
                throw IOException("Invalid asset path entry: $entry")
            }
            val childAssetPath = "$assetPath/$entry"
            val childRelativePath = if (relativePath.isEmpty()) {
                entry
            } else {
                "$relativePath/$entry"
            }
            collectAssetFiles(context, childAssetPath, childRelativePath, assetFiles)
        }
    }

    private fun validateSchemaDependencies(schemaText: String, assetFiles: Set<String>) {
        val dependencies = DEPENDENCY_BLOCK.find(schemaText)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(DEPENDENCY_NAME::findAll)
            ?.map { it.groupValues[1] }
            .orEmpty()
        val dictionaryNames = DICTIONARY_NAME.findAll(schemaText)
            .map { it.groupValues[1] }
            .filter(String::isNotBlank)
            .toSet()
        val referencedFiles = dependencies.map { "$it.schema.yaml" } +
            dictionaryNames.map { "$it.dict.yaml" }
        val missingFiles = referencedFiles.toSet() - assetFiles
        if (missingFiles.isNotEmpty()) {
            throw IOException(
                "Missing schema dependencies: ${missingFiles.sorted().joinToString()}"
            )
        }
    }

    private fun validateOpenCcResources(
        context: Context,
        schemaText: String,
        assetFiles: Set<String>
    ) {
        val configPaths = OPENCC_CONFIG.findAll(schemaText)
            .map { normalizeOpenCcPath(it.groupValues[1]) }
            .toSet()
        configPaths.forEach { configPath ->
            if (configPath !in assetFiles) {
                throw IOException("Missing OpenCC configuration: $configPath")
            }
            val configText = readAssetText(context, "$ASSET_DIRECTORY/$configPath")
            val dictionaryPaths = openCcDictionaryPaths(configText)
            val missingDictionaries = dictionaryPaths - assetFiles
            if (missingDictionaries.isNotEmpty()) {
                throw IOException(
                    "Missing OpenCC dictionaries: ${missingDictionaries.sorted().joinToString()}"
                )
            }
        }
    }

    private fun normalizeOpenCcPath(path: String): String {
        if (path.isBlank() || path.split('/').any { it == "." || it == ".." }) {
            throw IOException("Invalid OpenCC resource path: $path")
        }
        return if (path.startsWith("opencc/")) path else "opencc/$path"
    }

    private fun readAssetText(context: Context, assetPath: String): String =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun copyDirectory(
        context: Context,
        assetPath: String,
        destinationRoot: File,
        relativePath: String
    ) {
        val entries = context.assets.list(assetPath).orEmpty().sorted()
        if (entries.isNotEmpty()) {
            val directory = if (relativePath.isEmpty()) {
                destinationRoot
            } else {
                resolveDestination(destinationRoot, relativePath)
            }
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Unable to create Rime resource directory: $directory")
            }
            entries.forEach { entry ->
                if (entry.isBlank() || entry == "." || entry == ".." ||
                    entry.contains('/') || entry.contains('\\')
                ) {
                    throw IOException("Invalid asset path entry: $entry")
                }
                val childRelativePath = if (relativePath.isEmpty()) {
                    entry
                } else {
                    "$relativePath/$entry"
                }
                copyDirectory(
                    context,
                    "$assetPath/$entry",
                    destinationRoot,
                    childRelativePath
                )
            }
            return
        }

        val destination = resolveDestination(destinationRoot, relativePath)
        if (destination.isDirectory) {
            throw IOException("Rime asset destination is a directory: $destination")
        }
        val parent = destination.parentFile
            ?: throw IOException("Invalid Rime asset destination: $destination")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Unable to create resource parent directory: $parent")
        }

        val temporary = File.createTempFile("rime-", ".tmp", parent)
        try {
            context.assets.open(assetPath).use { input ->
                temporary.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            try {
                Os.rename(temporary.absolutePath, destination.absolutePath)
            } catch (error: ErrnoException) {
                throw IOException("Unable to install Rime resource: $destination", error)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun resolveDestination(root: File, relativePath: String): File {
        if (relativePath.isBlank() ||
            relativePath.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw IOException("Invalid Rime resource path: $relativePath")
        }
        val canonicalRoot = root.canonicalFile
        val destination = File(canonicalRoot, relativePath.replace('/', File.separatorChar))
            .canonicalFile
        if (!destination.path.startsWith(canonicalRoot.path + File.separator)) {
            throw IOException("Rime resource path escapes its destination: $relativePath")
        }
        return destination
    }

    private const val ASSET_DIRECTORY = "rime"
    private const val ASSET_STAMP_FILE = ".asset-stamp"
    private val REQUIRED_ASSET_FILES = setOf(
        "default.custom.yaml",
        "default.yaml",
        "key_bindings.yaml",
        "punctuation.yaml",
        "symbols.yaml",
        "essay.txt",
        "bopomofo.schema.yaml",
        "zhuyin.yaml",
        "LICENSE-LGPL-3.0.txt",
        "terra_pinyin.schema.yaml",
        "terra_pinyin.dict.yaml",
        "opencc/COPYRIGHT.OpenCC"
    )
    private val REQUIRED_SCHEMAS = listOf("bopomofo", "terra_pinyin")
    private fun schemaEntryRegex(schemaId: String) =
        Regex("""(?m)^\s*-\s*schema:\s*${Regex.escape(schemaId)}\s*(?:#.*)?$""")
    private val SIMPLIFICATION_OPTION =
        Regex("""(?m)^\s*-\s*name:\s*simplification\b""")
    private val ZH_HANS_OPTION =
        Regex("""(?m)^\s*-\s*zh_hans\s*$""")
    private val DEPENDENCY_BLOCK =
        Regex("""(?ms)^\s*dependencies:\s*\r?\n((?:\s+-[^\r\n]*\r?\n)+)""")
    private val DEPENDENCY_NAME = Regex("""^\s*-\s*([A-Za-z0-9_-]+)\s*$""", RegexOption.MULTILINE)
    private val DICTIONARY_NAME =
        Regex("""(?m)^\s*dictionary:\s*([A-Za-z0-9_-]+)\s*(?:#.*)?$""")
    private val OPENCC_CONFIG =
        Regex("""(?m)^\s*opencc_config:\s*["']?([^"'\s#]+)["']?\s*(?:#.*)?$""")
    private val OPENCC_DICTIONARY =
        Regex(""""file"\s*:\s*"([^"]+\.ocd2)"""")
}
