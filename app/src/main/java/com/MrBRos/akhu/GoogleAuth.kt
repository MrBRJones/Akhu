package com.MrBRos.akhu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

/**
 * "Continue with Google": Android's account sheet (Credential Manager) hands back a Google ID
 * token, which becomes a Firebase credential. It needs the Google provider turned on in Firebase,
 * whose web client ID then comes in google-services.json; without one the option is hidden.
 */
object GoogleAuth {

    /** The Firebase project's web client ID, or null until Google sign-in is set up there. */
    private fun clientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id).ifBlank { null }
    }

    @JvmStatic
    fun isAvailable(context: Context): Boolean = clientId(context) != null

    /** Whether [user] signs in with Google only (no password to ask for). */
    @JvmStatic
    fun googleOnly(user: FirebaseUser): Boolean {
        val providers = user.providerData.map { it.providerId }
        return GoogleAuthProvider.PROVIDER_ID in providers && "password" !in providers
    }

    /**
     * Shows the account sheet and passes the chosen account's credential to [onCredential].
     * [onError] gets a message to show, or null when the user just closed the sheet.
     */
    @JvmStatic
    fun request(activity: ComponentActivity, onCredential: (AuthCredential) -> Unit, onError: (String?) -> Unit) {
        val clientId = clientId(activity) ?: return onError(activity.getString(R.string.google_sign_in_unavailable))
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        activity.lifecycleScope.launch {
            try {
                val credential = CredentialManager.create(activity).getCredential(activity, request).credential
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    onCredential(GoogleAuthProvider.getCredential(token, null))
                } else {
                    onError(activity.getString(R.string.auth_failed))
                }
            } catch (e: GetCredentialCancellationException) {
                onError(null)
            } catch (e: com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException) {
                onError(activity.getString(R.string.auth_failed))
            } catch (e: GetCredentialException) {
                onError(e.message ?: activity.getString(R.string.auth_failed))
            }
        }
    }
}
