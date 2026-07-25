package me.rosuh.easywatermark.platform

import java.io.File

/**
 * J3 — Desktop app-data / cache path policy (issue 13 §J3).
 *
 * | OS | Data root | Cache root |
 * |---|---|---|
 * | macOS | `~/Library/Application Support/EasyWatermark` | `~/Library/Caches/EasyWatermark` |
 * | Windows | `%LOCALAPPDATA%\EasyWatermark` | `%LOCALAPPDATA%\EasyWatermark\Cache` |
 * | Linux | `$XDG_DATA_HOME/easywatermark` or `~/.local/share/easywatermark` | `$XDG_CACHE_HOME/...` or `~/.cache/easywatermark` |
 *
 * **Legacy:** `~/.easywatermark` (pre-J3). One-shot **copy-forward** when the native root is
 * empty/missing and legacy has content. Legacy is **never deleted**.
 *
 * Pure helpers accept [home]/[osName]/[env] for unit tests without touching the live home dir.
 */
object DesktopAppPaths {

    const val APP_DISPLAY_NAME: String = "EasyWatermark"
    const val APP_UNIX_NAME: String = "easywatermark"
    const val LEGACY_DIR_NAME: String = ".easywatermark"

    fun userHome(homeProp: String? = System.getProperty("user.home")): String? =
        homeProp?.takeIf { it.isNotBlank() }

    fun legacyDataDir(home: String): File = File(home, LEGACY_DIR_NAME)

    fun osNativeDataDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        home: String,
        env: (String) -> String? = { System.getenv(it) },
    ): File {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") ->
                File(home, "Library/Application Support/$APP_DISPLAY_NAME")
            os.contains("win") -> {
                val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                if (local != null) File(local, APP_DISPLAY_NAME)
                else File(home, "AppData${File.separator}Local${File.separator}$APP_DISPLAY_NAME")
            }
            else -> {
                val xdg = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                if (xdg != null) File(xdg, APP_UNIX_NAME)
                else File(home, ".local/share/$APP_UNIX_NAME")
            }
        }
    }

    fun osNativeCacheDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        home: String,
        env: (String) -> String? = { System.getenv(it) },
    ): File {
        val os = osName.lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") ->
                File(home, "Library/Caches/$APP_DISPLAY_NAME")
            os.contains("win") ->
                File(osNativeDataDir(osName, home, env), "Cache")
            else -> {
                val xdg = env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                if (xdg != null) File(xdg, APP_UNIX_NAME)
                else File(home, ".cache/$APP_UNIX_NAME")
            }
        }
    }

    /**
     * Resolve the interactive product data directory.
     * Migrates legacy → native when safe; creates the result dir.
     * Falls back to [fallbackWhenNoHome] when `user.home` is blank.
     */
    fun resolveAppDataDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        home: String? = userHome(),
        env: (String) -> String? = { System.getenv(it) },
        fallbackWhenNoHome: File = File("build/desktop-app-data"),
    ): File {
        if (home == null) {
            return fallbackWhenNoHome.apply { mkdirs() }
        }
        val native = osNativeDataDir(osName, home, env)
        val legacy = legacyDataDir(home)
        migrateLegacyIfNeeded(legacy, native)
        return native.apply { mkdirs() }
    }

    /**
     * Copy-forward migration. Returns true if any copy was performed.
     *
     * Preconditions for migration:
     * - [legacy] is a non-empty directory
     * - [native] does not exist or is empty (no user data yet)
     *
     * Never deletes or renames [legacy]. Never overwrites existing files under [native].
     */
    fun migrateLegacyIfNeeded(legacy: File, native: File): Boolean {
        if (!legacy.isDirectory || !directoryHasContent(legacy)) return false
        if (native.exists() && directoryHasContent(native)) return false
        native.mkdirs()
        var copied = false
        legacy.walkTopDown().forEach { src ->
            val rel = src.relativeTo(legacy)
            val dest = native.resolve(rel.path)
            if (src.isDirectory) {
                if (!dest.exists()) {
                    dest.mkdirs()
                    copied = true
                }
            } else if (src.isFile && !dest.exists()) {
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = false)
                copied = true
            }
        }
        return copied
    }

    fun directoryHasContent(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val children = dir.list() ?: return false
        return children.isNotEmpty()
    }
}
