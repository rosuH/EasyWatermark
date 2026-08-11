package me.rosuh.cmonet

interface IStorage {

    fun <T> save(key: String, value: T)

    fun <T> getValue(key: String, defaultValue: T): T

    /** True when [key] was written before (needed for migration defaults). */
    fun contains(key: String): Boolean
}
