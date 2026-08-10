package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.WatermarkMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorInspectorFormFieldsTest {

    @Test
    fun content_text_mode_only_text_field() {
        assertEquals(listOf(FuncType.Text), EditorInspectorFormFields.contentFields(WatermarkMode.Text))
    }

    @Test
    fun content_image_mode_only_icon_field() {
        assertEquals(listOf(FuncType.Icon), EditorInspectorFormFields.contentFields(WatermarkMode.Image))
    }

    @Test
    fun content_modes_are_mutually_exclusive() {
        val text = EditorInspectorFormFields.contentFields(WatermarkMode.Text).toSet()
        val image = EditorInspectorFormFields.contentFields(WatermarkMode.Image).toSet()
        assertTrue(text.intersect(image).isEmpty())
        assertFalse(text.contains(FuncType.Icon))
        assertFalse(image.contains(FuncType.Text))
    }

    @Test
    fun style_and_layout_match_catalog_order() {
        assertEquals(
            EditorOptionCatalog.style.map { it.type },
            EditorInspectorFormFields.styleFields(),
        )
        assertEquals(
            EditorOptionCatalog.layout.map { it.type },
            EditorInspectorFormFields.layoutFields(),
        )
    }

    @Test
    fun form_slider_contract() {
        assertTrue(EditorInspectorFormFields.isFormSlider(FuncType.TextSize))
        assertTrue(EditorInspectorFormFields.isFormSlider(FuncType.Alpha))
        assertTrue(EditorInspectorFormFields.isFormSlider(FuncType.Degree))
        assertTrue(EditorInspectorFormFields.isFormSlider(FuncType.Horizon))
        assertTrue(EditorInspectorFormFields.isFormSlider(FuncType.Vertical))
        assertFalse(EditorInspectorFormFields.isFormSlider(FuncType.TileMode))
        assertFalse(EditorInspectorFormFields.isFormSlider(FuncType.Color))
        assertFalse(EditorInspectorFormFields.isFormSlider(FuncType.Text))
    }
}
