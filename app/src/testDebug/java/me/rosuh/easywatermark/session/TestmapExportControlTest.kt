package me.rosuh.easywatermark.session

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TestmapExportControlTest {
    @get:Rule val temporary = TemporaryFolder()
    private val files get() = temporary.root
    private val marker get() = File(files, "testmap-export-control.json")
    private val journal get() = File(files, "testmap-export-events.jsonl")
    private val fixture = "content://media/external_primary/images/media/42"
    private val clock = 1_000_000L
    private val outcome = ExportOutcome.failure(ExportFailure.Encode("delegate result"))
    private val calls = AtomicInteger()
    private val delegate = ExportPipelinePort { _, _, _ -> calls.incrementAndGet(); outcome }

    private fun arm(expires: Any = clock + 120_000, mode: String = "hold-next"): JSONObject = JSONObject()
        .put("mode", mode)
        .put("run_id", "run-42_repeat_1")
        .put("fixture_uri", fixture)
        .put("expires_at_ms", expires)
        .also { marker.writeText(it.toString()) }

    private fun port(now: Long = clock) = testmapExportControl(files, delegate) { now }
    private suspend fun ExportPipelinePort.export(uri: String = fixture) =
        exportOne(ImageInfo(MediaRef(uri)), WaterMark.default, UserPreferences.DEFAULT)
    private fun events() = journal.readLines().map { line ->
        val json = JSONObject(line)
        assertEquals(setOf("run_id", "event", "timestamp_ms"), json.keys().asSequence().toSet())
        assertEquals("run-42_repeat_1", json.getString("run_id"))
        json.getString("event")
    }

    @Test fun absentAndMismatchedControlsDelegateWithoutConsumption() = runBlocking {
        assertSame(outcome, port().export())
        arm()
        assertSame(outcome, port().export("content://media/external_primary/images/media/43"))
        assertTrue(marker.exists())
        assertFalse(journal.exists())
        assertEquals(2, calls.get())
    }

    @Test fun actualCancellationPropagatesAndNextExportDelegates() = runBlocking {
        arm()
        var observed: CancellationException? = null
        val reason = CancellationException("real export job cancellation")
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { port().export() } catch (e: CancellationException) { observed = e; throw e }
        }
        assertEquals(listOf("ready", "entered"), events())
        assertFalse(marker.exists())
        assertEquals(0, calls.get())
        job.cancel(reason)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(reason.message, observed?.message)
        // Coroutine stacktrace recovery may copy CE with the original as its cause.
        // Require this exact cancellation origin, not merely any cancellation-shaped failure.
        assertTrue(generateSequence(observed as Throwable?) { it.cause }.take(8).any { it === reason })
        assertEquals(listOf("ready", "entered", "cancelled", "cleared"), events())
        assertSame(outcome, port().export())
        assertEquals(1, calls.get())
    }

    @Test fun simultaneousCallsAcrossWrappersConsumeOnlyOnce() = runBlocking {
        arm()
        val start = CompletableDeferred<Unit>()
        val jobs = List(12) { launch(Dispatchers.Default) { start.await(); port().export() } }
        start.complete(Unit)
        withTimeout(5_000) { while (calls.get() < 11) delay(1) }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        assertEquals(11, calls.get())
        assertEquals(listOf("ready", "entered", "cancelled", "cleared"), events())
    }

    @Test fun watchdogIsFailureNotCancellationAndDoesNotRearm() = runBlocking {
        arm(clock + 1)
        val failure = runCatching { port().export() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(failure is CancellationException)
        assertEquals(listOf("ready", "entered", "watchdog", "cleared"), events())
        assertFalse(marker.exists())
        assertSame(outcome, port().export())
    }

    @Test fun invalidTypesExpiryOverflowAndUnknownFieldsFailClosed() = runBlocking {
        for (invalid in listOf<Any>(clock.toString(), clock + 0.5, JSONObject.NULL, clock,
            clock - 1, clock + 120_001, Long.MAX_VALUE)) {
            arm(invalid)
            assertNotNull(runCatching { port().export() }.exceptionOrNull())
            assertTrue(marker.exists())
        }
        marker.writeText(arm().put("delay", 1).toString())
        assertNotNull(runCatching { port().export() }.exceptionOrNull())
        arm()
        assertNotNull(runCatching { port(Long.MAX_VALUE).export() }.exceptionOrNull())
        assertEquals(0, calls.get())
        assertFalse(journal.exists())
    }

    @Test fun malformedOversizedOrUnscopedControlsFailClosed() = runBlocking {
        for (body in listOf("{", "x".repeat(4097), arm().put("run_id", "run/path").toString(),
            arm().put("fixture_uri", "file:///tmp/picture.png").toString(),
            arm().put("mode", "fail-next").toString())) {
            marker.writeText(body)
            assertNotNull(runCatching { port().export() }.exceptionOrNull())
        }
        assertEquals(0, calls.get())
        assertFalse(journal.exists())
    }

    @Test fun evidenceFailureCannotStartHoldOrMaskRealCancellation() = runBlocking {
        arm()
        journal.writeText("x".repeat(16_384))
        assertNotNull(runCatching { port().export() }.exceptionOrNull())
        assertEquals(16_384L, journal.length())
        assertFalse(marker.exists())
        journal.delete()
        arm()
        journal.mkdir()
        assertNotNull(runCatching { port().export() }.exceptionOrNull())
        assertEquals(0, calls.get())
        assertFalse(marker.exists())
        journal.delete()
        arm()
        var observed: CancellationException? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { port().export() } catch (e: CancellationException) { observed = e; throw e }
        }
        journal.delete()
        journal.mkdir()
        job.cancelAndJoin()
        assertNotNull(observed)
        assertSame(outcome, port().export())
    }

    @Test fun observeReturnsExactOutcomesWithOnlyFiniteTaxonomy() = runBlocking {
        val secret = "private source and settings must not enter events"
        val cases = listOf(
            ExportOutcome.success(ExportedMedia(MediaRef("content://private/result"), 10, 20,
                ImageFormat.PNG, 123)) to "outcome_success",
            ExportOutcome.failure(ExportFailure.SourceDecode(secret)) to "outcome_source_decode",
            ExportOutcome.failure(ExportFailure.Render(secret)) to "outcome_render",
            ExportOutcome.failure(ExportFailure.Encode(secret)) to "outcome_encode",
            ExportOutcome.failure(ExportFailure.Permission(secret)) to "outcome_permission",
            ExportOutcome.failure(ExportFailure.Io(secret)) to "outcome_io",
            ExportOutcome.failure(ExportFailure.Persistence(secret)) to "outcome_persistence",
            ExportOutcome.failure(ExportFailure.Cancelled(secret)) to "outcome_cancelled",
        )
        for ((expected, tag) in cases) {
            journal.delete()
            arm(mode = "observe-next")
            val observed = testmapExportControl(files, ExportPipelinePort { _, _, _ -> expected }) { clock }
            assertSame(expected, observed.export())
            assertEquals(listOf("ready", "entered", tag, "cleared"), events())
            assertFalse(marker.exists())
            assertFalse(journal.readText().contains(secret))
            assertFalse(journal.readText().contains("content://private/result"))
            assertSame(expected, observed.export())
            assertEquals(4, events().size)
        }
    }

    @Test fun observeConcurrentCallsConsumeOnceAndAllReachDelegate() = runBlocking {
        arm(mode = "observe-next")
        val start = CompletableDeferred<Unit>()
        val jobs = List(12) {
            launch(Dispatchers.Default) {
                start.await()
                assertSame(outcome, port().export())
            }
        }
        start.complete(Unit)
        jobs.joinAll()
        assertEquals(12, calls.get())
        assertEquals(listOf("ready", "entered", "outcome_encode", "cleared"), events())
        assertFalse(marker.exists())
    }

    @Test fun observeMismatchAndExpiredMarkerCannotObserveAnotherExport() = runBlocking {
        arm(mode = "observe-next")
        assertSame(outcome, port().export("content://media/external_primary/images/media/43"))
        assertTrue(marker.exists())
        assertFalse(journal.exists())
        arm(expires = clock, mode = "observe-next")
        assertNotNull(runCatching { port().export() }.exceptionOrNull())
        assertEquals(1, calls.get())
        assertTrue(marker.exists())
        assertFalse(journal.exists())
    }

    @Test fun observeRethrowsOriginalCancellationAndExceptionWithoutMessages() = runBlocking {
        for (reason in listOf(CancellationException("private cancellation"),
            IllegalStateException("private exception"))) {
            journal.delete()
            arm(mode = "observe-next")
            val observed = testmapExportControl(files, ExportPipelinePort { _, _, _ -> throw reason }) { clock }
            assertSame(reason, runCatching { observed.export() }.exceptionOrNull())
            val tag = if (reason is CancellationException) "cancelled" else "threw_exception"
            assertEquals(listOf("ready", "entered", tag, "cleared"), events())
            assertFalse(journal.readText().contains("private"))
            assertFalse(marker.exists())
        }
        journal.delete()
        arm(mode = "observe-next")
        val reason = CancellationException("actual export job cancellation")
        var caught: CancellationException? = null
        val suspended = testmapExportControl(files, ExportPipelinePort { _, _, _ ->
            awaitCancellation()
        }) { clock }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { suspended.export() } catch (e: CancellationException) { caught = e; throw e }
        }
        assertEquals(listOf("ready", "entered"), events())
        job.cancel(reason)
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(generateSequence(caught as Throwable?) { it.cause }.take(8).any { it === reason })
        assertEquals(listOf("ready", "entered", "cancelled", "cleared"), events())
    }

    @Test fun observeEvidenceFailureCannotStartDelegateOrReplaceItsResult() = runBlocking {
        arm(mode = "observe-next")
        journal.writeText("x".repeat(16_384))
        assertNotNull(runCatching { port().export() }.exceptionOrNull())
        assertEquals(0, calls.get())
        assertEquals(16_384L, journal.length())
        assertFalse(marker.exists())
        journal.delete()
        arm(mode = "observe-next")
        val observed = testmapExportControl(files, ExportPipelinePort { _, _, _ ->
            journal.delete()
            journal.mkdir() // IO failure after the real delegate has run.
            outcome
        }) { clock }
        assertSame(outcome, observed.export())
        assertFalse(marker.exists())
        journal.delete()
        arm(mode = "observe-next")
        val reason = CancellationException("original cancellation")
        val cancelled = testmapExportControl(files, ExportPipelinePort { _, _, _ ->
            journal.delete()
            journal.mkdir()
            throw reason
        }) { clock }
        assertSame(reason, runCatching { cancelled.export() }.exceptionOrNull())
        assertFalse(marker.exists())
    }
}
