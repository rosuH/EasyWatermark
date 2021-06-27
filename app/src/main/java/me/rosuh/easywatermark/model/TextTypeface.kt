package me.rosuh.easywatermark.model

import android.widget.TextView

/**
 * Sealed for [android.graphics.Typeface]
 * @author rosuh
 * @date 2021/6/27
 */
sealed class TextTypeface : SerializableSealClass<Int> {

    abstract fun applyStyle(tv: TextView?)

    abstract fun obtainSysTypeface(): Int


    object Normal : TextTypeface() {
        override fun applyStyle(tv: TextView?) {
            tv?.setTypeface(tv.typeface, android.graphics.Typeface.NORMAL)
        }

        override fun obtainSysTypeface(): Int {
            return android.graphics.Typeface.NORMAL
        }

        override fun serializeKey(): Int {
            return 0
        }
    }

    object Italic : TextTypeface() {
        override fun applyStyle(tv: TextView?) {
            tv?.setTypeface(tv.typeface, android.graphics.Typeface.ITALIC)
        }

        override fun obtainSysTypeface(): Int {
            return android.graphics.Typeface.ITALIC
        }


        override fun serializeKey(): Int {
            return 1
        }
    }

    object Bold : TextTypeface() {
        override fun applyStyle(tv: TextView?) {
            tv?.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        }

        override fun obtainSysTypeface(): Int {
            return android.graphics.Typeface.BOLD
        }

        override fun serializeKey(): Int {
            return 2
        }
    }

    object BoldItalic : TextTypeface() {
        override fun applyStyle(tv: TextView?) {
            tv?.setTypeface(tv.typeface, android.graphics.Typeface.BOLD_ITALIC)
        }

        override fun obtainSysTypeface(): Int {
            return android.graphics.Typeface.BOLD_ITALIC
        }

        override fun serializeKey(): Int {
            return 3
        }
    }

    companion object {
        fun obtainSealedClass(key: Int): TextTypeface {
            return when (key) {
                0 -> Normal
                1 -> Italic
                2 -> Bold
                3 -> BoldItalic
                else -> throw IllegalArgumentException("No such key for TextTypeface")
            }
        }
    }
}