package me.rosuh.easywatermark.utils.ktx

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.MainActivity

fun Activity.isStoragePermissionGrated(): Boolean {
    val readGranted =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
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
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary80)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary40)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_primary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_primary)
            }
        }
    }

val Context.colorOnPrimary: Int
    get() {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary20)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary100)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary20)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary90)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary90)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_primary10)
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
val Context.colorSecondly: Int
    get() {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary80)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary40)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_secondary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_secondary)
            }
        }
    }

val Context.colorOnSecondly: Int
    get() {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary20)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary100)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_secondary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_secondary)
            }
        }
    }

val Context.colorSecondlyContainer: Int
    get() {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary30)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary90)
            }
            isNight() || !supportNight() -> {
                ContextCompat.getColor(this, R.color.md_theme_dark_secondary)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_secondary)
            }
        }
    }

val Context.colorOnSecondlyContainer: Int
    get() {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary90)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_secondary10)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary80)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary40)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary20)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary100)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary30)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary90)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary90)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_tertiary10)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral10)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral99)
            }
            isNight() || !supportNight() -> {
                // default color is dark :)
                ContextCompat.getColor(this, R.color.md_theme_dark_background)
            }
            else -> {
                ContextCompat.getColor(this, R.color.md_theme_light_background)
            }
        }
    }

val Context.colorOnBackground: Int
    get() {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral90)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral10)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral10)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral99)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral80)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral10)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral_variant30)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral_variant90)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral_variant80)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral_variant30)
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
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isNight() -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral_variant60)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                ContextCompat.getColor(this, R.color.material_dynamic_neutral_variant50)
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
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}