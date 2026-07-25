package me.rosuh.easywatermark.render

import javax.imageio.ImageIO

/**
 * J3 — Desktop open/drop format truthfulness (issue 13 §J3).
 *
 * Stock JDK / Corretto / Zulu `ImageIO` typically has **no** WebP reader. Advertising `webp` in
 * the chooser would promise a format [DesktopImageDecoder] cannot open (no new decoder deps in J3).
 *
 * Base set is always advertised; WebP is added only when [isWebpDecodable] is true at runtime.
 */
object DesktopImageFormats {

    /** Formats ImageIO commonly supports without extra plugins. */
    val BASE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "bmp", "gif")

    /** True when the JVM registered at least one ImageIO WebP reader. */
    fun isWebpDecodable(): Boolean {
        val readers = ImageIO.getImageReadersByFormatName("webp")
        return try {
            readers.hasNext()
        } finally {
            // Iterator may hold native resources on some JREs; dispose if present.
            while (readers.hasNext()) {
                runCatching { readers.next().dispose() }
            }
        }
    }

    /**
     * Extensions for file choosers / drag-drop filters.
     * Defaults to capability-true advertising (WebP only if decodable).
     */
    fun chooserExtensions(preferWebpWhenSupported: Boolean = true): Set<String> {
        if (!preferWebpWhenSupported || !isWebpDecodable()) return BASE_EXTENSIONS
        return BASE_EXTENSIONS + "webp"
    }
}
