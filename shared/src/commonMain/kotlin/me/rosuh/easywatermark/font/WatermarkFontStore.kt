package me.rosuh.easywatermark.font

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.random.Random

@Serializable
data class ImportedFontMetadata(
    val schemaVersion: Int = 1,
    val originalFileName: String,
    val displayName: String,
    val extension: String,
)

sealed class FontPublishOutcome {
    data class Added(val entry: FontEntry) : FontPublishOutcome()
    data class Duplicate(val entry: FontEntry) : FontPublishOutcome()
    data class Failed(val fileName: String, val reason: String) : FontPublishOutcome()
}

/**
 * Per-font immutable directories under `<app data>/watermark_fonts/`.
 * Directory name is the content SHA-256; metadata is the list index (ADR-0035).
 */
class WatermarkFontStore(
    private val fileSystem: FileSystem,
    val root: Path,
    private val limits: FontImportLimits = FontImportLimits.Default,
) {
    init {
        fileSystem.createDirectories(root)
        recoverOrphans()
    }

    fun recoverOrphans() {
        val children = runCatching { fileSystem.list(root) }.getOrDefault(emptyList())
        for (child in children) {
            val name = child.name
            if (name.startsWith(TEMP_PREFIX)) {
                runCatching { fileSystem.deleteRecursively(child, mustExist = false) }
            }
        }
    }

    fun storedCount(): Int =
        runCatching { fileSystem.list(root) }
            .getOrDefault(emptyList())
            .count { SHA256_DIR.matches(it.name) }

    fun listImported(): List<FontEntry> {
        val children = runCatching { fileSystem.list(root) }.getOrDefault(emptyList())
        return children
            .filter { SHA256_DIR.matches(it.name) }
            .map { dir ->
                val sha = dir.name
                val meta = readMetadata(sha)
                val fontPath = fontFile(dir)
                val available = fontPath != null && meta != null
                FontEntry(
                    ref = WatermarkFontRef.Imported(sha),
                    displayName = meta?.displayName
                        ?: meta?.originalFileName
                        ?: sha.take(8),
                    available = available,
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    fun readFontBytes(sha256: String): ByteArray? {
        val dir = root / sha256
        val file = fontFile(dir) ?: return null
        return runCatching { fileSystem.read(file) { readByteArray() } }.getOrNull()
    }

    fun fontPath(sha256: String): Path? {
        val dir = root / sha256
        return fontFile(dir)
    }

    fun metadata(sha256: String): ImportedFontMetadata? = readMetadata(sha256)

    /**
     * Stream [bytes] into a temp dir, hash, validate via [validate], then rename-publish.
     * [validate] must actually load the face; extension checks are not enough.
     */
    fun publishBytes(
        originalFileName: String,
        bytes: ByteArray,
        validate: (bytes: ByteArray, extension: String) -> ValidatedImportedFont,
    ): FontPublishOutcome {
        val extension = extensionOf(originalFileName)
        if (extension != "ttf" && extension != "otf") {
            return FontPublishOutcome.Failed(originalFileName, "Unsupported file type")
        }
        if (bytes.isEmpty()) {
            return FontPublishOutcome.Failed(originalFileName, "Empty file")
        }
        if (bytes.size.toLong() > limits.maxFileBytes) {
            return FontPublishOutcome.Failed(originalFileName, "File exceeds ${limits.maxFileBytes} bytes")
        }
        if (storedCount() >= limits.maxStored) {
            return FontPublishOutcome.Failed(originalFileName, "Imported font limit reached")
        }
        val tempDir = root / (TEMP_PREFIX + randomToken())
        try {
            fileSystem.createDirectories(tempDir)
            val tempFont = tempDir / "font.$extension"
            fileSystem.write(tempFont) {
                write(bytes)
            }
            val sha256 = bytes.toByteString().sha256().hex()
            val published = root / sha256
            if (fileSystem.exists(published)) {
                runCatching { fileSystem.deleteRecursively(tempDir, mustExist = false) }
                val complete = fontFile(published) != null && readMetadata(sha256) != null
                val existing = listImported().firstOrNull {
                    it.ref == WatermarkFontRef.Imported(sha256)
                } ?: FontEntry(
                    ref = WatermarkFontRef.Imported(sha256),
                    displayName = originalFileName,
                    available = complete,
                )
                return if (complete) {
                    FontPublishOutcome.Duplicate(existing)
                } else {
                    FontPublishOutcome.Failed(
                        originalFileName,
                        "Existing published font is damaged",
                    )
                }
            }
            val validated = try {
                validate(bytes, extension)
            } catch (t: Throwable) {
                runCatching { fileSystem.deleteRecursively(tempDir, mustExist = false) }
                return FontPublishOutcome.Failed(
                    originalFileName,
                    t.message?.takeIf { it.isNotBlank() } ?: "Font could not be loaded",
                )
            }
            val metadata = ImportedFontMetadata(
                originalFileName = originalFileName,
                displayName = validated.displayName.ifBlank { basename(originalFileName) },
                extension = extension,
            )
            fileSystem.write(tempDir / METADATA_FILE) {
                writeUtf8(metadataJson.encodeToString(metadata))
            }
            fileSystem.atomicMove(tempDir, published)
            return FontPublishOutcome.Added(
                FontEntry(
                    ref = WatermarkFontRef.Imported(sha256),
                    displayName = metadata.displayName,
                ),
            )
        } catch (t: Throwable) {
            runCatching { fileSystem.deleteRecursively(tempDir, mustExist = false) }
            return FontPublishOutcome.Failed(
                originalFileName,
                t.message?.takeIf { it.isNotBlank() } ?: "Import failed",
            )
        }
    }

    fun cleanupTemp(tempDir: Path) {
        runCatching { fileSystem.deleteRecursively(tempDir, mustExist = false) }
    }

    private fun readMetadata(sha256: String): ImportedFontMetadata? {
        val path = root / sha256 / METADATA_FILE
        if (!fileSystem.exists(path)) return null
        return runCatching {
            val raw = fileSystem.read(path) { readUtf8() }
            metadataJson.decodeFromString<ImportedFontMetadata>(raw)
        }.getOrNull()
    }

    private fun fontFile(dir: Path): Path? {
        val ttf = dir / "font.ttf"
        val otf = dir / "font.otf"
        return when {
            fileSystem.exists(ttf) -> ttf
            fileSystem.exists(otf) -> otf
            else -> null
        }
    }

    companion object {
        const val DIR_NAME: String = "watermark_fonts"
        const val METADATA_FILE: String = "metadata.json"
        const val TEMP_PREFIX: String = ".import-"
        private val SHA256_DIR = Regex("^[0-9a-f]{64}$")
        private val metadataJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun extensionOf(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            if (dot < 0 || dot == fileName.lastIndex) return ""
            return fileName.substring(dot + 1).lowercase()
        }

        fun basename(fileName: String): String {
            val slash = fileName.replace('\\', '/').substringAfterLast('/')
            val dot = slash.lastIndexOf('.')
            return if (dot > 0) slash.substring(0, dot) else slash
        }

        fun isFontFileName(fileName: String): Boolean {
            val ext = extensionOf(fileName)
            return ext == "ttf" || ext == "otf"
        }

        private fun randomToken(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(12) {
                repeat(12) { append(alphabet[Random.nextInt(alphabet.length)]) }
            }
        }
    }
}

data class ValidatedImportedFont(
    val displayName: String,
    val capability: FontStyleCapability,
)
