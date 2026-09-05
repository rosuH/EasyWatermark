package me.rosuh.easywatermark.font

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.render.IosByteArrayInterop
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIFont

@OptIn(ExperimentalForeignApi::class)
class IosWatermarkFontAccess(
    rootPath: String = defaultFontsRoot(),
    private val limits: FontImportLimits = FontImportLimits.Default,
) : WatermarkFontAccess {

    private val store = WatermarkFontStore(
        fileSystem = FileSystem.SYSTEM,
        root = rootPath.toPath(),
        limits = limits,
    )
    private val importMutex = Mutex()
    private val resolveMutex = Mutex()
    private val resolveCache = mutableMapOf<String, FontResolution>()

    override fun recoverOrphans() {
        store.recoverOrphans()
    }

    override suspend fun listSystemFonts(): List<FontEntry> = withContext(Dispatchers.Default) {
        val mgr = FontMgr.default
        val names = UIFont.familyNames.map { it.toString() }
        names.mapNotNull { family ->
            val typeface = mgr.matchFamilyStyle(family, FontStyle.NORMAL) ?: return@mapNotNull null
            typeface.close()
            FontEntry(
                ref = WatermarkFontRef.System(PLATFORM, family),
                displayName = family,
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    override suspend fun listImportedFonts(): List<FontEntry> = withContext(Dispatchers.Default) {
        store.listImported()
    }

    override suspend fun resolve(ref: WatermarkFontRef): FontResolution = withContext(Dispatchers.Default) {
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
                val cacheKey = ref.fingerprint()
                resolveMutex.withLock { resolveCache[cacheKey] }?.let { return@withContext it }
                val resolved = when (ref) {
                    is WatermarkFontRef.System -> resolveSystem(ref)
                    is WatermarkFontRef.Imported -> resolveImported(ref)
                    else -> FontResolution.Failure("Unsupported font", ref)
                }
                if (resolved is FontResolution.Success) {
                    resolveMutex.withLock { resolveCache[cacheKey] = resolved }
                }
                resolved
            }
        }
    }

    suspend fun importDirectory(path: String, cancelled: () -> Boolean = { false }): FontImportResult {
        return importMutex.withLock {
            withContext(Dispatchers.Default) {
                val candidates = collectFontFiles(path)
                WatermarkFontImporter.importCandidates(
                    store = store,
                    candidates = candidates,
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
        val typeface = FontMgr.default.matchFamilyStyle(ref.key, FontStyle.NORMAL)
            ?: return FontResolution.Failure("System font is unavailable", ref)
        return FontResolution.Success(
            family = FontFamily(Typeface(typeface, ref.key)),
            supportedStyles = capabilityOf(typeface),
            displayName = ref.key,
        )
    }

    private fun resolveImported(ref: WatermarkFontRef.Imported): FontResolution {
        val bytes = store.readFontBytes(ref.sha256)
            ?: return FontResolution.Failure("Imported font is missing", ref)
        val skia = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
            ?: return FontResolution.Failure("Imported font could not be loaded", ref)
        skia.close()
        val font = Font(identity = "imported:${ref.sha256}", data = bytes)
        val meta = store.metadata(ref.sha256)
        return FontResolution.Success(
            family = FontFamily(font),
            supportedStyles = capabilityFromBytes(bytes),
            displayName = meta?.displayName ?: ref.sha256.take(8),
        )
    }

    private fun validateImported(bytes: ByteArray, extension: String): ValidatedImportedFont {
        val typeface = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
            ?: error("Font could not be loaded")
        val name = typeface.familyName.ifBlank { "Imported font" }
        val capability = capabilityOf(typeface)
        typeface.close()
        return ValidatedImportedFont(displayName = name, capability = capability)
    }

    private fun capabilityFromBytes(bytes: ByteArray): FontStyleCapability {
        val typeface = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
            ?: return FontStyleCapability.NormalOnly
        val cap = capabilityOf(typeface)
        typeface.close()
        return cap
    }

    private fun capabilityOf(typeface: org.jetbrains.skia.Typeface): FontStyleCapability {
        val style = typeface.fontStyle
        val bold = style.weight >= 600
        val italic = style.slant != org.jetbrains.skia.FontSlant.UPRIGHT
        return FontStyleCapability.ofFace(bold, italic)
    }

    private fun collectFontFiles(rootPath: String): List<FontImportCandidate> {
        val fm = NSFileManager.defaultManager
        val root = rootPath.trimEnd('/')
        val out = mutableListOf<FontImportCandidate>()
        fun walk(dir: String) {
            if (out.size >= limits.maxCandidates) return
            val children = fm.contentsOfDirectoryAtPath(dir, error = null) ?: return
            for (child in children) {
                if (out.size >= limits.maxCandidates) return
                val name = child.toString()
                val path = "$dir/$name"
                val attrs = fm.attributesOfItemAtPath(path, error = null)
                val type = attrs?.get(NSFileType) as? String
                if (type == NSFileTypeDirectory) {
                    walk(path)
                } else if (WatermarkFontStore.isFontFileName(name)) {
                    val size = (attrs?.get(platform.Foundation.NSFileSize) as? Number)?.toLong()
                    out += FontImportCandidate(
                        fileName = name,
                        sizeBytes = size,
                        readBytes = {
                            val data = NSData.dataWithContentsOfFile(path)
                                ?: error("Could not read $name")
                            IosByteArrayInterop.fromNSData(data)
                        },
                    )
                }
            }
        }
        walk(root)
        return out
    }

    companion object {
        const val PLATFORM: String = "ios"

        fun defaultFontsRoot(): String {
            val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )
            val base = requireNotNull(documentDirectory?.path)
            return "$base/${WatermarkFontStore.DIR_NAME}"
        }
    }
}
