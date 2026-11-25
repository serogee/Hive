package io.github.serogee.hive.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** Asks the user to choose a Google account. */
class GoogleIdTokens(private val webClientId: String) {
    /** Returns an ID token with [hashedNonce] in it, or null when the user cancels. [context] is the screen's activity. */
    suspend fun request(context: Context, hashedNonce: String): String? {
        val option = GetSignInWithGoogleOption.Builder(webClientId).setNonce(hashedNonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                throw SignInException("Google sign-in did not work on this phone.")
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: GetCredentialCancellationException) {
            null
        } catch (_: NoCredentialException) {
            throw SignInException("Add a Google account to this phone first.")
        } catch (_: GetCredentialException) {
            throw SignInException("Google sign-in did not work. Check your connection and Google Play services.")
        } catch (_: GoogleIdTokenParsingException) {
            throw SignInException("Google sign-in did not work on this phone.")
        }
    }
}
