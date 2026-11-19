package io.github.serogee.hive.host

import io.github.serogee.hive.core.api.ApiError
import io.github.serogee.hive.core.api.ErrorCode

/** A request failure that the routes report as an [ApiError]. */
class HiveException(val code: ErrorCode, override val message: String) : RuntimeException(message) {
    fun toApiError() = ApiError(code, message)
}
