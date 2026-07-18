package me.rosuh.easywatermark.data.repo

import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS runtime proof that the common [WaterMarkRepository], behind the Swift-facing
 * [IosWatermarkConfigBridge], reads/writes the watermark text, rotation degree,
 * tile mode, alpha, text color, text size, and h/v gaps
 * through the iOS [createWaterMarkDataStore] (`NSDocumentDirectory`) store. RUNS on
 * `iosSimulatorArm64Test`.
 *
 * A unique store name (NSUUID) is used so the initial read is the true default and the test does not
 * collide with the app's default store or other runs (the simulator data container is ephemeral).
 */
class IosWatermarkConfigBridgeTest {

    private fun bridge(name: String) = IosWatermarkConfigBridge(
        WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = name),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        ),
    )

    @Test
    fun bridge_watermark_text_roundtrip() = runBlocking {
        val b = bridge("s4d102_roundtrip_" + NSUUID().UUIDString())

        // Empty store -> the injected default text.
        assertEquals("EasyWatermark 水印", b.currentText(), "default watermark text must be the constant")

        // Write through the shared editor use-case, then read back.
        b.setText("请勿转载")
        assertEquals("请勿转载", b.currentText(), "watermark text must persist after setText")

        // Overwrite again to prove repeated edits persist.
        b.setText("DO NOT REDISTRIBUTE")
        assertEquals("DO NOT REDISTRIBUTE", b.currentText(), "watermark text must persist on re-edit")
    }

    @Test
    fun bridge_watermark_degree_roundtrip() = runBlocking {
        val b = bridge("s4d103_degree_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.degree (matches the prior Swift hardcoded 315.0).
        assertEquals(315f, b.currentDegree(), "default degree must be 315 (fresh-install default)")

        // Write through the shared editor use-case, then read back.
        b.setDegree(90f)
        assertEquals(90f, b.currentDegree(), "degree must persist after setDegree")

        // Repeated edit persists.
        b.setDegree(0f)
        assertEquals(0f, b.currentDegree(), "degree must persist on re-edit")

        // Out-of-range write is clamped by the shared WatermarkConfigRules.clampDegree (0..360).
        b.setDegree(400f)
        assertEquals(360f, b.currentDegree(), "degree must clamp to 360 (shared clamp)")
    }

    @Test
    fun bridge_watermark_tilemode_roundtrip() = runBlocking {
        val b = bridge("s4d104_tilemode_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.tileMode (matches the prior Swift hardcoded REPEAT).
        assertEquals(WatermarkTileMode.REPEAT, b.currentTileMode(), "default tile mode must be REPEAT")

        // Write through the shared editor use-case, then read back (CLAMP = single decal).
        b.setTileMode(WatermarkTileMode.CLAMP)
        assertEquals(WatermarkTileMode.CLAMP, b.currentTileMode(), "tile mode must persist after setTileMode")

        // Switch back to prove repeated edits persist.
        b.setTileMode(WatermarkTileMode.REPEAT)
        assertEquals(WatermarkTileMode.REPEAT, b.currentTileMode(), "tile mode must persist on re-edit")
    }

    @Test
    fun bridge_watermark_alpha_roundtrip() = runBlocking {
        val b = bridge("s4d105_alpha_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.alpha (255 = fully opaque; matches the prior Swift 1.0).
        assertEquals(255, b.currentAlphaByte(), "default alpha byte must be 255 (opaque)")

        // 50% -> byte 127 because alphaPercentToByte = (percent/100*255).toInt() truncates 127.5 -> 127.
        b.setAlphaPercent(50f)
        assertEquals(127, b.currentAlphaByte(), "50% must persist as byte 127 (truncating)")

        // Edges: 0% -> 0, 100% -> 255.
        b.setAlphaPercent(0f)
        assertEquals(0, b.currentAlphaByte(), "0% must persist as byte 0")
        b.setAlphaPercent(100f)
        assertEquals(255, b.currentAlphaByte(), "100% must persist as byte 255")
    }

    @Test
    fun bridge_watermark_textcolor_roundtrip() = runBlocking {
        val b = bridge("s4d107_color_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textColor (#FFB800 amber). NOTE: this is the value the iOS
        // render now uses on a fresh install, replacing the prior hardcoded white (an alignment, not a
        // default-preserving change).
        assertEquals(0xFFFFB800.toInt(), b.currentTextColor(), "default text color must be amber #FFB800")

        // Write through the shared editor use-case, then read back.
        b.setTextColor(0xFFFFFFFF.toInt())
        assertEquals(0xFFFFFFFF.toInt(), b.currentTextColor(), "text color must persist as white")

        // Second value to prove repeated edits persist.
        b.setTextColor(0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), b.currentTextColor(), "text color must persist as black on re-edit")
    }

    @Test
    fun bridge_watermark_textsize_roundtrip() = runBlocking {
        val b = bridge("s4d109_size_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textSize (14). NOTE: this is the value the iOS render now uses
        // on a fresh install, replacing the prior hardcoded 24 (an alignment, not default-preserving).
        assertEquals(14f, b.currentTextSize(), "default text size must be 14 (fresh-install render size)")

        // Write through the shared editor use-case, then read back.
        b.setTextSize(30f)
        assertEquals(30f, b.currentTextSize(), "text size must persist after setTextSize")

        // Clamp floor: a 0 write is stored 0 (editor coerceAtLeast(0f)) but the repo read clamps to >= 1.
        b.setTextSize(0f)
        assertEquals(1f, b.currentTextSize(), "text size read must clamp to the 1 floor (MIN_TEXT_SIZE)")
    }

    @Test
    fun bridge_watermark_gap_roundtrip() = runBlocking {
        val b = bridge("s4d110_gap_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default gaps (0/0). NOTE: this is what the iOS render now uses on a
        // fresh install, replacing the prior hardcoded 40/40 (an alignment, denser tiling).
        assertEquals(0, b.currentHGap(), "default hGap must be 0 (fresh-install render)")
        assertEquals(0, b.currentVGap(), "default vGap must be 0 (fresh-install render)")

        // Write a representative non-default value through the shared editor, then read back.
        b.setHGap(40)
        b.setVGap(40)
        assertEquals(40, b.currentHGap(), "hGap must persist as 40")
        assertEquals(40, b.currentVGap(), "vGap must persist as 40")

        // Clamp: negative -> 0, over max -> 500 (WatermarkConfigRules clamps 0..500).
        b.setHGap(-5)
        assertEquals(0, b.currentHGap(), "hGap must clamp negative to 0")
        b.setVGap(600)
        assertEquals(500, b.currentVGap(), "vGap must clamp over-max to 500")
    }

    @Test
    fun bridge_watermark_typeface_roundtrip() = runBlocking {
        val b = bridge("s4d112_typeface_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textTypeface (Normal; preserves the prior regular iOS output).
        assertEquals(TextTypeface.Normal, b.currentTextTypeface(), "default typeface must be Normal")

        // Each of the four values persists and reads back through the shared editor.
        for (tf in listOf(TextTypeface.Italic, TextTypeface.Bold, TextTypeface.BoldItalic, TextTypeface.Normal)) {
            b.setTextTypeface(tf)
            assertEquals(tf, b.currentTextTypeface(), "typeface must persist as $tf")
        }
    }

    /**
 * The iOS renderer honors all four typefaces — each renders a visible (non-blank) text cell. * Uses the system font (FontFamily.Default) so bold/italic are Compose **synthetic** (faux-bold/italic),
 * mirroring Android's synthesis intent; this is perceptual, not byte-parity. Cheap: one small cell each.
     */
    @Test
    fun renderer_honors_each_typeface_nonblank() {
        for (tf in listOf(TextTypeface.Normal, TextTypeface.Italic, TextTypeface.Bold, TextTypeface.BoldItalic)) {
            val cell = IosWatermarkRenderer.renderTextCell(text = "Ag", textSize = 48f, typeface = tf)
            val pixels = cell.toPixelMap()
            var visible = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].alpha > 0f) visible++
            assertTrue(visible > 0, "typeface $tf must render visible text pixels (visible=$visible)")
        }
    }

    @Test
    fun bridge_watermark_textstyle_roundtrip() = runBlocking {
        val b = bridge("s4d113_textstyle_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.textStyle (Fill; preserves the prior filled iOS output).
        assertEquals(TextPaintStyle.Fill, b.currentTextStyle(), "default text style must be Fill")

        // Stroke persists and reads back through the shared editor.
        b.setTextStyle(TextPaintStyle.Stroke)
        assertEquals(TextPaintStyle.Stroke, b.currentTextStyle(), "text style must persist as Stroke")

        // Switch back to prove repeated edits persist.
        b.setTextStyle(TextPaintStyle.Fill)
        assertEquals(TextPaintStyle.Fill, b.currentTextStyle(), "text style must persist as Fill on re-edit")
    }

    /**
 * The iOS renderer honors both paint styles — each renders a visible (non-blank) text cell. * Stroke maps to a Compose `Stroke()` (default width 0 = Skia hairline), mirroring Android's stroked
 * text (`Paint.Style.STROKE` at the default strokeWidth 0); this is perceptual Skiko honoring, not
 * byte-parity. Cheap: one small cell each.
     */
    @Test
    fun renderer_honors_each_textstyle_nonblank() {
        for (style in listOf(TextPaintStyle.Fill, TextPaintStyle.Stroke)) {
            val cell = IosWatermarkRenderer.renderTextCell(text = "Ag", textSize = 48f, textStyle = style)
            val pixels = cell.toPixelMap()
            var visible = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].alpha > 0f) visible++
            assertTrue(visible > 0, "text style $style must render visible text pixels (visible=$visible)")
        }
    }

    /**
 * Persist picked icon bytes (Option A) and prove the full durable-icon contract on the iOS * runtime: empty-store defaults → write copies bytes to an app-private path + flips mode to Image →
 * the bytes read back identically → replacement stores new bytes AND cleans up the prior owned file.
     */
    @Test
    fun bridge_icon_persistence_roundtrip_and_replacement_cleanup() = runBlocking {
        val b = bridge("s4d116_icon_" + NSUUID().UUIDString())

        // Empty store -> no icon, Text mode.
        assertEquals(MediaRef.Empty, b.currentIconRef(), "default icon ref must be Empty")
        assertEquals(WatermarkMode.Text, b.currentMarkMode(), "default mark mode must be Text")

        // Persist icon bytes -> ref becomes a non-empty, helper-owned app-private path; mode flips to Image.
        val first = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        b.setIconFromBytes(first)
        val ref1 = b.currentIconRef()
        assertTrue(ref1.value.isNotEmpty(), "icon ref must be non-empty after setIconFromBytes")
        assertTrue(IosIconPersistence.isOwned(ref1.value), "persisted path must be helper-owned")
        assertEquals(WatermarkMode.Image, b.currentMarkMode(), "mark mode must be Image after setIconFromBytes")
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(ref1.value),
            "persisted icon file must exist on disk (durable app-private path)",
        )
        // The bytes read back from the persisted MediaRef path are byte-identical.
        assertTrue(
            IosIconPersistence.readIconBytes(ref1).contentEquals(first),
            "persisted icon bytes must read back identically from the MediaRef path",
        )

        // Replace with new bytes -> a NEW path holding the new bytes, and the prior owned file is removed.
        val second = byteArrayOf(9, 8, 7, 6, 5)
        b.setIconFromBytes(second)
        val ref2 = b.currentIconRef()
        assertTrue(ref2.value.isNotEmpty() && ref2.value != ref1.value, "replacement must store a NEW path")
        assertTrue(
            IosIconPersistence.readIconBytes(ref2).contentEquals(second),
            "replacement bytes must read back from the new path",
        )
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(ref1.value),
            "the prior helper-owned icon file must be cleaned up on replacement",
        )
    }

    /** S4d-116: empty icon bytes fail loudly (no unusable file, no ref change). */
    @Test
    fun bridge_setIconFromBytes_rejects_empty() = runBlocking {
        val b = bridge("s4d116_empty_" + NSUUID().UUIDString())
        assertFailsWith<IllegalArgumentException> { b.setIconFromBytes(ByteArray(0)) }
        // The empty write must not have changed the persisted state.
        assertEquals(MediaRef.Empty, b.currentIconRef(), "icon ref must stay Empty after a rejected empty write")
        assertEquals(WatermarkMode.Text, b.currentMarkMode(), "mark mode must stay Text after a rejected empty write")
    }

    /**
 * (revision): ownership is more than a prefix match. A real generated path is owned, but a
 * Traversal (`icon_/../../foreign`) or a nested/sibling path (`icon_x/foreign`) — which share the * `icon_` prefix but add a path separator — and an empty-filename path are NOT owned, so
 * `deleteIfOwned` ignores them and can never delete an arbitrary path. Proven on the iOS runtime.
     */
    @Test
    fun iconPersistence_ownership_rejects_traversal_and_nested_suffixes() {
        // A normal generated path is owned.
        val ownedPath = IosIconPersistence.writeIconBytes(byteArrayOf(1, 2, 3))
        assertTrue(IosIconPersistence.isOwned(ownedPath), "a normal generated icon path must be owned")

        // Derive the helper prefix (`…/watermark_icons/icon_`) from the generated path to craft siblings.
        val marker = "icon_"
        val prefix = ownedPath.substring(0, ownedPath.lastIndexOf(marker) + marker.length)
        val traversal = prefix + "/../../foreign"   // …/watermark_icons/icon_/../../foreign
        val nested = prefix + "x/foreign"            // …/watermark_icons/icon_x/foreign
        val emptySuffix = prefix                     // …/watermark_icons/icon_ (no filename)

        assertFalse(IosIconPersistence.isOwned(traversal), "a '..' traversal suffix must NOT be owned")
        assertFalse(IosIconPersistence.isOwned(nested), "a nested-path suffix (extra '/') must NOT be owned")
        assertFalse(IosIconPersistence.isOwned(emptySuffix), "an empty filename suffix must NOT be owned")
        assertFalse(IosIconPersistence.isOwned(""), "an empty path must NOT be owned")

        // deleteIfOwned must ignore every non-owned shape (no throw) and leave the real owned file intact.
        IosIconPersistence.deleteIfOwned(traversal)
        IosIconPersistence.deleteIfOwned(nested)
        IosIconPersistence.deleteIfOwned(emptySuffix)
        IosIconPersistence.deleteIfOwned("")
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(ownedPath),
            "deleteIfOwned on non-owned paths must not delete anything (the real icon file survives)",
        )

        // The owned path IS deletable (sanity: the predicate is not simply rejecting everything) + tidy up.
        IosIconPersistence.deleteIfOwned(ownedPath)
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(ownedPath),
            "deleteIfOwned must remove a genuinely owned file",
        )
    }

    /**
 * The render workflow reads the persisted icon as **bytes** through the bridge (the file path * never crosses to Swift). Null when no icon is set; the exact persisted bytes after `setIconFromBytes`.
     */
    @Test
    fun bridge_currentIconBytes_reads_persisted_bytes() = runBlocking {
        val b = bridge("s4d117_iconbytes_" + NSUUID().UUIDString())

        // No icon set -> null (Swift then surfaces a failure in Image mode rather than rendering text).
        assertNull(b.currentIconBytes(), "currentIconBytes must be null when no icon is persisted")

        // Persist icon bytes -> currentIconBytes returns exactly those bytes (mode also flips to Image).
        val bytes = byteArrayOf(10, 20, 30, 40, 50, 60)
        b.setIconFromBytes(bytes)
        assertEquals(WatermarkMode.Image, b.currentMarkMode(), "mode must be Image after setIconFromBytes")
        val read = b.currentIconBytes()
        assertTrue(read != null && read.contentEquals(bytes), "currentIconBytes must return the persisted icon bytes")
    }
}
