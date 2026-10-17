package me.rosuh.easywatermark.font

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.platform.DesktopAppPaths
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class DesktopWatermarkFontAccess(
    root: File = File(DesktopAppPaths.resolveAppDataDir(), WatermarkFontStore.DIR_NAME),
    private val limits: FontImportLimits = FontImportLimits.Default,
    private val onEnumerate: ((File, () -> Boolean) -> Unit)? = null,
) : WatermarkFontAccess {

    private val store = WatermarkFontStore(
        fileSystem = FileSystem.SYSTEM,
        root = root.toOkioPath(),
        limits = limits,
    )
    private val importMutex = Mutex()
    private val resolveCache = ConcurrentHashMap<String, FontResolution>()

    override fun recoverOrphans() {
        store.recoverOrphans()
    }

    override suspend fun listSystemFonts(): List<FontEntry> = withContext(Dispatchers.IO) {
        val mgr = FontMgr.default
        val count = mgr.familiesCount
        val seen = LinkedHashSet<String>()
        val out = ArrayList<FontEntry>(count)
        for (i in 0 until count) {
            val name = mgr.getFamilyName(i)
            if (name.isBlank() || !seen.add(name)) continue
            val typeface = mgr.matchFamilyStyle(name, FontStyle.NORMAL) ?: continue
            typeface.close()
            out += FontEntry(
                ref = WatermarkFontRef.System(PLATFORM, name),
                displayName = name,
            )
        }
        out.sortedBy { it.displayName.lowercase() }
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
                val cacheKey = ref.fingerprint()
                resolveCache[cacheKey]?.let { return@withContext it }
                val resolved = when (ref) {
                    is WatermarkFontRef.System -> resolveSystem(ref)
                    is WatermarkFontRef.Imported -> resolveImported(ref)
                    else -> FontResolution.Failure("Unsupported font", ref)
                }
                if (resolved is FontResolution.Success) {
                    resolveCache[cacheKey] = resolved
                }
                resolved
            }
        }
    }

    suspend fun importDirectory(directory: File, cancelled: () -> Boolean = { false }): FontImportResult {
        return importMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val root = directory.canonicalFile
                    onEnumerate?.invoke(root, cancelled)
                    val enumeration = collectFontFiles(root, cancelled)
                    WatermarkFontImporter.importEnumerated(
                        store = store,
                        enumeration = enumeration,
                        limits = limits,
                        validate = ::validateImported,
                        cancelled = cancelled,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FontImportResult(
                        added = 0,
                        duplicates = 0,
                        failed = listOf(
                            FontImportFailure(
                                directory.name,
                                e.message?.takeIf { it.isNotBlank() } ?: "Import failed",
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun resolveSystem(ref: WatermarkFontRef.System): FontResolution {
        if (ref.platform != PLATFORM) {
            return FontResolution.Failure("Font belongs to another platform", ref)
        }
        val mgr = FontMgr.default
        val typeface = mgr.matchFamilyStyle(ref.key, FontStyle.NORMAL)
            ?: return FontResolution.Failure("System font is unavailable", ref)
        val family = FontFamily(Typeface(typeface, ref.key))
        val capability = capabilityOf(typeface)
        return FontResolution.Success(
            family = family,
            supportedStyles = capability,
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
        val typeface = FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) ?: return FontStyleCapability.NormalOnly
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

    private fun collectFontFiles(
        root: File,
        cancelled: () -> Boolean,
    ): FontEnumerationResult {
        val budget = FontScanBudget(limits, cancelled)
        val visited = HashSet<String>()
        val rootCanon = runCatching { root.canonicalFile }.getOrNull()
            ?: return budget.snapshot()
        fun walk(dir: File) {
            if (!budget.canContinue()) return
            val dirCanon = runCatching { dir.canonicalFile }.getOrNull() ?: return
            if (!FontDirectorySafety.isCanonicalInside(rootCanon.absolutePath, dirCanon.absolutePath)) {
                return
            }
            if (!visited.add(dirCanon.absolutePath)) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (!budget.onVisit()) return
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    walk(child)
                } else if (child.isFile && WatermarkFontStore.isFontFileName(child.name)) {
                    val childCanon = runCatching { child.canonicalFile }.getOrNull() ?: continue
                    if (!FontDirectorySafety.isCanonicalInside(rootCanon.absolutePath, childCanon.absolutePath)) {
                        continue
                    }
                    budget.offerCandidate(
                        FontImportCandidate(
                            fileName = child.name,
                            sizeBytes = child.length(),
                            openSource = { FileSystem.SYSTEM.source(childCanon.toOkioPath()) },
                        ),
                    )
                }
            }
        }
        walk(rootCanon)
        return budget.snapshot()
    }

    companion object {
        const val PLATFORM: String = "desktop"
    }
}
