package me.rosuh.easywatermark.session

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

internal fun withTestmapExportControl(
    context: Context,
    delegate: ExportPipelinePort,
): ExportPipelinePort = testmapExportControl(context.filesDir, delegate)

/** Debug-only, private, one-shot seam. The shared export job and Cancel button remain real. */
internal fun testmapExportControl(
    filesDir: File,
    delegate: ExportPipelinePort,
    now: () -> Long = System::currentTimeMillis,
): ExportPipelinePort = ExportPipelinePort { image, config, prefs ->
    val claim = claimControl(filesDir, image.uri.value, now())
    if (claim == null) {
        delegate.exportOne(image, config, prefs)
    } else if (claim.mode == "observe-next") {
        try {
            event(filesDir, claim.runId, "ready", now())
            event(filesDir, claim.runId, "entered", now())
            val outcome = try {
                delegate.exportOne(image, config, prefs)
            } catch (cancelled: CancellationException) {
                runCatching { event(filesDir, claim.runId, "cancelled", now()) }
                throw cancelled
            } catch (failure: Exception) {
                runCatching { event(filesDir, claim.runId, "threw_exception", now()) }
                throw failure
            }
            // Observation must never replace the real result with a journal write failure.
            runCatching {
                val outputUri = (outcome as? ExportOutcome.Success)?.media?.ref?.value
                check(outputUri == null || fixturePattern.matches(outputUri)) { "Invalid Testmap export output" }
                event(filesDir, claim.runId, outcome.observationEvent(), now(), outputUri)
            }
            outcome
        } finally {
            runCatching { event(filesDir, claim.runId, "cleared", now()) }
        }
    } else {
        try {
            event(filesDir, claim.runId, "ready", now())
            event(filesDir, claim.runId, "entered", now())
            // Only this timeout returns null. Parent cancellation still throws its original CE.
            withTimeoutOrNull(claim.holdMs) { awaitCancellation() }
            event(filesDir, claim.runId, "watchdog", now())
            error("Testmap export hold watchdog expired")
        } catch (cancelled: CancellationException) {
            // Evidence write failure must never replace the actual cancellation exception.
            runCatching { event(filesDir, claim.runId, "cancelled", now()) }
            throw cancelled
        } finally {
            // Consumption already removed the marker. Missing evidence fails harness acceptance.
            runCatching { event(filesDir, claim.runId, "cleared", now()) }
        }
    }
}

private const val CONTROL = "testmap-export-control.json"
private const val EVENTS = "testmap-export-events.jsonl"
private const val MAX_MARKER_BYTES = 4096
private const val MAX_EVENT_BYTES = 16_384L
private const val MAX_EXPIRY_MS = 120_000L
private const val MAX_HOLD_MS = 30_000L
private val controlLock = Any()
private val runIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}")
private val fixturePattern = Regex("content://media/(external|external_primary)/images/media/[0-9]+")
private val controlKeys = setOf("mode", "run_id", "fixture_uri", "expires_at_ms")
private data class Claim(val runId: String, val mode: String, val holdMs: Long)

/** Finite taxonomy only: never serialize messages, exception names, paths, or settings. */
private fun ExportOutcome.observationEvent(): String = when (this) {
    is ExportOutcome.Success -> "outcome_success"
    is ExportOutcome.Failure -> when (failure) {
        is ExportFailure.SourceDecode -> "outcome_source_decode"
        is ExportFailure.Render -> "outcome_render"
        is ExportFailure.Encode -> "outcome_encode"
        is ExportFailure.Permission -> "outcome_permission"
        is ExportFailure.Io -> "outcome_io"
        is ExportFailure.Persistence -> "outcome_persistence"
        is ExportFailure.Cancelled -> "outcome_cancelled"
    }
}

private fun claimControl(filesDir: File, fixture: String, now: Long): Claim? = synchronized(controlLock) {
    val marker = File(filesDir, CONTROL)
    if (!marker.exists()) return@synchronized null
    // Invalid controls cannot silently turn a requested hold into a normal export.
    check(marker.isFile && marker.length() in 1..MAX_MARKER_BYTES.toLong()) { "Invalid Testmap export control size" }
    val bytes = marker.inputStream().use { input ->
        val buffer = ByteArray(MAX_MARKER_BYTES + 1)
        var length = 0
        while (length < buffer.size) {
            val count = input.read(buffer, length, buffer.size - length)
            if (count < 0) break
            length += count
        }
        buffer.copyOf(length)
    }
    check(bytes.size <= MAX_MARKER_BYTES) { "Invalid Testmap export control size" }
    val json = JSONObject(bytes.toString(Charsets.UTF_8))
    check(json.keys().asSequence().toSet() == controlKeys) { "Invalid Testmap export control fields" }
    val mode = json.get("mode")
    check(mode == "hold-next" || mode == "observe-next") { "Invalid Testmap export control mode" }
    val runId = json.get("run_id")
    val uri = json.get("fixture_uri")
    val expiry = json.get("expires_at_ms")
    check(runId is String && runIdPattern.matches(runId)) { "Invalid Testmap export run ID" }
    check(uri is String && fixturePattern.matches(uri)) { "Invalid Testmap export fixture" }
    check(expiry is Long || expiry is Int) { "Invalid Testmap export expiry type" }
    val expiresAt = (expiry as Number).toLong()
    // Check before subtraction, including the upper boundary, to avoid overflow.
    check(now >= 0 && now <= Long.MAX_VALUE - MAX_EXPIRY_MS &&
        expiresAt > now && expiresAt <= now + MAX_EXPIRY_MS) { "Invalid Testmap export expiry" }
    if (uri != fixture) return@synchronized null
    check(marker.delete()) { "Cannot consume Testmap export control" }
    Claim(runId, mode as String, minOf(expiresAt - now, MAX_HOLD_MS))
}

private fun event(filesDir: File, runId: String, name: String, timestamp: Long, outputUri: String? = null) = synchronized(controlLock) {
    val line = JSONObject()
        .put("run_id", runId)
        .put("event", name)
        .put("timestamp_ms", timestamp)
        .also { if (outputUri != null) it.put("output_uri", outputUri) }
        .toString()
    val bytes = "$line\n".toByteArray(Charsets.UTF_8)
    val journal = File(filesDir, EVENTS)
    check(!journal.exists() || journal.isFile) { "Invalid Testmap export event file" }
    check(journal.length() <= MAX_EVENT_BYTES - bytes.size) { "Testmap export event limit reached" }
    journal.appendBytes(bytes)
}
