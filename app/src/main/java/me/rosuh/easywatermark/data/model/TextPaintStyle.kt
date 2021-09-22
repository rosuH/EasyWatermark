package me.rosuh.easywatermark.data.model

import android.graphics.Paint
import android.widget.TextView

sealed class TextPaintStyle : SerializableSealClass<Int> {

    abstract fun applyStyle(tv: TextView?)

    abstract fun obtainSysStyle(): Paint.Style

    object Fill : TextPaintStyle() {
        override fun applyStyle(tv: TextView?) {
            tv?.paint?.style = Paint.Style.FILL
        }

        override fun obtainSysStyle(): Paint.Style {
            return Paint.Style.FILL
        }

        override fun serializeKey(): Int {
            return 0
        }
    }

    object Stroke : TextPaintStyle() {
        override fun applyStyle(tv: TextView?) {
            tv?.paint?.style = Paint.Style.STROKE
        }

        override fun obtainSysStyle(): Paint.Style {
            return Paint.Style.STROKE
        }

        override fun serializeKey(): Int {
            return 1
        }
    }

    companion object {
        fun obtainSealedClass(key: Int): TextPaintStyle {
            return when (key) {
                0 -> Fill
                1 -> Stroke
                else -> throw IllegalArgumentException("No such key for TextPaintStyle")
            }
        }
    }
}
