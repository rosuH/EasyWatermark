package me.rosuh.cmonet

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

class SimpleSp(
    context: Context
) : IStorage {

    companion object {
        private const val TAG = "SimpleSp"

        private const val SP_NAME = "sp_water_mark_c_monet"
    }

    private val sp = context.getSharedPreferences(SP_NAME, MODE_PRIVATE)

    inline fun <reified T> SharedPreferences.Editor.put(key: String, value: T) {
        when (T::class.java) {
            Int::class.java -> putInt(key, value as Int)
            Float::class.java -> putFloat(key, value as Float)
            Boolean::class.java -> putBoolean(key, value as Boolean)
            Long::class.java -> putLong(key, value as Long)
            String::class.java -> putString(key, value as String)
            else -> throw IllegalArgumentException("Unsupported type.")
        }
    }

    override fun <T> save(key: String, value: T) {
        Log.i(TAG, "save: $key, $value")
        when (value) {
            is Int -> {
                sp.edit {
                    putInt(key, value)
                }
            }
            is Float -> {
                sp.edit {
                    putFloat(key, value)
                }
            }
            is String -> {
                sp.edit {
                    putString(key, value)
                }
            }
            is Long -> {
                sp.edit {
                    putLong(key, value)
                }
            }
            is Boolean -> {
                sp.edit {
                    putBoolean(key, value)
                }
            }
            else -> {
                throw IllegalArgumentException("Not support such type yet!")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getValue(key: String, defaultValue: T): T {
        Log.i(TAG, "getValue: $key, $defaultValue")
        when (defaultValue) {
            is Int -> {
                return sp.getInt(key, defaultValue) as T
            }
            is Float -> {
                return sp.getFloat(key, defaultValue) as T
            }
            is String -> {
                return sp.getString(key, defaultValue) as T
            }
            is Long -> {
                return sp.getLong(key, defaultValue) as T
            }
            is Boolean -> {
                return sp.getBoolean(key, defaultValue) as T
            }
            else -> {
                throw IllegalArgumentException("Not support such type yet!")
            }
        }
    }
}
