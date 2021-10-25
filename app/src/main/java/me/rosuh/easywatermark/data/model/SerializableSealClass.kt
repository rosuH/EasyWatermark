package me.rosuh.easywatermark.data.model

import java.io.Serializable

/**
 * An easy way to make sealed class serializable.
 * @author rosuh
 * @date 2021/6/27
 */
sealed interface SerializableSealClass<T : Serializable> {
    /**
     * return serializable key for specify class
     * @author rosuh
     * @date 2021/6/27
     */
    fun serializeKey(): T
}
