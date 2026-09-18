package me.rosuh.easywatermark.uitest

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Live preview + key-node frames for the local console (ADR-0032).
 *
 * Writes under `docs/testmap/artifacts/` (gitignored). Not a golden: no
 * byte/hash assertion. Desktop is the human-view target; iOS writes are
 * best-effort.
 */
internal object L1Live {
    var activeTest: String = "l1"

    val previewTags: List<String> = listOf(
        "sharedComposeExportSheet",
        "sharedComposeGalleryDialog",
        "sharedComposeRecoveryScreen",
        "templateListSheet",
        "watermarkTextEditField",
        "iosLibraryReadUpsellContinue",
        "sharedComposeEditorScreen",
        "sharedComposeLaunchScreen",
    )

    private const val MAX_KEYFRAMES = 80
    private val fs = FileSystem.SYSTEM
    private val keyframes = ArrayDeque<Keyframe>()
    private var seq: Long = 0

    data class Keyframe(
        val file: String,
        val tag: String,
        val test: String,
        val t: Long,
    )

    fun write(testName: String, tag: String, bytes: ByteArray, keyframe: Boolean) {
        val root = resolveL1ArtifactsDir() ?: return
        val liveDir = root / "live"
        val keyDir = root / "keyframes"
        try {
            fs.createDirectories(liveDir)
            fs.createDirectories(keyDir)
            writeAtomic(liveDir / "preview.png", bytes)
            val now = nextSeq()
            writeAtomicString(
                liveDir / "preview.json",
                """{"tag":"${esc(tag)}","test":"${esc(testName)}","t":$now}""",
            )
            if (keyframe) {
                val file = "${now}-${safe(testName)}-${safe(tag)}.png"
                writeAtomic(keyDir / file, bytes)
                keyframes.addLast(Keyframe(file = file, tag = tag, test = testName, t = now))
                while (keyframes.size > MAX_KEYFRAMES) {
                    val old = keyframes.removeFirst()
                    runCatching { fs.delete(keyDir / old.file) }
                }
            }
            writeIndex(root)
        } catch (_: Throwable) {
            // iOS simulator / unwritable cwd — desktopTest is the live target.
        }
    }

    private fun nextSeq(): Long {
        seq += 1
        return seq
    }

    private fun writeIndex(root: Path) {
        val preview = keyframes.lastOrNull()
        val head = if (preview != null) {
            """{"tag":"${esc(preview.tag)}","test":"${esc(preview.test)}","t":${preview.t}}"""
        } else {
            "null"
        }
        val frames = keyframes.joinToString(",") { kf ->
            """{"file":"${esc(kf.file)}","tag":"${esc(kf.tag)}","test":"${esc(kf.test)}","t":${kf.t}}"""
        }
        writeAtomicString(root / "index.json", """{"preview":$head,"keyframes":[$frames]}""")
    }

    private fun writeAtomic(path: Path, bytes: ByteArray) {
        val tmp = path.parent!!.div("${path.name}.tmp")
        fs.write(tmp) { write(bytes) }
        fs.atomicMove(tmp, path)
    }

    private fun writeAtomicString(path: Path, text: String) {
        writeAtomic(path, text.encodeToByteArray())
    }

    private fun esc(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
    }

    internal fun safe(value: String): String {
        val cleaned = buildString(value.length) {
            value.forEach { ch ->
                append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '-')
            }
        }.trim('-').ifEmpty { "frame" }
        return cleaned.take(48)
    }
}

internal fun resolveL1ArtifactsDir(): Path? {
    val repo = resolveRepoRoot() ?: return null
    return repo / "docs" / "testmap" / "artifacts"
}

internal fun resolveRepoRoot(): Path? {
    val start = try {
        FileSystem.SYSTEM.canonicalize(".".toPath())
    } catch (_: Throwable) {
        return null
    }
    var walk: Path? = start
    repeat(8) {
        val here = walk ?: return null
        if (FileSystem.SYSTEM.exists(here / "settings.gradle.kts")) return here
        walk = here.parent
    }
    return null
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.captureL1Live(tag: String, keyframe: Boolean) {
    try {
        val bitmap = onNodeWithTag(tag, useUnmergedTree = true).captureToImage()
        L1Live.write(L1Live.activeTest, tag, encodePng(bitmap), keyframe)
    } catch (_: Throwable) {
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.captureL1LiveBestEffort(keyframe: Boolean = false) {
    for (candidate in L1Live.previewTags) {
        val present = try {
            onAllNodesWithTag(candidate, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        } catch (_: Throwable) {
            false
        }
        if (present) {
            captureL1Live(candidate, keyframe = keyframe)
            return
        }
    }
}
