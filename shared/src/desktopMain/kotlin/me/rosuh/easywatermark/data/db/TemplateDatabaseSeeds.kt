package me.rosuh.easywatermark.data.db

import java.io.File
import java.util.Locale

/**
 * S4d-224 / S4d-225: Desktop template seed helpers.
 *
 * The Android template seed DBs (`ewm-db-ch.db` and `ewm-db-eng.db`) are packaged as `:shared` desktopMain
 * resources so the shared builder/tests can access them without `:shared` depending on `:desktopApp` resources
 * or assets.
 *
 * Because Room KMP does not expose `createFromFile`/`createFromAsset` off-Android, seeding is performed by
 * copying the seed file to the final DB path before Room opens it (see [buildTemplateDatabase]). This helper
 * copies the bundled resource bytes to a caller-supplied [output] file. The caller controls where the
 * temporary seed file lives (e.g. a temp file or under the app-data dir); the builder then copies it into
 * the final DB location on first creation, after which the temporary seed file can be deleted.
 *
 * Locale-aware seed selection mirrors [TemplateDatabaseBuilder.android.kt]: Chinese (`ch`) when the default
 * JVM locale's language contains `zh`, English (`eng`) otherwise.
 */

private const val SEED_RESOURCE_PREFIX = "seed/ewm-db-"
private const val SEED_RESOURCE_SUFFIX = ".db"

/**
 * Returns the seed language key for the current default JVM locale.
 *
 * - `"ch"` if [Locale.getDefault].language contains `"zh"` (matches Android's rule).
 * - `"eng"` otherwise.
 */
fun defaultTemplateSeedLanguage(): String = if (Locale.getDefault().language.contains("zh")) "ch" else "eng"

/**
 * Copies the bundled template seed DB for the current default JVM locale to [output].
 *
 * @param output the file to write the seed bytes to; its parent directory is created if missing.
 * @return [output] for chaining.
 * @throws IllegalStateException if the seed resource cannot be found or read.
 */
fun unpackDefaultTemplateSeed(output: File): File = unpackTemplateSeed(output, defaultTemplateSeedLanguage())

/**
 * Copies the bundled template seed DB for the requested [language] to [output].
 *
 * Supported [language] values are `"ch"` and `"eng"`, matching the two Android seed assets. The value is
 * used verbatim to build the resource path `seed/ewm-db-{language}.db`, so callers can pass a fixed key in
 * tests without mutating the process-global default locale.
 *
 * @param output the file to write the seed bytes to; its parent directory is created if missing.
 * @param language the seed language key (`"ch"` or `"eng"`).
 * @return [output] for chaining.
 * @throws IllegalStateException if the seed resource cannot be found or read.
 */
fun unpackTemplateSeed(output: File, language: String): File {
    if (!output.parentFile.exists()) output.parentFile.mkdirs()
    val resourcePath = "$SEED_RESOURCE_PREFIX$language$SEED_RESOURCE_SUFFIX"
    val stream = object {}.javaClass.classLoader?.getResourceAsStream(resourcePath)
        ?: throw IllegalStateException("Template seed resource not found: $resourcePath")
    stream.use { input ->
        output.outputStream().use { out ->
            input.copyTo(out)
        }
    }
    return output
}
