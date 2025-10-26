package me.rosuh.easywatermark.utils.ktx

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.R

fun Activity.isStoragePermissionGrated(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

fun Activity.checkReadingPermission(
    activityResultLauncher: ActivityResultLauncher<String>,
    failed: (msg: String) -> Unit = {
        Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
    },
    grant: () -> Unit,
) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    preCheckStoragePermission(activityResultLauncher, permissions, failed, grant)
}

fun Activity.checkWritingPermission(
    activityResultLauncher: ActivityResultLauncher<String>,
    failed: (msg: String) -> Unit = {
        Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
    },
    grant: () -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        grant.invoke()
        return
    }
    preCheckStoragePermission(activityResultLauncher, Manifest.permission.WRITE_EXTERNAL_STORAGE, failed, grant)
}

fun Activity.preCheckStoragePermission(
    activityResultLauncher: ActivityResultLauncher<String>,
    permission: String,
    failed: (msg: String) -> Unit = {
        Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
    },
    grant: () -> Unit,
) {
    if (isStoragePermissionGrated(permission)) {
        grant.invoke()
        return
    }
    kotlin.runCatching {
        activityResultLauncher.launch(permission)
    }.getOrElse {
        failed.invoke(it.message ?: "Unknown error")
    }
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
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorPrimary, defaultColor = R.color.material_dynamic_primary80)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorPrimary, defaultColor = R.color.material_dynamic_primary40)
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
            CMonet.isDynamicColorAvailable() && isNight() -> {
                getColorFromAttr(R.attr.colorOnPrimary, defaultColor = R.color.material_dynamic_primary20)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnPrimary, defaultColor = R.color.material_dynamic_primary100)
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
                getColorFromAttr(R.attr.colorPrimaryContainer, defaultColor = R.color.material_dynamic_primary20)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorPrimaryContainer, defaultColor = R.color.material_dynamic_primary90)
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
                getColorFromAttr(R.attr.colorOnPrimaryContainer, defaultColor = R.color.material_dynamic_primary90)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnPrimaryContainer, defaultColor = R.color.material_dynamic_primary10)
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
                getColorFromAttr(R.attr.colorSecondary, defaultColor = R.color.material_dynamic_secondary80)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSecondary, defaultColor = R.color.material_dynamic_secondary40)
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
                getColorFromAttr(R.attr.colorOnSecondary, defaultColor = R.color.material_dynamic_secondary20)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSecondary, defaultColor = R.color.material_dynamic_secondary100)
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
                getColorFromAttr(R.attr.colorSecondaryContainer, defaultColor = R.color.material_dynamic_secondary30)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSecondaryContainer, defaultColor = R.color.material_dynamic_secondary90)
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
                getColorFromAttr(R.attr.colorOnSecondaryContainer, defaultColor = R.color.material_dynamic_secondary90)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSecondaryContainer, defaultColor = R.color.material_dynamic_secondary10)
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
                getColorFromAttr(R.attr.colorTertiary, defaultColor = R.color.material_dynamic_tertiary80)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorTertiary, defaultColor = R.color.material_dynamic_tertiary40)
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
                getColorFromAttr(R.attr.colorOnTertiary, defaultColor = R.color.material_dynamic_tertiary20)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnTertiary, defaultColor = R.color.material_dynamic_tertiary100)
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
                getColorFromAttr(R.attr.colorTertiaryContainer, defaultColor = R.color.material_dynamic_tertiary30)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorTertiaryContainer, defaultColor = R.color.material_dynamic_tertiary90)
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
                getColorFromAttr(R.attr.colorOnTertiaryContainer, defaultColor = R.color.material_dynamic_tertiary90)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnTertiaryContainer, defaultColor = R.color.material_dynamic_tertiary10)
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
                getColorFromAttr(R.attr.colorError, defaultColor = R.color.design_default_color_error)
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
                getColorFromAttr(R.attr.colorOnError, defaultColor = R.color.design_default_color_on_error)
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
                getColorFromAttr(R.attr.colorErrorContainer, defaultColor = R.color.md_theme_dark_errorContainer)
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
                getColorFromAttr(R.attr.colorOnErrorContainer, defaultColor = R.color.md_theme_dark_onErrorContainer)
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
                getColorFromAttr(R.attr.backgroundColor, defaultColor = R.color.material_dynamic_neutral10)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.backgroundColor, defaultColor = R.color.material_dynamic_neutral99)
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
                getColorFromAttr(R.attr.colorOnBackground, defaultColor = R.color.material_dynamic_neutral90)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnBackground, defaultColor = R.color.material_dynamic_neutral10)
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
                getColorFromAttr(R.attr.colorSurface, defaultColor = R.color.material_dynamic_neutral10)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSurface, defaultColor = R.color.material_dynamic_neutral99)
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
                getColorFromAttr(R.attr.colorOnSurface, defaultColor = R.color.material_dynamic_neutral80)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSurface, defaultColor = R.color.material_dynamic_neutral10)
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
                getColorFromAttr(R.attr.colorSurfaceVariant, defaultColor = R.color.material_dynamic_neutral30)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorSurfaceVariant, defaultColor = R.color.material_dynamic_neutral90)
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
                getColorFromAttr(R.attr.colorOnSurfaceVariant, defaultColor = R.color.material_dynamic_neutral80)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOnSurfaceVariant, defaultColor = R.color.material_dynamic_neutral30)
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
                getColorFromAttr(R.attr.colorOutline, defaultColor = R.color.material_dynamic_neutral60)
            }
            CMonet.isDynamicColorAvailable() -> {
                getColorFromAttr(R.attr.colorOutline, defaultColor = R.color.material_dynamic_neutral50)
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
    resolveRefs: Boolean = true,
    defaultColor: Int = 0
): Int {
    return try {
        theme.resolveAttribute(attrColor, typedValue, resolveRefs)
        typedValue.data
    } catch (e: Exception) {
        ContextCompat.getColor(this, defaultColor)
    }
}