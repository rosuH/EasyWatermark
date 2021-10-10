package me.rosuh.easywatermark.data.model

sealed class JobState {
    object Ready : JobState()
    object Ing : JobState()

    class Success(val result: Result<*>) : JobState()

    class Failure(val result: Result<*>) : JobState()
}