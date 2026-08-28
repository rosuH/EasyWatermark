package me.rosuh.easywatermark.render

/**
 * Serializes [WatermarkCellComposer.composeTextCell] across `Dispatchers.Default` workers.
 * CMP Skiko `ParagraphBuilder.makeSkTextStyle` keeps a process-wide WeakKeysCache that is
 * not thread-safe — two overlay composes SIGSEGV (`iosApp-2026-08-29-015756.ips`).
 */
internal expect fun <T> withComposeTextCellLock(block: () -> T): T
