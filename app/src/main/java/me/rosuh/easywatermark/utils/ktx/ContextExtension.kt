package me.rosuh.easywatermark.utils.ktx

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.MainActivity
import me.rosuh.easywatermark.ui.theme.ThemeManager
import me.rosuh.easywatermark.ui.widget.RadioButton

fun Activity.isStoragePermissionGrated(): Boolean {
    val readGranted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    val writeGranted =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    return readGranted && writeGranted
}

fun Activity.preCheckStoragePermission(block: () -> Unit) {
    if (isStoragePermissionGrated()) {
        block.invoke()
    } else {
        requestPermission()
    }
}

/**
 * 申请权限
 */
fun Activity.requestPermission() {
    ActivityCompat.requestPermissions(
        this,
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ),
        MainActivity.REQ_CODE_REQ_WRITE_PERMISSION
    )
}


inline fun ViewModel.launch(crossinline action: suspend CoroutineScope.() -> Unit): Job {
    return viewModelScope.launch {
        action()
    }
}

/**
 * 主要角色用于整个UI的关键组件，如FAB、突出按钮、活动状态以及高阴影表面的色调。
 */
val Context.colorPrimary: Int
    get() {
        return ThemeManager.colorPrimary
    }

val Context.colorOnPrimary: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnPrimary)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnPrimary)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onPrimary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onPrimary)
            }
        }
    }

val Context.colorPrimaryContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorPrimaryContainer)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorPrimaryContainer)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_primaryContainer)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_primaryContainer)
            }
        }
    }

val Context.colorOnPrimaryContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnPrimaryContainer)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnPrimaryContainer)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onPrimaryContainer)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onPrimaryContainer)
            }
        }
    }

/**
 * 用于UI中不太突出的组件，如过 chips，同时扩展了颜色表达的机会。
 * Toolbar icon, TabLayout
 */
val Context.colorSecondary: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorSecondary)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSecondary)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_secondary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_secondary)
            }
        }
    }

val Context.colorOnSecondary: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnSecondary)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSecondary)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_secondary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_secondary)
            }
        }
    }

val Context.colorSecondaryContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorSecondaryContainer)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSecondaryContainer)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_secondary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_secondary)
            }
        }
    }

val Context.colorOnSecondaryContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnSecondaryContainer)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSecondaryContainer)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_secondary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_secondary)
            }
        }
    }

/**
 * 用于对比重音，这些重音可用于平衡主色和次色，或提高对元素(如输入字段)的注意。
 */
val Context.colorTertiary: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorTertiary)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorTertiary)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_tertiary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_tertiary)
            }
        }
    }

val Context.colorOnTertiary: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnTertiary)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnTertiary)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onTertiary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onTertiary)
            }
        }
    }

val Context.colorTertiaryContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorTertiaryContainer)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorTertiaryContainer)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_tertiaryContainer)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_tertiaryContainer)
            }
        }
    }

val Context.colorOnTertiaryContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnTertiaryContainer)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnTertiaryContainer)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onTertiaryContainer)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onTertiaryContainer)
            }
        }
    }

val Context.colorError: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorError)
            }
            isNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_error)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_error)
            }
        }
    }

val Context.colorOnError: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnError)
            }
            isNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onError)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onError)
            }
        }
    }

val Context.colorErrorContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorErrorContainer)
            }
            isNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_errorContainer)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_errorContainer)
            }
        }
    }

val Context.colorOnErrorContainer: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnErrorContainer)
            }
            isNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onErrorContainer)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onErrorContainer)
            }
        }
    }

val Context.colorBackground: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.backgroundColor)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.backgroundColor)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_background)
            }
        }
    }

val Context.colorOnBackground: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnBackground)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnBackground)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onBackground)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onBackground)
            }
        }
    }

val Context.colorSurface: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorSurface)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSurface)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_surface)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_surface)
            }
        }
    }

val Context.colorOnSurface: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnSurface)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSurface)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onSurface)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onSurface)
            }
        }
    }

val Context.colorSurfaceVariant: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorSurfaceVariant)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSurfaceVariant)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_surfaceVariant)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_surfaceVariant)
            }
        }
    }

val Context.colorOnSurfaceVariant: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnSurfaceVariant)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSurfaceVariant)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_onSurfaceVariant)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_onSurfaceVariant)
            }
        }
    }

val Context.colorOutline: Int
    get() {
        return when {
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOutline)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOutline)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_outline)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_outline)
            }
        }
    }


fun Context.isNight(): Boolean {
    return when (this.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true // Night mode is not active, we're using the light theme
        Configuration.UI_MODE_NIGHT_NO -> false // Night mode is active, we're using dark theme
        else -> false
    }
}

fun Context.supportNight(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}

fun Context.supportDynamicColor(): Boolean {
    return CMonet.isDynamicColorAvailable()
}

@ColorInt
fun Context.getColorFromAttr(
    @AttrRes attrColor: Int,
    typedValue: TypedValue = TypedValue(),
    resolveRefs: Boolean = true
): Int {
    theme.resolveAttribute(attrColor, typedValue, resolveRefs)
    return typedValue.data
}