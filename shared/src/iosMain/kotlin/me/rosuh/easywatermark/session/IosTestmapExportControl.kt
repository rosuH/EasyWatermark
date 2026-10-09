@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package me.rosuh.easywatermark.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import kotlin.native.Platform

/** Release returns before resolving directories, constructing the control, or inspecting files. */
internal fun withIosTestmapExportControl(delegate: ExportPipelinePort): ExportPipelinePort {
    if (!Platform.isDebugBinary) return delegate
    val documents = NSFileManager.defaultManager.URLForDirectory(
        NSDocumentDirectory, NSUserDomainMask, null, false, null,
    )?.path ?: error("Testmap documents directory unavailable")
    return IosTestmapExportControl(documents.toPath(), NSTemporaryDirectory().toPath(), delegate)
}

/** Private one-shot device-test entry, outside the real port's exception handlers. */
internal class IosTestmapExportControl(
    private val documents: Path,
    private val temporary: Path,
    private val delegate: ExportPipelinePort,
    private val now: () -> Long = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
) : ExportPipelinePort {
    private val fs = FileSystem.SYSTEM
    private val marker get() = documents / "testmap-export-control.json"
    private val journal get() = documents / "testmap-export-events.jsonl"
    private val fixture get() = documents / "testmap-export-fixture.png"

    override suspend fun exportOne(imageInfo: ImageInfo, config: WaterMark, prefs: UserPreferences): ExportOutcome {
        val claim = claim(imageInfo.uri.value) ?: return delegate.exportOne(imageInfo, config, prefs)
        try {
            event(claim.runId, "ready")
            event(claim.runId, "entered")
            if (claim.mode == "fail-next") {
                event(claim.runId, "failed")
                return ExportOutcome.failure(ExportFailure.Persistence(message = "Testmap one-shot persistence failure"))
            }
            // Local timeout is an ordinary failure. Only external cancellation propagates as CE.
            withTimeoutOrNull(claim.holdMs) { awaitCancellation() }
            event(claim.runId, "watchdog")
            error("Testmap export hold watchdog expired")
        } catch (cancelled: CancellationException) {
            runCatching { event(claim.runId, "cancelled") }
            throw cancelled
        } finally {
            // The marker was already consumed. Evidence failure must not replace a real CE.
            runCatching { event(claim.runId, "cleared") }
        }
    }

    private data class Claim(val mode: String, val runId: String, val holdMs: Long)

    private fun claim(rawSource: String): Claim? = ControlIo.locked {
        if (fs.metadataOrNull(marker) == null) return@locked null
        val json = Json.parseToJsonElement(readBounded(marker, 4096).decodeToString(throwOnInvalidSequence = true))
        check(json is JsonObject && json.keys == setOf("mode", "run_id", "fixture_id", "expires_at_ms")) {
            "Invalid Testmap export control fields"
        }
        fun string(key: String): String {
            val value = json[key]
            check(value is JsonPrimitive && value.isString) { "Invalid Testmap export control type" }
            return value.content
        }
        val mode = string("mode")
        check(mode == "hold-next" || mode == "fail-next") { "Invalid Testmap export mode" }
        val runId = string("run_id")
        check(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}").matches(runId)) { "Invalid Testmap export run ID" }
        check(string("fixture_id") == "testmap-export-fixture") { "Invalid Testmap fixture ID" }
        val expiry = json["expires_at_ms"]
        check(expiry is JsonPrimitive && !expiry.isString && Regex("[0-9]+").matches(expiry.content)) {
            "Invalid Testmap export expiry type"
        }
        val expiresAt = expiry.content.toLongOrNull() ?: error("Invalid Testmap export expiry")
        fun remaining(): Long {
            val timestamp = now()
            check(timestamp >= 0 && timestamp <= Long.MAX_VALUE - 120_000 &&
                expiresAt > timestamp && expiresAt <= timestamp + 120_000) { "Invalid Testmap export expiry" }
            return expiresAt - timestamp
        }
        remaining()
        val source = rawSource.toPath()
        // Match the exact path from the actual Session item, never a directory scan or host path.
        if (source.parent != temporary || !Regex("ewm_src_[0-9A-Fa-f-]{36}").matches(source.name)) return@locked null
        val expected = readBounded(fixture, 1_048_576)
        checkFixtureRun(expected, runId)
        val sourceMetadata = fs.metadata(source)
        check(sourceMetadata.isRegularFile && sourceMetadata.symlinkTarget == null) { "Unsafe Testmap source" }
        if (sourceMetadata.size != expected.size.toLong()) return@locked null
        val actual = readBounded(source, 1_048_576)
        if (!actual.contentEquals(expected)) return@locked null
        val holdMs = minOf(remaining(), 30_000)
        fs.delete(marker, mustExist = true)
        Claim(mode, runId, holdMs)
    }

    private fun readBounded(path: Path, limit: Int): ByteArray {
        val metadata = fs.metadata(path)
        check(metadata.isRegularFile && metadata.symlinkTarget == null &&
            (metadata.size ?: -1L) in 1L..limit.toLong()) { "Invalid Testmap private file" }
        check(fs.canonicalize(path).parent == fs.canonicalize(path.parent!!)) { "Unsafe Testmap private path" }
        return fs.source(path).use { input ->
            val buffer = Buffer()
            while (buffer.size <= limit && input.read(buffer, limit + 1L - buffer.size) != -1L) Unit
            check(buffer.size <= limit) { "Testmap private file exceeds bound" }
            buffer.readByteArray()
        }
    }

    private fun event(runId: String, name: String) = ControlIo.locked {
        val bytes = (buildJsonObject {
            put("run_id", runId)
            put("event", name)
            put("timestamp_ms", now())
        }.toString() + "\n").encodeToByteArray()
        val metadata = fs.metadataOrNull(journal)
        check(metadata == null || (metadata.isRegularFile && metadata.symlinkTarget == null)) {
            "Invalid Testmap event file"
        }
        check((metadata?.size ?: 0) <= 16_384L - bytes.size) { "Testmap event limit reached" }
        fs.appendingSink(journal).buffer().use { it.write(bytes) }
    }
}

/** Initialized only when the debug helper actually claims a control or writes an event. */
private object ControlIo {
    private val lock = NSLock()
    fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}

/** The nonce is in PNG bytes, not just its filename, so another run cannot match this fixture. */
private fun checkFixtureRun(bytes: ByteArray, runId: String) {
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    check(bytes.size >= 20 && bytes.copyOfRange(0, 8).contentEquals(signature)) { "Invalid Testmap fixture PNG" }
    fun uint32(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 255) }
    var offset = 8
    var matches = 0
    var ended = false
    while (offset + 12 <= bytes.size) {
        val length = uint32(offset)
        check(length <= bytes.size - offset - 12) { "Invalid Testmap PNG chunk" }
        val end = offset + 8 + length.toInt()
        val type = bytes.copyOfRange(offset + 4, offset + 8).decodeToString()
        if (type == "tEXt") {
            val value = bytes.copyOfRange(offset + 8, end).decodeToString(throwOnInvalidSequence = true)
            if (value.startsWith("testmap-run\u0000")) {
                val prefix = "testmap-run\u0000$runId:"
                check(value.startsWith(prefix) &&
                    Regex("[0-9a-f]{32}").matches(value.removePrefix(prefix))) { "Wrong Testmap fixture run" }
                var crc = -1
                for (index in offset + 4 until end) {
                    crc = crc xor (bytes[index].toInt() and 255)
                    repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
                }
                check((crc.inv().toLong() and 0xffffffffL) == uint32(end)) { "Invalid Testmap fixture CRC" }
                matches++
            }
        }
        offset = end + 4
        if (type == "IEND") {
            check(length == 0L && offset == bytes.size) { "Invalid Testmap PNG end" }
            ended = true
            break
        }
    }
    check(ended && matches == 1) { "Missing or repeated Testmap fixture nonce" }
}
