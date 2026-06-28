package me.rosuh.easywatermark.data.db

import java.io.File

/**
 * S4d-224: Desktop template seed helpers.
 *
 * The English Android seed DB (`ewm-db-eng.db`) is packaged as a `:shared` desktopMain resource so the
 * shared builder/tests can access it without `:shared` depending on `:desktopApp` resources or assets.
 *
 * Because Room KMP does not expose `createFromFile`/`createFromAsset` off-Android, seeding is performed by
 * copying the seed file to the final DB path before Room opens it (see [buildTemplateDatabase]). This helper
 * copies the bundled resource bytes to a caller-supplied [output] file. The caller controls where the
 * temporary seed file lives (e.g. a temp file or under the app-data dir); the builder then copies it into
 * the final DB location on first creation, after which the temporary seed file can be deleted.
 */

private const val SEED_RESOURCE_PATH = "seed/ewm-db-eng.db"

/**
 * Copies the bundled English template seed DB from the desktopMain resources to [output].
 *
 * @param output the file to write the seed bytes to; its parent directory is created if missing.
 * @return [output] for chaining.
 * @throws IllegalStateException if the seed resource cannot be found or read.
 */
fun unpackDefaultTemplateSeed(output: File): File {
    if (!output.parentFile.exists()) output.parentFile.mkdirs()
    val stream = object {}.javaClass.classLoader?.getResourceAsStream(SEED_RESOURCE_PATH)
        ?: throw IllegalStateException("Template seed resource not found: $SEED_RESOURCE_PATH")
    stream.use { input ->
        output.outputStream().use { out ->
            input.copyTo(out)
        }
    }
    return output
}
