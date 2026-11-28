package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StickerCatalogTest {

    @Test
    fun empty_text_is_empty_list() {
        assertEquals(emptyList(), StickerCatalog.decode(""))
        assertEquals(emptyList(), StickerCatalog.decode("\n"))
    }

    @Test
    fun round_trip_keeps_asset() {
        val sticker = Sticker(
            id = "a",
            icon = MediaRef("watermark_icons/icon-a.png"),
        )
        val decoded = StickerCatalog.decode(StickerCatalog.encode(listOf(sticker)))
        assertEquals(listOf(sticker), decoded)
    }

    @Test
    fun add_then_remove_round_trips() {
        val sticker = Sticker(
            id = "a",
            icon = MediaRef("watermark_icons/icon-a.png"),
        )
        val saved = StickerCatalog.add("", sticker)
        assertEquals(listOf(sticker), StickerCatalog.decode(saved))
        assertEquals("", StickerCatalog.remove(saved, "a"))
    }

    @Test
    fun legacy_ten_field_line_decodes_asset_fields() {
        val legacyLine = listOf(
            "legacy-1",
            "watermark_icons/icon-legacy.png",
            "21.0",
            "128",
            "15.0",
            "0",
            "10",
            "20",
            "0.25",
            "0.75",
        ).joinToString("\u001f")
        assertEquals(
            listOf(Sticker(id = "legacy-1", icon = MediaRef("watermark_icons/icon-legacy.png"))),
            StickerCatalog.decode(legacyLine),
        )
    }

    @Test
    fun bad_line_fails() {
        assertFailsWith<IllegalArgumentException> {
            StickerCatalog.decode("only-one-field")
        }
    }

    @Test
    fun add_prepends_new_sticker_first_and_rejects_duplicate_id() {
        val first = Sticker(id = "a", icon = MediaRef("watermark_icons/icon-a.png"))
        val second = Sticker(id = "b", icon = MediaRef("watermark_icons/icon-b.png"))
        val saved = StickerCatalog.add(StickerCatalog.add("", first), second)
        assertEquals(listOf(second, first), StickerCatalog.decode(saved))
        assertFailsWith<IllegalArgumentException> {
            StickerCatalog.add(saved, first)
        }
    }

    @Test
    fun markUsed_moves_target_to_index_zero_and_no_ops_when_missing_or_first() {
        val a = Sticker(id = "a", icon = MediaRef("watermark_icons/icon-a.png"))
        val b = Sticker(id = "b", icon = MediaRef("watermark_icons/icon-b.png"))
        val c = Sticker(id = "c", icon = MediaRef("watermark_icons/icon-c.png"))
        val initial = StickerCatalog.encode(listOf(a, b, c))

        assertEquals(initial, StickerCatalog.markUsed(initial, "a"))
        assertEquals(initial, StickerCatalog.markUsed(initial, "missing"))
        assertEquals("", StickerCatalog.markUsed("", "a"))

        val movedC = StickerCatalog.markUsed(initial, "c")
        assertEquals(listOf(c, a, b), StickerCatalog.decode(movedC))

        val movedB = StickerCatalog.markUsed(movedC, "b")
        assertEquals(listOf(b, c, a), StickerCatalog.decode(movedB))
    }

    @Test
    fun removeAll_batch_removes_ids_and_empty_set_is_no_op() {
        val a = Sticker(id = "a", icon = MediaRef("watermark_icons/icon-a.png"))
        val b = Sticker(id = "b", icon = MediaRef("watermark_icons/icon-b.png"))
        val c = Sticker(id = "c", icon = MediaRef("watermark_icons/icon-c.png"))
        val initial = StickerCatalog.encode(listOf(a, b, c))

        assertEquals(initial, StickerCatalog.removeAll(initial, emptySet()))
        assertEquals(initial, StickerCatalog.removeAll(initial, setOf("missing")))

        val remaining = StickerCatalog.removeAll(initial, setOf("a", "c"))
        assertEquals(listOf(b), StickerCatalog.decode(remaining))

        assertEquals("", StickerCatalog.removeAll(remaining, setOf("b")))
    }
}
