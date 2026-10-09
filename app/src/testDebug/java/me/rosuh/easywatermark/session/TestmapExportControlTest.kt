package me.rosuh.easywatermark.session

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private fun arm(expires: Any = clock + 120_000): JSONObject = JSONObject()
        .put("mode", "hold-next")
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
}
