package me.rosuh.easywatermark.data.model

/**
 * One saved image-mode watermark asset in the reusable sticker library.
 * Applying a sticker swaps [WaterMark.iconUri] while preserving live styling and placement.
 */
data class Sticker(
    val id: String,
    val icon: MediaRef,
)

/** Line file kept next to owned icons. One sticker per line, fields split by [SEP]. */
object StickerCatalog {
    const val FILE_NAME: String = "stickers.catalog"

    private const val SEP = "\u001f"
    private const val FIELDS = 2
    private const val LEGACY_FIELDS = 10

    fun encode(stickers: List<Sticker>): String =
        stickers.joinToString("\n") { sticker ->
            listOf(
                sticker.id,
                sticker.icon.value,
            ).joinToString(SEP)
        }

    fun decode(text: String): List<Sticker> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(SEP)
                require(parts.size == FIELDS || parts.size == LEGACY_FIELDS) {
                    "sticker catalog line has ${parts.size} fields"
                }
                val id = parts[0]
                val icon = parts[1]
                require(id.isNotEmpty() && icon.isNotEmpty()) { "sticker catalog line missing id or icon" }
                Sticker(
                    id = id,
                    icon = MediaRef(icon),
                )
            }
            .toList()
    }

    fun add(existing: String, sticker: Sticker): String {
        val stickers = decode(existing)
        require(stickers.none { it.id == sticker.id }) { "sticker id already saved" }
        return encode(listOf(sticker) + stickers)
    }

    fun markUsed(existing: String, id: String): String {
        val stickers = decode(existing)
        val index = stickers.indexOfFirst { it.id == id }
        if (index <= 0) return existing
        val target = stickers[index]
        return encode(listOf(target) + stickers.filterNot { it.id == id })
    }

    fun remove(existing: String, id: String): String =
        encode(decode(existing).filter { it.id != id })

    fun removeAll(existing: String, ids: Set<String>): String {
        if (ids.isEmpty()) return existing
        return encode(decode(existing).filter { it.id !in ids })
    }
}
