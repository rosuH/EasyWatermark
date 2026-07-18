package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral watermark mode (text watermark vs image/icon watermark) — the commonMain
 * Replacement for the `WaterMarkRepository.MarkMode` sealed type that previously leaked an `:app` * repository type into [WaterMark] (after the readiness map).
 *
 * [value] is the stable int persisted in DataStore under `KEY_MODE`. It is kept EXACTLY equal to the
 * historical `WaterMarkRepository.MarkMode` values (Text=0, Image=1) so existing user preferences
 * round-trip with NO migration write — the same explicit-id contract as [WatermarkTileMode]/[ImageFormat].
 */
enum class WatermarkMode(val value: Int) {
    Text(0),
    Image(1);

    companion object {
        /** Map a persisted [KEY_MODE] int back to a mode; anything other than [Image]'s id is [Text]
 * (preserves the legacy `if (it[KEY_MODE] == MarkMode.Image.value) Image else Text` rule). */
        fun fromValue(value: Int): WatermarkMode = if (value == Image.value) Image else Text
    }
}
