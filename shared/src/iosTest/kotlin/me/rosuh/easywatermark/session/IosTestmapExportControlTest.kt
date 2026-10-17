@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosTestmapExportControlTest {
    private class Fixture {
        val fs = FileSystem.SYSTEM
        val root = NSTemporaryDirectory().toPath() / "testmap-control-${NSUUID().UUIDString}"
        val documents = root / "Documents"
        val temporary = root / "tmp"
        val marker = documents / "testmap-export-control.json"
        val journal = documents / "testmap-export-events.jsonl"
        val fixture = documents / "testmap-export-fixture.png"
        val source = temporary / "ewm_src_${NSUUID().UUIDString}"
        val clock = 1_000_000L
        private val countLock = NSLock()
        private var calls = 0
        val outcome = ExportOutcome.failure(ExportFailure.Encode("real delegate result"))
        val delegate = ExportPipelinePort { _, _, _ ->
            countLock.lock()
            try { calls++ } finally { countLock.unlock() }
            outcome
        }
        fun callCount(): Int {
            countLock.lock()
            try { return calls } finally { countLock.unlock() }
        }
        init {
            fs.createDirectories(documents)
            fs.createDirectories(temporary)
            write(fixture, PNG)
            write(source, PNG)
        }
        fun write(path: Path, bytes: ByteArray) = fs.write(path) { write(bytes) }
        fun arm(mode: String = "hold-next", expiry: String = (clock + 120_000).toString(), run: String = "run-42") {
            fs.write(marker) {
                writeUtf8("""{"mode":"$mode","run_id":"$run","fixture_id":"testmap-export-fixture","expires_at_ms":$expiry}""")
            }
        }
        fun port(delegate: ExportPipelinePort = this.delegate, time: Long = clock) =
            IosTestmapExportControl(documents, temporary, delegate) { time }
        fun events(): List<String> = fs.read(journal) { readUtf8() }.lineSequence().filter { it.isNotBlank() }.map {
            val json = Json.parseToJsonElement(it).jsonObject
            assertEquals(setOf("run_id", "event", "timestamp_ms"), json.keys)
            assertEquals("run-42", json["run_id"]?.jsonPrimitive?.content)
            json.getValue("event").jsonPrimitive.content
        }.toList()
        fun close() = fs.deleteRecursively(root)
    }

    private suspend fun Fixture.export(port: ExportPipelinePort = port(), path: Path = source): ExportOutcome =
        port.exportOne(ImageInfo(MediaRef(path.toString())), WaterMark.default, UserPreferences(ImageFormat.PNG, 100))

    @Test fun absentAndDifferentFixtureDelegateWithoutConsumption() = runBlocking {
        val f = Fixture()
        try {
            f.fs.delete(f.fixture)
            assertSame(f.outcome, f.export()) // No control means no fixture read.
            f.write(f.fixture, PNG)
            f.arm()
            f.write(f.source, PNG + byteArrayOf(0))
            assertSame(f.outcome, f.export())
            f.write(f.source, ByteArray(1_048_577))
            assertSame(f.outcome, f.export()) // An unrelated large source is not this bounded fixture.
            assertTrue(f.fs.exists(f.marker))
            assertFalse(f.fs.exists(f.journal))
            assertEquals(3, f.callCount())
        } finally { f.close() }
    }

    @Test fun realJobCancelPropagatesAndNextExportDelegates() = runBlocking {
        val f = Fixture()
        try {
            f.arm()
            val reason = CancellationException("actual cancel")
            var caught: CancellationException? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try { f.export() } catch (e: CancellationException) { caught = e; throw e }
            }
            assertEquals(listOf("ready", "entered"), f.events())
            assertFalse(f.fs.exists(f.marker))
            job.cancel(reason)
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(generateSequence(caught as Throwable?) { it.cause }.take(8).any { it === reason })
            assertEquals(listOf("ready", "entered", "cancelled", "cleared"), f.events())
            assertSame(f.outcome, f.export())
            assertEquals(1, f.callCount())
        } finally { f.close() }
    }

    @Test fun failureIsOnceThenRealIosPortProducesOutput() = runBlocking {
        val f = Fixture()
        var output: Path? = null
        try {
            f.arm("fail-next")
            val port = f.port(IosExportPipelinePort())
            assertIs<ExportFailure.Persistence>(assertIs<ExportOutcome.Failure>(f.export(port)).failure)
            assertEquals(listOf("ready", "entered", "failed", "cleared"), f.events())
            assertFalse(f.fs.exists(f.marker))
            val success = assertIs<ExportOutcome.Success>(f.export(port))
            val outputPath = success.media.ref.value.toPath()
            output = outputPath
            assertTrue(f.fs.metadata(outputPath).size!! > 0)
            assertEquals(8, success.media.width)
            assertEquals(8, success.media.height)
        } finally {
            output?.let { f.fs.delete(it) }
            f.close()
        }
    }

    @Test fun concurrentCallsAcrossWrappersClaimOnlyOneHold() = runBlocking {
        val f = Fixture()
        try {
            f.arm()
            val start = CompletableDeferred<Unit>()
            val jobs = List(8) { launch(Dispatchers.Default) { start.await(); f.export() } }
            start.complete(Unit)
            withTimeout(5_000) { while (f.callCount() < 7) delay(1) }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            assertEquals(7, f.callCount())
            assertEquals(listOf("ready", "entered", "cancelled", "cleared"), f.events())
        } finally { f.close() }
    }

    @Test fun watchdogCannotBeMistakenForCancellation() = runBlocking {
        val f = Fixture()
        try {
            f.arm(expiry = (f.clock + 1).toString())
            val error = runCatching { f.export() }.exceptionOrNull()
            assertIs<IllegalStateException>(error)
            assertFalse(error is CancellationException)
            assertEquals(listOf("ready", "entered", "watchdog", "cleared"), f.events())
            assertSame(f.outcome, f.export())
        } finally { f.close() }
    }

    @Test fun invalidControlAndForeignNonceFailClosed() = runBlocking {
        val f = Fixture()
        try {
            for (expiry in listOf("\"1000001\"", "1000001.0", "true", "1000000", "1120001", "9223372036854775807")) {
                f.arm(expiry = expiry)
                assertNotNull(runCatching { f.export() }.exceptionOrNull())
            }
            f.arm(run = "run-43")
            assertNotNull(runCatching { f.export() }.exceptionOrNull())
            f.arm("arbitrary-mode")
            assertNotNull(runCatching { f.export() }.exceptionOrNull())
            f.arm()
            assertNotNull(runCatching { f.export(f.port(time = Long.MAX_VALUE)) }.exceptionOrNull())
            f.write(f.marker, ByteArray(4097))
            assertNotNull(runCatching { f.export() }.exceptionOrNull())
            assertEquals(0, f.callCount())
            assertFalse(f.fs.exists(f.journal))
        } finally { f.close() }
    }

    @Test fun symlinksOversizedFixtureAndCorruptNonceCrcAreRejected() = runBlocking {
        val f = Fixture()
        try {
            f.arm()
            f.fs.delete(f.source)
            f.fs.createSymlink(f.source, f.fixture)
            assertNotNull(runCatching { f.export() }.exceptionOrNull())
            f.fs.delete(f.source)
            f.write(f.source, PNG)
            f.write(f.fixture, ByteArray(1_048_577))
            assertNotNull(runCatching { f.export() }.exceptionOrNull())
            val corrupted = PNG.copyOf().also { it[it.size - 13] = (it[it.size - 13].toInt() xor 1).toByte() }
            f.write(f.fixture, corrupted)
            assertNotNull(runCatching { f.export() }.exceptionOrNull())
            assertTrue(f.fs.exists(f.marker))
            assertEquals(0, f.callCount())
        } finally { f.close() }
    }

    @Test fun eventFailureIsClosedAndCannotReplaceActualCancel() = runBlocking {
        val f = Fixture()
        try {
            f.arm()
            f.write(f.journal, ByteArray(16_384))
            assertNotNull(runCatching { f.export() }.exceptionOrNull())
            assertEquals(16_384L, f.fs.metadata(f.journal).size)
            assertEquals(0, f.callCount())
            f.fs.delete(f.journal)
            f.arm()
            var cancelled: CancellationException? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try { f.export() } catch (e: CancellationException) { cancelled = e; throw e }
            }
            f.fs.delete(f.journal)
            f.fs.createDirectory(f.journal)
            job.cancelAndJoin()
            assertNotNull(cancelled)
            assertSame(f.outcome, f.export())
        } finally { f.close() }
    }

    companion object {
        // 8x8 RGBA PNG generated with Python stdlib zlib; one CRC-valid testmap-run tEXt chunk.
        private val PNG = ("89504e470d0a1a0a0000000d4948445200000008000000080806000000c40fbe8b" +
            "0000001249444154789c6390f46df88f0f338c0c05002d0a79417b6933570000003374455874" +
            "746573746d61702d72756e0072756e2d34323a6161616161616161616161616161616161616161616161616161616161616161" +
            "7ae40d4b0000000049454e44ae426082").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
