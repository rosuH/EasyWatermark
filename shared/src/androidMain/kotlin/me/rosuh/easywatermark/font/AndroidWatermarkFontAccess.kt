package me.rosuh.easywatermark.font

import android.content.ContentResolver
import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.SystemFonts
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.source
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AndroidWatermarkFontAccess(
    private val context: Context,
    private val limits: FontImportLimits = FontImportLimits.Default,
) : WatermarkFontAccess {

    private val store = WatermarkFontStore(
        fileSystem = FileSystem.SYSTEM,
        root = File(context.filesDir, WatermarkFontStore.DIR_NAME).toOkioPath(),
        limits = limits,
    )
    private val importMutex = Mutex()
    private val resolveCache = ConcurrentHashMap<String, FontResolution>()

    val systemListRestricted: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun recoverOrphans() {
        store.recoverOrphans()
    }

    override suspend fun listSystemFonts(): List<FontEntry> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listSystemFontsApi29()
        } else {
            GENERIC_FAMILIES.map { (key, label) ->
                FontEntry(
                    ref = WatermarkFontRef.System(PLATFORM, key),
                    displayName = label,
                )
            }
        }
    }

    override suspend fun listImportedFonts(): List<FontEntry> = withContext(Dispatchers.IO) {
        store.listImported()
    }

    override suspend fun resolve(ref: WatermarkFontRef): FontResolution = withContext(Dispatchers.IO) {
        when (ref) {
            WatermarkFontRef.Default -> FontResolution.Success(
                family = FontFamily.Default,
                supportedStyles = FontStyleCapability.All,
                displayName = "System default",
            )
            WatermarkFontRef.Unavailable -> FontResolution.Failure(
                reason = "Saved font is missing or unreadable",
                ref = ref,
            )
            is WatermarkFontRef.System,
            is WatermarkFontRef.Imported,
            -> {
                val key = ref.fingerprint()
                resolveCache[key]?.let { return@withContext it }
                val resolved = when (ref) {
                    is WatermarkFontRef.System -> resolveSystem(ref)
                    is WatermarkFontRef.Imported -> resolveImported(ref)
                    else -> FontResolution.Failure("Unsupported font", ref)
                }
                if (resolved is FontResolution.Success) {
                    resolveCache[key] = resolved
                }
                resolved
            }
        }
    }

    suspend fun importTree(treeUri: Uri, cancelled: () -> Boolean = { false }): FontImportResult {
        return importMutex.withLock {
            withContext(Dispatchers.IO) {
                val enumeration = collectFontCandidates(
                    context.contentResolver,
                    treeUri,
                    cancelled,
                )
                WatermarkFontImporter.importEnumerated(
                    store = store,
                    enumeration = enumeration,
                    limits = limits,
                    validate = ::validateImported,
                    cancelled = cancelled,
                )
            }
        }
    }

    private fun resolveSystem(ref: WatermarkFontRef.System): FontResolution {
        if (ref.platform != PLATFORM) {
            return FontResolution.Failure("Font belongs to another platform", ref)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolveSystemApi29(ref)
        } else {
            resolveGeneric(ref)
        }
    }

    private fun resolveGeneric(ref: WatermarkFontRef.System): FontResolution {
        val typeface = Typeface.create(ref.key, Typeface.NORMAL)
            ?: return FontResolution.Failure("System family is unavailable", ref)
        val label = GENERIC_FAMILIES[ref.key] ?: ref.key
        return FontResolution.Success(
            family = composeFamily(typeface),
            supportedStyles = FontStyleCapability.All,
            displayName = label,
        )
    }

    private fun resolveSystemApi29(ref: WatermarkFontRef.System): FontResolution {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return FontResolution.Failure("System font requires Android 10", ref)
        }
        val parsed = AndroidSystemFontKey.parse(ref.key)
            ?: return FontResolution.Failure("Invalid system font key", ref)
        val file = File(parsed.path)
        if (!file.isFile) {
            return FontResolution.Failure("System font file is gone", ref)
        }
        val typeface = buildTypeface(file, parsed.ttcIndex, parsed.variation)
            ?: return FontResolution.Failure("Could not load system font", ref)
        return FontResolution.Success(
            family = composeFamily(typeface),
            supportedStyles = FontStyleCapability.ofFace(typeface.isBold, typeface.isItalic),
            displayName = parsed.displayName,
        )
    }

    private fun resolveImported(ref: WatermarkFontRef.Imported): FontResolution {
        val path = store.fontPath(ref.sha256)
            ?: return FontResolution.Failure("Imported font is missing", ref)
        val file = path.toFile()
        if (!file.isFile) {
            return FontResolution.Failure("Imported font is missing", ref)
        }
        val typeface = typefaceFromFile(file)
            ?: return FontResolution.Failure("Imported font could not be loaded", ref)
        val meta = store.metadata(ref.sha256)
        return FontResolution.Success(
            family = composeFamily(typeface),
            supportedStyles = FontStyleCapability.ofFace(typeface.isBold, typeface.isItalic),
            displayName = meta?.displayName ?: file.nameWithoutExtension,
        )
    }

    private fun validateImported(bytes: ByteArray, extension: String): ValidatedImportedFont {
        val tmp = File(context.cacheDir, "ewm-font-validate-${System.nanoTime()}.$extension")
        try {
            tmp.writeBytes(bytes)
            val typeface = typefaceFromFile(tmp)
                ?: error("Font could not be loaded")
            return ValidatedImportedFont(
                displayName = "",
                capability = FontStyleCapability.ofFace(typeface.isBold, typeface.isItalic),
            )
        } finally {
            tmp.delete()
        }
    }

    private fun typefaceFromFile(file: File): Typeface? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Typeface.Builder(file).build()
        } else {
            Typeface.createFromFile(file)
        }
    }

    private fun buildTypeface(file: File, ttcIndex: Int, variation: String): Typeface? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Typeface.Builder(file).setTtcIndex(ttcIndex)
            if (variation.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setFontVariationSettings(variation)
            }
            builder.build()
        } else {
            Typeface.createFromFile(file)
        }
    }

    @Suppress("DEPRECATION")
    private fun composeFamily(typeface: Typeface): FontFamily = FontFamily(typeface)

    private fun listSystemFontsApi29(): List<FontEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val fonts = SystemFonts.getAvailableFonts()
        val entries = LinkedHashMap<String, FontEntry>()
        for (font in fonts) {
            val file = font.file ?: continue
            if (!file.isFile) continue
            val ttcIndex = font.ttcIndex
            val variation = variationSettings(font)
            val display = displayNameFor(font, file)
            val key = AndroidSystemFontKey(file.absolutePath, ttcIndex, variation, display).encode()
            entries.putIfAbsent(
                key,
                FontEntry(
                    ref = WatermarkFontRef.System(PLATFORM, key),
                    displayName = display,
                ),
            )
        }
        return entries.values.sortedBy { it.displayName.lowercase() }
    }

    private fun variationSettings(font: Font): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        val axes = font.axes ?: return ""
        if (axes.isEmpty()) return ""
        return axes.joinToString(",") { axis ->
            "'${axis.tag}' ${axis.styleValue}"
        }
    }

    private fun displayNameFor(font: Font, file: File): String {
        val style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) font.style else null
        val weight = style?.weight
        val italic = style?.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC
        val base = file.nameWithoutExtension.ifBlank { file.name }
        val extras = buildList {
            if (weight != null && weight != 400) add(weight.toString())
            if (italic) add("Italic")
            if (font.ttcIndex > 0) add("#${font.ttcIndex}")
        }
        return if (extras.isEmpty()) base else "$base (${extras.joinToString(" ")})"
    }

    private fun collectFontCandidates(
        resolver: ContentResolver,
        treeUri: Uri,
        cancelled: () -> Boolean,
    ): FontEnumerationResult {
        val budget = FontScanBudget(limits, cancelled)
        fun walk(documentId: String) {
            if (!budget.canContinue()) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val cursor = runCatching {
                resolver.query(children, projection, null, null, null)
            }.getOrNull() ?: return
            cursor.use { rows ->
                val idIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) return
                while (rows.moveToNext()) {
                    if (!budget.onVisit()) return
                    val id = rows.getString(idIdx) ?: continue
                    val name = rows.getString(nameIdx) ?: "font"
                    val mime = rows.getString(mimeIdx).orEmpty()
                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                        walk(id)
                    } else if (WatermarkFontStore.isFontFileName(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val size = if (sizeIdx >= 0 && !rows.isNull(sizeIdx)) rows.getLong(sizeIdx) else null
                        budget.offerCandidate(
                            FontImportCandidate(
                                fileName = name,
                                sizeBytes = size,
                                openSource = {
                                    val stream = resolver.openInputStream(uri)
                                        ?: error("Could not read $name")
                                    stream.source()
                                },
                            ),
                        )
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(treeUri))
        return budget.snapshot()
    }

    companion object {
        const val PLATFORM: String = "android"

        private val GENERIC_FAMILIES = linkedMapOf(
            "sans-serif" to "Sans Serif",
            "serif" to "Serif",
            "monospace" to "Monospace",
            "casual" to "Casual",
            "cursive" to "Cursive",
            "sans-serif-condensed" to "Sans Serif Condensed",
            "sans-serif-medium" to "Sans Serif Medium",
            "sans-serif-light" to "Sans Serif Light",
            "sans-serif-thin" to "Sans Serif Thin",
            "sans-serif-black" to "Sans Serif Black",
        )
    }
}

private data class AndroidSystemFontKey(
    val path: String,
    val ttcIndex: Int,
    val variation: String,
    val displayName: String,
) {
    fun encode(): String = listOf(path, ttcIndex.toString(), variation, displayName).joinToString(SEP)

    companion object {
        private const val SEP = "\u001f"
        fun parse(raw: String): AndroidSystemFontKey? {
            val parts = raw.split(SEP)
            if (parts.size < 4) return null
            val ttc = parts[1].toIntOrNull() ?: return null
            return AndroidSystemFontKey(
                path = parts[0],
                ttcIndex = ttc,
                variation = parts[2],
                displayName = parts.drop(3).joinToString(SEP),
            )
        }
    }
}
