@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicInt
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.IosImageIODecoder
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.IosPickGenerationGate
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.numberWithInt
import platform.Foundation.numberWithLong
import platform.darwin.NSObjectProtocol

/**
 * Host-owned progressive import state machine (zero public Shared.framework surface).
 *
 * Control plane is NotificationCenter (names mirrored in Swift ProgressiveImportNotifications).
 * Session only ever receives Ready [ImageInfo] paths. Pending/Failed live here.
 *
 * Attempt-5 lessons baked in:
 * - fileReady acknowledges success only after guarded Session publication succeeds
 * - observers removed on [close]
 * - retry sources retained separately (Swift owns provisional files)
 * - remove is an awaited Session republish transaction including empty selection
 * - no Main-thread runBlocking
 */
internal class IosProgressiveImportController(
    private val session: WatermarkSessionViewModel,
    private val waterMarkProvider: suspend () -> WaterMark,
    private val hostScope: CoroutineScope,
    private val hostAlive: () -> Boolean,
    private val onSlotsChanged: (EditorMediaSlotState) -> Unit = {},
    private val onImportChromeChanged: (inProgress: Boolean) -> Unit = {},
    /**
     * Awaited after the focused slot first becomes Ready (outside [mutationMutex]).
     * Host must finish watermark-priority preview bind before returning so Swift's
     * first-item-alone transfer lane does not open the second Photos transfer yet.
     */
    private val onFocusReadyForPreview: suspend (focusPath: String) -> Unit = {},
    /** Production delivery is Main; tests use null for deterministic synchronous NC delivery. */
    private val notificationDeliveryQueue: NSOperationQueue? = NSOperationQueue.mainQueue,
) {
    var slots: EditorMediaSlotState by mutableStateOf(EditorMediaSlotState(emptyList(), null))
        private set
    var importInProgress: Boolean by mutableStateOf(false)
        private set
    var activeGeneration: Long = -1L
        private set

    /** importId → stable Session ewm_src path after successful adoption. */
    private val readyPaths = linkedMapOf<String, String>()
    /** Old fresh-selection paths held until the replacement Session publication is durable. */
    private val pendingFreshCleanupPaths = linkedSetOf<String>()
    private var appendMode: Boolean = false
    /** Baseline Session selection when append=true (uris preserved across progressive fills). */
    private var appendBaseline: List<ImageInfo> = emptyList()
    private var prioritizeImportId: String? = null
    private var closed = false
    private val observers = mutableListOf<NSObjectProtocol>()
    /** Monotonic-ish attempt clock for Pending chrome (ms since process start of first tick). */
    private var monoOriginMs: Long = -1L
    private fun nowMonoMs(): Long {
        // Prefer the same monotonic base as ImportTimelineProbe when available; Pending chrome only
        // needs a forward-moving clock relative to attempt start (not wall-clock absolute).
        val now = me.rosuh.easywatermark.session.ImportTimelineProbe.nowMonoMsForHost()
        if (monoOriginMs < 0L) monoOriginMs = now
        return now - monoOriginMs
    }

    /** Host/composable clock for Pending chrome elapsed = now - attemptStartedAtMs. */
    internal fun nowMonoMsForTests(): Long = nowMonoMs()
    /**
     * Test inject: if non-null, production file-ready job awaits this gate before adopt body.
     * Cancel the Job while waiting to exercise pre-body ACK-once via [invokeOnCompletion].
     */
    internal var preBodyGateForTests: CompletableDeferred<Unit>? = null
    /**
     * Test inject: invoked after NonCancellable stable copy succeeds and before the
     * cancellation/rollback check. Tests cancel the Job here to prove destination delete.
     */
    internal var afterStableCopyForTests: (suspend (stablePath: String) -> Unit)? = null
    /** Serializes ready/fail/remove Session transactions without ever blocking the main thread. */
    private val mutationMutex = Mutex()

    enum class AdoptionAck {
        Published,
        StaleGeneration,
        NotPublished,
        Disposed,
        InvalidPath,
        Error,
    }

    fun installObservers() {
        if (observers.isNotEmpty() || closed) return
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(BEGIN, null, notificationDeliveryQueue) { note ->
            handleBegin(note)
        }
        observers += center.addObserverForName(FILE_READY, null, notificationDeliveryQueue) { note ->
            handleFileReady(note)
        }
        observers += center.addObserverForName(FILE_FAILED, null, notificationDeliveryQueue) { note ->
            handleFileFailed(note)
        }
        observers += center.addObserverForName(FINISH, null, notificationDeliveryQueue) { note ->
            handleFinish(note)
        }
        observers += center.addObserverForName(CANCEL, null, notificationDeliveryQueue) { note ->
            handleCancel(note)
        }
        observers += center.addObserverForName(RETRY, null, notificationDeliveryQueue) { note ->
            // Retry re-uses fileReady path after markPendingRetry; Swift posts fileReady after.
            handleRetryChrome(note)
        }
        observers += center.addObserverForName(REMOVE, null, notificationDeliveryQueue) { note ->
            handleRemove(note)
        }
        observers += center.addObserverForName(PRIORITIZE, null, notificationDeliveryQueue) { note ->
            val id = note?.userInfoString(KEY_IMPORT_ID) ?: return@addObserverForName
            prioritizeImportId = id
            postControl(PRIORITIZE_REQUESTED, id)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        removeObservers()
        importInProgress = false
        onImportChromeChanged(false)
        slots = EditorMediaSlotState(emptyList(), null)
        // Process-wide Session outlives a single Host. Never delete app-owned sources that the
        // live Session selection still references — a later Host rebuild must still be able to
        // open those paths. Only drop controller-private orphans that Session no longer holds.
        val sessionHeld = sessionHeldSourcePaths()
        readyPaths.values
            .filter { IosSourceStager.isOwnedSourcePath(it) && it !in sessionHeld }
            .forEach(IosSourceStager::deleteQuietly)
        readyPaths.clear()
        deletePendingFreshCleanupPaths(excluding = sessionHeld)
        activeGeneration = -1L
    }

    /**
     * Leave-editor / empty-selection resource release (host still alive).
     *
     * Deletes app-owned `ewm_src_*` that Session no longer holds, drops controller maps/slots,
     * and clears Pending chrome. Safe to call repeatedly; does **not** remove NotificationCenter
     * observers (unlike [close]).
     */
    fun releaseUnheldSourcesAfterLeaveEditor() {
        if (closed) return
        hostScope.launch {
            mutationMutex.withLock {
                if (closed) return@withLock
                val sessionHeld = sessionHeldSourcePaths()
                readyPaths.values
                    .filter { IosSourceStager.isOwnedSourcePath(it) && it !in sessionHeld }
                    .forEach(IosSourceStager::deleteQuietly)
                readyPaths.keys
                    .filter { readyPaths[it] !in sessionHeld }
                    .toList()
                    .forEach { readyPaths.remove(it) }
                deletePendingFreshCleanupPaths(excluding = sessionHeld)
                // Session empty → drop progressive presentation entirely (Pending/Failed/Ready).
                if (sessionHeld.isEmpty()) {
                    readyPaths.clear()
                    slots = EditorMediaSlotState(emptyList(), null)
                    importInProgress = false
                    onImportChromeChanged(false)
                    activeGeneration = -1L
                    onSlotsChanged(slots)
                } else if (slots.slots.isNotEmpty()) {
                    // Keep only Ready slots still held by Session.
                    val kept = slots.slots.mapNotNull { slot ->
                        when (slot) {
                            is EditorMediaSlot.Ready ->
                                slot.takeIf { it.image.uri.value in sessionHeld }
                            else -> null
                        }
                    }
                    slots = EditorMediaSlotState(
                        slots = kept,
                        focusedImportId = slots.focusedImportId
                            ?.takeIf { id -> kept.any { it.importId == id } }
                            ?: kept.firstOrNull()?.importId,
                    )
                    onSlotsChanged(slots)
                }
            }
        }
    }

    private fun sessionHeldSourcePaths(): Set<String> =
        session.launchScreenUiStateFlow.value.selectedImageList
            .map { it.uri.value }
            .filter { it.isNotBlank() }
            .toSet()

    private fun removeObservers() {
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
    }

    private fun handleBegin(note: NSNotification?) {
        if (closed || !hostAlive()) return
        val generation = note?.userInfoLong(KEY_GENERATION) ?: return
        val ids = note.userInfoStringList(KEY_IMPORT_IDS)
        if (ids.isEmpty()) return
        val append = note.userInfoBool(KEY_APPEND)
        // Generation + slot chrome update runs under the mutation mutex so a concurrent empty
        // NavigateBack / Ready publish cannot interleave with a newer begin.
        hostScope.launch {
            mutationMutex.withLock {
                if (closed || !hostAlive()) return@withLock
                activeGeneration = generation
                appendMode = append
                prioritizeImportId = ids.firstOrNull()
                // BEGIN is posted from Swift at picker completion; treat as picker_callback then
                // fixed slots (same generation, privacy-safe ids only).
                me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
                    "picker_callback",
                    generation,
                    "n${ids.size}",
                )
                me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
                    "slots_published",
                    generation,
                    "n${ids.size}",
                )
                if (append) {
                    val launch = session.launchScreenUiStateFlow.value
                    appendBaseline = launch.selectedImageList.toList()
                    val existingSlots = appendBaseline.map {
                        EditorMediaSlot.Ready(importId = "existing:${it.uri.value}", image = it)
                    }
                    slots = EditorMediaSlotState.start(
                        importIds = ids,
                        appendTo = existingSlots,
                        focusedImportId = launch.curImageInfo?.let { "existing:${it.uri.value}" }
                            ?: existingSlots.firstOrNull()?.importId,
                        nowMs = nowMonoMs(),
                    )
                    readyPaths.clear()
                    appendBaseline.forEach { readyPaths["existing:${it.uri.value}"] = it.uri.value }
                } else {
                    appendBaseline = emptyList()
                    pendingFreshCleanupPaths += readyPaths.values.filter(IosSourceStager::isOwnedSourcePath)
                    readyPaths.clear()
                    slots = EditorMediaSlotState.start(
                        importIds = ids,
                        appendTo = emptyList(),
                        focusedImportId = null,
                        nowMs = nowMonoMs(),
                    )
                    // No common empty-selection API (allowlist / E3). Fresh pick does **not**
                    // NavigateBack here: the first Ready of this generation replaces Session via
                    // publishEditorSelectionIf. Host export is gated by importInProgress.
                    // Drop controller-only orphans that Session no longer holds.
                    val held = sessionHeldSourcePaths()
                    deletePendingFreshCleanupPaths(excluding = held)
                }
                importInProgress = true
                onImportChromeChanged(true)
                onSlotsChanged(slots)
            }
        }
    }

    private fun handleFileReady(note: NSNotification?) {
        if (closed || !hostAlive()) {
            postAck(note, ok = false, reason = "disposed")
            return
        }
        val generation = note?.userInfoLong(KEY_GENERATION) ?: run {
            postAck(note, ok = false, reason = "missing_generation")
            return
        }
        val importId = note.userInfoString(KEY_IMPORT_ID) ?: run {
            postAck(note, ok = false, reason = "missing_import_id")
            return
        }
        val path = note.userInfoString(KEY_PATH) ?: run {
            postAck(note, ok = false, reason = "missing_path")
            return
        }
        val requestId = note.userInfoString(KEY_REQUEST_ID)
        // Always ACK: body finally + invokeOnCompletion cover pre-entry cancellation of hostScope.
        val ackPosted = AtomicInt(0)
        fun postAckOnce(ok: Boolean, reason: String) {
            if (ackPosted.compareAndSet(0, 1)) {
                postAck(
                    generation = generation,
                    importId = importId,
                    requestId = requestId,
                    ok = ok,
                    reason = reason,
                )
            }
        }
        launchFileReadyJob(
            generation = generation,
            importId = importId,
            provisionalPath = path,
            requestId = requestId,
            postAckOnce = ::postAckOnce,
        )
    }

    /**
     * Production file-ready job body (also the injection seam for pre-body cancel tests).
     * Exactly-once ACK: body finally + [Job.invokeOnCompletion] for pre-entry cancellation.
     */
    private fun launchFileReadyJob(
        generation: Long,
        importId: String,
        provisionalPath: String,
        requestId: String?,
        postAckOnce: (ok: Boolean, reason: String) -> Unit,
    ): Job {
        val job = hostScope.launch(start = CoroutineStart.DEFAULT) {
            try {
                preBodyGateForTests?.await()
                val outcome = mutationMutex.withLock {
                    adoptFileReady(
                        generation = generation,
                        importId = importId,
                        provisionalPath = provisionalPath,
                    )
                }
                // Watermark-region priority: finish focus preview BEFORE ACKing Swift, so
                // firstItemAlone does not start item-1 transfer while item-0 is still decoding.
                val focusPath = outcome.focusPathForPreview
                if (outcome.ack == AdoptionAck.Published && focusPath != null) {
                    try {
                        onFocusReadyForPreview(focusPath)
                    } catch (_: Throwable) {
                        // Preview failure must not block transfer ACK / provisional cleanup.
                    }
                }
                postAckOnce(
                    outcome.ack == AdoptionAck.Published,
                    outcome.ack.name.lowercase(),
                )
            } catch (c: CancellationException) {
                postAckOnce(false, "cancelled")
                throw c
            } catch (t: Throwable) {
                postAckOnce(false, "exception:${t::class.simpleName ?: "error"}")
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                postAckOnce(false, "job_incomplete")
            }
        }
        return job
    }

    private data class AdoptOutcome(
        val ack: AdoptionAck,
        /** Non-null only when this adoption made (or re-confirmed) the focused Ready path. */
        val focusPathForPreview: String? = null,
    )

    /**
     * Test seam: drive the **production** file-ready job (not the direct adopt shortcut) so
     * pre-body cancellation and ACK-once can be injected against the real control plane path.
     */
    internal fun launchProductionFileReadyJobForTests(
        generation: Long,
        importId: String,
        provisionalPath: String,
        requestId: String,
    ): Job {
        val ackPosted = AtomicInt(0)
        fun postAckOnce(ok: Boolean, reason: String) {
            if (ackPosted.compareAndSet(0, 1)) {
                postAck(
                    generation = generation,
                    importId = importId,
                    requestId = requestId,
                    ok = ok,
                    reason = reason,
                )
            }
        }
        return launchFileReadyJob(
            generation = generation,
            importId = importId,
            provisionalPath = provisionalPath,
            requestId = requestId,
            postAckOnce = ::postAckOnce,
        )
    }

    private suspend fun adoptFileReady(
        generation: Long,
        importId: String,
        provisionalPath: String,
    ): AdoptOutcome {
        if (closed || !hostAlive()) return AdoptOutcome(AdoptionAck.Disposed)
        if (generation != activeGeneration) return AdoptOutcome(AdoptionAck.StaleGeneration)
        if (!IosPickGenerationGate.isPhotoCurrent(generation)) {
            return AdoptOutcome(AdoptionAck.StaleGeneration)
        }
        if (!IosSourceStager.isOwnedProvisionalPath(provisionalPath)) {
            return AdoptOutcome(AdoptionAck.InvalidPath)
        }
        me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
            "file_ready",
            generation,
            importId,
        )

        // NonCancellable copy so a prompt cancel cannot drop the destination identity after the
        // native copy succeeds; if we are cancelled immediately after, we still delete below.
        val stablePath = try {
            withContext(Dispatchers.Default + NonCancellable) {
                IosSourceStager.adoptOwnedFile(provisionalPath)
            }
        } catch (_: Throwable) {
            return AdoptOutcome(AdoptionAck.Error)
        }
        // Injection point: cancel after stable copy to prove destination rollback.
        afterStableCopyForTests?.invoke(stablePath)
        if (!currentCoroutineContext().isActive) {
            IosSourceStager.deleteQuietly(stablePath)
            return AdoptOutcome(AdoptionAck.StaleGeneration)
        }

        // Fail closed: undecodable / metadata-less inputs must never become Ready or enter Session.
        val meta = runCatching {
            withContext(Dispatchers.Default) { IosImageIODecoder.metadata(stablePath) }
        }.getOrNull()
        if (meta == null || meta.width < 1 || meta.height < 1) {
            IosSourceStager.deleteQuietly(stablePath)
            if (
                !closed && hostAlive() && generation == activeGeneration &&
                IosPickGenerationGate.isPhotoCurrent(generation) &&
                slots.slot(importId) is EditorMediaSlot.Pending
            ) {
                slots = slots.markFailed(importId, "undecodable")
                onSlotsChanged(slots)
            }
            return AdoptOutcome(AdoptionAck.Error)
        }
        val image = ImageInfo(
            uri = MediaRef(stablePath),
            width = meta.width,
            height = meta.height,
        )

        // Copy/metadata suspended: revalidate ownership before mutating a current generation.
        if (
            closed || !hostAlive() || generation != activeGeneration ||
            !IosPickGenerationGate.isPhotoCurrent(generation)
        ) {
            IosSourceStager.deleteQuietly(stablePath)
            return AdoptOutcome(AdoptionAck.StaleGeneration)
        }
        if (slots.slot(importId) !is EditorMediaSlot.Pending) {
            IosSourceStager.deleteQuietly(stablePath)
            return AdoptOutcome(AdoptionAck.NotPublished)
        }

        // Build ready set in fixed slot order. Ownership is provisional until Session publish
        // succeeds — any throw after this point must roll back readyPaths + delete stablePath.
        readyPaths[importId] = stablePath
        val nextSlots = slots.markReady(importId, image)
        val readyImages = nextSlots.readyImagesInOrder()
        val focusUri = nextSlots.focusedImportId?.let { id ->
            (nextSlots.slot(id) as? EditorMediaSlot.Ready)?.image?.uri
        }
        // Only bind watermark preview when the focused slot is Ready AND this adoption is that
        // focus item. Never fall back to "first Ready" while first is still Pending (that made
        // item-1 paint as the main preview when item-0 was still transferring).
        val focusPathForPreview =
            if (importId == nextSlots.focusedImportId) focusUri?.value else null

        return try {
            val wm = waterMarkProvider()
            val published = publishReadySelectionIf(
                stillValid = {
                    !closed && hostAlive() &&
                        generation == activeGeneration &&
                        IosPickGenerationGate.isPhotoCurrent(generation)
                },
                selected = readyImages,
                waterMark = wm,
                focusUriIfNotFirst = focusUri?.takeIf { readyImages.firstOrNull()?.uri != it },
            )
            if (!published) {
                readyPaths.remove(importId)
                IosSourceStager.deleteQuietly(stablePath)
                return if (
                    generation != activeGeneration ||
                    !IosPickGenerationGate.isPhotoCurrent(generation)
                ) {
                    AdoptOutcome(AdoptionAck.StaleGeneration)
                } else {
                    AdoptOutcome(AdoptionAck.NotPublished)
                }
            }

            slots = nextSlots
            // A fresh replacement is now durable; old app-owned Session sources are unreachable.
            if (!appendMode) deletePendingFreshCleanupPaths()
            onSlotsChanged(slots)
            me.rosuh.easywatermark.session.ImportTimelineProbe.mark(
                "session_adopted",
                generation,
                importId,
            )
            AdoptOutcome(
                ack = AdoptionAck.Published,
                focusPathForPreview = focusPathForPreview,
            )
        } catch (t: Throwable) {
            readyPaths.remove(importId)
            IosSourceStager.deleteQuietly(stablePath)
            throw t
        }
    }

    private fun handleFileFailed(note: NSNotification?) {
        if (closed || !hostAlive()) return
        val generation = note?.userInfoLong(KEY_GENERATION) ?: return
        if (generation != activeGeneration) return
        val importId = note.userInfoString(KEY_IMPORT_ID) ?: return
        val message = note.userInfoString(KEY_MESSAGE) ?: "import failed"
        hostScope.launch {
            mutationMutex.withLock {
                if (closed || generation != activeGeneration) return@withLock
                if (slots.slot(importId) !is EditorMediaSlot.Pending &&
                    slots.slot(importId) !is EditorMediaSlot.Failed
                ) {
                    return@withLock
                }
                slots = slots.markFailed(importId, message)
                onSlotsChanged(slots)
            }
        }
    }

    private fun handleRetryChrome(note: NSNotification?) {
        if (closed || !hostAlive()) return
        val generation = note?.userInfoLong(KEY_GENERATION) ?: return
        if (generation != activeGeneration) return
        val importId = note.userInfoString(KEY_IMPORT_ID) ?: return
        slots = slots.markPendingRetry(importId, nowMs = nowMonoMs())
        importInProgress = true
        onImportChromeChanged(true)
        onSlotsChanged(slots)
    }

    private fun handleFinish(note: NSNotification?) {
        if (closed) return
        val generation = note?.userInfoLong(KEY_GENERATION) ?: return
        if (generation != activeGeneration) return
        importInProgress = false
        onImportChromeChanged(false)
    }

    private fun handleCancel(note: NSNotification?) {
        if (closed) return
        val generation = note?.userInfoLong(KEY_GENERATION) ?: return
        if (generation != activeGeneration) return
        // Drop non-ready progressive slots; keep already-published Ready images.
        val kept = slots.slots.filterIsInstance<EditorMediaSlot.Ready>()
        slots = EditorMediaSlotState(
            slots = kept,
            focusedImportId = slots.focusedImportId?.takeIf { id -> kept.any { it.importId == id } }
                ?: kept.firstOrNull()?.importId,
        )
        importInProgress = false
        onImportChromeChanged(false)
        onSlotsChanged(slots)
        activeGeneration = -1L
    }

    private fun handleRemove(note: NSNotification?) {
        if (closed || !hostAlive()) return
        val importId = note?.userInfoString(KEY_IMPORT_ID) ?: return
        hostScope.launch {
            mutationMutex.withLock { removeSlotLocked(importId) }
        }
    }

    /** Atomic remove: update slots → republish Session (or clear) → drop owned path. */
    suspend fun removeSlot(importId: String): Boolean {
        return mutationMutex.withLock { removeSlotLocked(importId) }
    }

    private suspend fun removeSlotLocked(importId: String): Boolean {
        if (closed || !hostAlive()) return false
        val prior = slots.slot(importId) ?: return false
        // Pending/failed slots were never visible to Session. Removing one is a presentation and
        // Swift-retained-retry-source action only; do not disturb an existing Ready selection.
        if (prior !is EditorMediaSlot.Ready) {
            slots = slots.remove(importId)
            onSlotsChanged(slots)
            postControl(REMOVE_REQUESTED, importId)
            return true
        }
        val removedPath = (prior as? EditorMediaSlot.Ready)?.image?.uri?.value
            ?: readyPaths[importId]
        val next = slots.remove(importId)
        val readyImages = next.readyImagesInOrder()
        val wm = waterMarkProvider()
        val generation = activeGeneration
        if (readyImages.isEmpty()) {
            // Last Ready: Session leave-editor (NavigateBack) then delete owned source that Session
            // no longer holds. No common empty-selection API / E3 mirror writes. Order is fixed
            // under mutationMutex: clear Session → drop controller map → delete file if unheld.
            val cleared = publishReadySelectionIf(
                stillValid = {
                    !closed && hostAlive() && generation == activeGeneration &&
                        IosPickGenerationGate.isPhotoCurrent(generation)
                },
                selected = emptyList(),
                waterMark = wm,
            )
            if (!cleared) return false
            slots = next
            readyPaths.remove(importId)
            onSlotsChanged(slots)
            val stillHeld = sessionHeldSourcePaths()
            removedPath
                ?.takeIf { IosSourceStager.isOwnedSourcePath(it) && it !in stillHeld }
                ?.let(IosSourceStager::deleteQuietly)
            postControl(REMOVE_REQUESTED, importId)
            return true
        }
        val focusUri = next.focusedImportId?.let { id ->
            (next.slot(id) as? EditorMediaSlot.Ready)?.image?.uri
        }
        val published = publishReadySelectionIf(
            stillValid = {
                !closed && hostAlive() && generation == activeGeneration &&
                    IosPickGenerationGate.isPhotoCurrent(generation)
            },
            selected = readyImages,
            waterMark = wm,
            focusUriIfNotFirst = focusUri?.takeIf { readyImages.firstOrNull()?.uri != it },
        )
        if (!published) return false
        slots = next
        readyPaths.remove(importId)
        onSlotsChanged(slots)
        removedPath?.takeIf(IosSourceStager::isOwnedSourcePath)
            ?.let(IosSourceStager::deleteQuietly)
        postControl(REMOVE_REQUESTED, importId)
        // User-driven remove: rebind watermark for the new focus (no transfer ACK to gate).
        scheduleFocusPreview(focusUri?.value ?: readyImages.firstOrNull()?.uri?.value)
        return true
    }

    /**
     * Test seam: drive file-ready without NotificationCenter.
     * When [requestId] is set, posts the same FILE_READY_RESULT bridge payload production uses.
     * Mirrors production: awaits focus watermark-priority bind before ACK when focus becomes Ready.
     */
    internal suspend fun noteFileReadyForTests(
        generation: Long,
        importId: String,
        provisionalPath: String,
        requestId: String? = null,
    ): AdoptionAck {
        // Never reopen a closed controller from the test seam (mirrors production dispose).
        if (closed) {
            if (requestId != null) {
                postAck(generation, importId, requestId, ok = false, reason = "disposed")
            }
            return AdoptionAck.Disposed
        }
        if (activeGeneration < 0L) activeGeneration = generation
        val outcome = try {
            mutationMutex.withLock { adoptFileReady(generation, importId, provisionalPath) }
        } catch (t: Throwable) {
            if (requestId != null) {
                postAck(
                    generation = generation,
                    importId = importId,
                    requestId = requestId,
                    ok = false,
                    reason = "exception:${t::class.simpleName ?: "error"}",
                )
            }
            return AdoptionAck.Error
        }
        val focusPath = outcome.focusPathForPreview
        if (outcome.ack == AdoptionAck.Published && focusPath != null) {
            try {
                onFocusReadyForPreview(focusPath)
            } catch (_: Throwable) {
            }
        }
        if (requestId != null) {
            postAck(
                generation = generation,
                importId = importId,
                requestId = requestId,
                ok = outcome.ack == AdoptionAck.Published,
                reason = outcome.ack.name.lowercase(),
            )
        }
        return outcome.ack
    }

    /** Fire-and-forget focus rebind (user tap / remove). Transfer path awaits via launchFileReadyJob. */
    private fun scheduleFocusPreview(path: String?) {
        if (path.isNullOrBlank() || closed || !hostAlive()) return
        hostScope.launch {
            try {
                onFocusReadyForPreview(path)
            } catch (_: Throwable) {
            }
        }
    }

    internal suspend fun noteFileFailedForTests(
        generation: Long,
        importId: String,
        message: String,
    ): Boolean = mutationMutex.withLock {
        if (generation != activeGeneration || slots.slot(importId) !is EditorMediaSlot.Pending) {
            return@withLock false
        }
        slots = slots.markFailed(importId, message)
        onSlotsChanged(slots)
        true
    }

    internal fun beginForTests(
        generation: Long,
        importIds: List<String>,
        append: Boolean,
    ) {
        activeGeneration = generation
        appendMode = append
        if (!append) {
            readyPaths.clear()
            appendBaseline = emptyList()
            slots = EditorMediaSlotState.start(importIds, emptyList(), null, nowMs = nowMonoMs())
        }
        importInProgress = true
        onImportChromeChanged(true)
        onSlotsChanged(slots)
    }

    /** Shared UI request: only a Failed cell can transition back to Pending. */
    fun requestRetry(importId: String) {
        if (closed || !hostAlive()) return
        hostScope.launch {
            mutationMutex.withLock {
                if (closed || slots.slot(importId) !is EditorMediaSlot.Failed) return@withLock
                slots = slots.markPendingRetry(importId, nowMs = nowMonoMs())
                importInProgress = true
                onImportChromeChanged(true)
                onSlotsChanged(slots)
                postControl(RETRY_REQUESTED, importId)
            }
        }
    }

    /** Shared UI request: move a still-pending cell to the Swift queue's live priority head. */
    fun requestPrioritize(importId: String) {
        if (closed || !hostAlive() || slots.slot(importId) !is EditorMediaSlot.Pending) return
        prioritizeImportId = importId
        postControl(PRIORITIZE_REQUESTED, importId)
    }

    /** Select a Ready cell through the same guarded Session publication path as file-ready. */
    fun requestFocusReady(importId: String) {
        if (closed || !hostAlive()) return
        hostScope.launch {
            mutationMutex.withLock {
                val next = slots.focusReady(importId)
                if (next === slots || next == slots) return@withLock
                val selected = next.readyImagesInOrder()
                val focus = (next.slot(importId) as? EditorMediaSlot.Ready)?.image?.uri ?: return@withLock
                val generation = activeGeneration
                val published = publishReadySelectionIf(
                    stillValid = {
                        !closed && hostAlive() && generation == activeGeneration &&
                            IosPickGenerationGate.isPhotoCurrent(generation)
                    },
                    selected = selected,
                    waterMark = waterMarkProvider(),
                    focusUriIfNotFirst = focus.takeIf { selected.firstOrNull()?.uri != it },
                )
                if (!published) return@withLock
                slots = next
                onSlotsChanged(slots)
                scheduleFocusPreview(focus.value)
            }
        }
    }

    /** Shared UI request; Session mutation and Swift retry-source cleanup happen in that order. */
    fun requestRemove(importId: String) {
        if (closed || !hostAlive()) return
        hostScope.launch { mutationMutex.withLock { removeSlotLocked(importId) } }
    }

    private fun postAck(note: NSNotification?, ok: Boolean, reason: String) {
        val generation = note?.userInfoLong(KEY_GENERATION) ?: return
        val importId = note.userInfoString(KEY_IMPORT_ID) ?: return
        postAck(generation, importId, note.userInfoString(KEY_REQUEST_ID), ok, reason)
    }

    private fun postAck(
        generation: Long,
        importId: String,
        requestId: String?,
        ok: Boolean,
        reason: String,
    ) {
        // Box generation/ok as NSNumber so Swift can read UInt64/Bool without Kotlin Long traps.
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = FILE_READY_RESULT,
            `object` = null,
            userInfo = mapOf(
                // Prefer NSNumber so Swift can read generation/ok via NSNumber.uint64Value/boolValue.
                KEY_GENERATION to NSNumber.numberWithLong(generation),
                KEY_IMPORT_ID to importId,
                KEY_REQUEST_ID to requestId.orEmpty(),
                KEY_OK to NSNumber.numberWithInt(if (ok) 1 else 0),
                KEY_REASON to reason,
            ),
        )
    }

    private fun postControl(name: String, importId: String) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = name,
            `object` = null,
            userInfo = mapOf(
                KEY_GENERATION to NSNumber.numberWithLong(activeGeneration),
                KEY_IMPORT_ID to importId,
            ),
        )
    }

    /**
     * Ready-only Session publication using **existing** common APIs only (no allowlist growth).
     *
     * Non-empty: [WatermarkSessionViewModel.publishEditorSelectionIf] (stillValid under Session
     * mutex). Empty: [AppIntent.NavigateBack] while the caller already holds [mutationMutex], so
     * concurrent adopt/begin on this controller cannot interleave. Does not write the repository
     * selection mirror (E3).
     */
    private suspend fun publishReadySelectionIf(
        stillValid: () -> Boolean,
        selected: List<ImageInfo>,
        waterMark: WaterMark,
        focusUriIfNotFirst: MediaRef? = null,
    ): Boolean {
        if (selected.isNotEmpty()) {
            return session.publishEditorSelectionIf(
                stillValid = stillValid,
                selected = selected,
                waterMark = waterMark,
                focusUriIfNotFirst = focusUriIfNotFirst,
            )
        }
        if (!stillValid()) return false
        session.dispatchAndAwait(AppIntent.NavigateBack)
        // Re-sample after leave-editor. If a newer generation already advanced activeGeneration
        // (serialized under mutationMutex for begin/adopt), report false so the caller keeps
        // its slot/file cleanup decisions conservative.
        return stillValid()
    }

    private fun deletePendingFreshCleanupPaths(excluding: Set<String> = emptySet()) {
        val stale = pendingFreshCleanupPaths.filter { it !in excluding }
        pendingFreshCleanupPaths.clear()
        stale.forEach(IosSourceStager::deleteQuietly)
    }

    companion object {
        const val BEGIN = "me.rosuh.easywatermark.progressive.begin"
        const val FILE_READY = "me.rosuh.easywatermark.progressive.fileReady"
        const val FILE_FAILED = "me.rosuh.easywatermark.progressive.fileFailed"
        const val FINISH = "me.rosuh.easywatermark.progressive.finish"
        const val CANCEL = "me.rosuh.easywatermark.progressive.cancel"
        const val RETRY = "me.rosuh.easywatermark.progressive.retry"
        const val REMOVE = "me.rosuh.easywatermark.progressive.remove"
        const val PRIORITIZE = "me.rosuh.easywatermark.progressive.prioritize"
        const val FILE_READY_RESULT = "me.rosuh.easywatermark.progressive.fileReady.result"
        const val PRIORITIZE_REQUESTED = "me.rosuh.easywatermark.progressive.prioritize.requested"
        const val REMOVE_REQUESTED = "me.rosuh.easywatermark.progressive.remove.requested"
        const val RETRY_REQUESTED = "me.rosuh.easywatermark.progressive.retry.requested"

        const val KEY_GENERATION = "generation"
        const val KEY_IMPORT_ID = "importId"
        const val KEY_IMPORT_IDS = "importIds"
        const val KEY_PATH = "path"
        const val KEY_MESSAGE = "message"
        const val KEY_APPEND = "append"
        const val KEY_OK = "ok"
        const val KEY_REASON = "reason"
        const val KEY_REQUEST_ID = "requestId"
    }
}

private fun NSNotification.userInfoLong(key: String): Long? {
    val raw = userInfo?.get(key) ?: return null
    return when (raw) {
        is Long -> raw
        is Int -> raw.toLong()
        is ULong -> raw.toLong()
        is Number -> raw.toLong()
        else -> raw.toString().toLongOrNull()
    }
}

private fun NSNotification.userInfoString(key: String): String? =
    userInfo?.get(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }

private fun NSNotification.userInfoBool(key: String): Boolean {
    val raw = userInfo?.get(key) ?: return false
    return when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        else -> raw.toString().equals("true", ignoreCase = true)
    }
}

@Suppress("UNCHECKED_CAST")
private fun NSNotification.userInfoStringList(key: String): List<String> {
    val raw = userInfo?.get(key) ?: return emptyList()
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
}
