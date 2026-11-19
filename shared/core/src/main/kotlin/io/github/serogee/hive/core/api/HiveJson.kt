package io.github.serogee.hive.core.api

import kotlinx.serialization.json.Json

/** JSON settings for every request, response, and event. */
val HiveJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
