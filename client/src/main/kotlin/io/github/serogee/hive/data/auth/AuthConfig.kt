package io.github.serogee.hive.data.auth

/** The values that Google sign-in needs. The app works without them, with sign-in switched off. */
data class AuthConfig(
    val supabaseUrl: String,
    val supabaseKey: String,
    val googleWebClientId: String,
) {
    companion object {
        /** Null unless all three values are there and the address is a web address. */
        fun of(supabaseUrl: String?, supabaseKey: String?, googleWebClientId: String?): AuthConfig? {
            val url = supabaseUrl?.trim()?.trimEnd('/').orEmpty()
            val key = supabaseKey?.trim().orEmpty()
            val clientId = googleWebClientId?.trim().orEmpty()
            val complete = url.startsWith("https://") && key.isNotEmpty() && clientId.isNotEmpty()
            return if (complete) AuthConfig(url, key, clientId) else null
        }
    }
}
