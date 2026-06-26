package me.rosuh.easywatermark.data.repo

/**
 * Holds in-memory settings shared across business usage.
 *
 * S4d-41: the dormant image-tint background-color members were removed — the Compose build never
 * generated or consumed them (S4d-40 Option B). The class is intentionally kept (still DI-provided) as
 * the home for future in-memory settings; it has no members today.
 */
class MemorySettingRepo
