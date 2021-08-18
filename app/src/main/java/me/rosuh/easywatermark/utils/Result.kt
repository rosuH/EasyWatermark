package me.rosuh.easywatermark.utils

class Result<T>(
    var type: Type,
    var data: T? = null,
    var code: String? = null,
    var message: String? = null,
) {

    fun isSuccess() = type == Type.Success

    fun isFailure() = type == Type.Failure

    sealed class Type {
        object Success : Type()
        object Failure : Type()
    }

    companion object {
        fun <T> success(data: T?, code: String? = null, message: String? = null): Result<T> {
            return Result(Type.Success, data, code, message)
        }

        fun <T> failure(data: T? = null, code: String? = null, message: String? = null): Result<T> {
            return Result(Type.Failure, data, code, message)
        }

        fun <T> extendMsg(result: Result<*>, data: T? = null): Result<T> {
            return Result(result.type, data, result.code, result.message)
        }
    }
}