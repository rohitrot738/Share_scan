package com.rohit.share_scan

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseAuthWebException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.tasks.await

/**
 * Firebase login for the Account sheet: Google (through Credential Manager), GitHub and email + password.
 *
 * Firebase is set up by hand from the FIREBASE_* values baked into BuildConfig at build time, because the
 * google-services Gradle plugin fails every build that has no committed google-services.json. Until those values
 * exist, [auth] is null, [isConfigured] is false and nothing here touches Firebase, so the scanner runs as before.
 */
object AccountManager {
    private const val TAG = "ShareScan"

    /** Firebase provider ids, as [FirebaseUser.getProviderData] reports them. */
    const val PROVIDER_PASSWORD = "password"
    const val PROVIDER_GOOGLE = "google.com"
    const val PROVIDER_GITHUB = "github.com"

    private var auth: FirebaseAuth? = null

    /** This build carries a Firebase config; without one the Account sheet only says login is not set up yet. */
    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_API_KEY.isNotEmpty() &&
            BuildConfig.FIREBASE_APP_ID.isNotEmpty() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotEmpty()

    /** Google sign-in also needs the OAuth web client id; email and GitHub work without it. */
    val hasGoogle: Boolean
        get() = BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotEmpty()

    /** What the sheet shows about the signed-in user, free of Firebase types. */
    data class Profile(
        val uid: String,
        val name: String?,
        val email: String?,
        val emailVerified: Boolean,
        val phone: String?,
        val photoUrl: String?,
        val providers: Set<String>,
        val createdAt: Long?,
        val lastSignInAt: Long?,
    )

    /** Asked of a build without a Firebase config, or of a signed-out user; the sheet never offers either. */
    class UnavailableException : IllegalStateException("Login is not available")

    /** Firebase, set up on first use; null when this build has no config or the config is unusable. */
    @Synchronized
    fun auth(context: Context): FirebaseAuth? {
        auth?.let { return it }
        if (!isConfigured) return null
        return try {
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.initializeApp(context.applicationContext, options())
            FirebaseAuth.getInstance(app).also {
                // Reset and verification emails in Hindi, like the rest of the app.
                it.setLanguageCode("hi")
                auth = it
            }
        } catch (error: RuntimeException) {
            // A mistyped secret must not take the scanner down with it; login just stays off.
            Log.w(TAG, "Firebase setup failed", error)
            null
        }
    }

    private fun options(): FirebaseOptions = FirebaseOptions.Builder()
        .setApiKey(BuildConfig.FIREBASE_API_KEY)
        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
        .apply {
            if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotEmpty()) setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
            if (BuildConfig.FIREBASE_SENDER_ID.isNotEmpty()) setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
        }
        .build()

    private fun requireAuth(context: Context): FirebaseAuth = auth(context) ?: throw UnavailableException()

    private fun requireUser(context: Context): FirebaseUser = requireAuth(context).currentUser ?: throw UnavailableException()

    fun profile(context: Context): Profile? {
        val user = auth(context)?.currentUser ?: return null
        // GitHub accounts with a private email or no name keep them only on the provider's entry.
        val linked = user.providerData.filter { it.providerId != "firebase" }
        return Profile(
            uid = user.uid,
            name = user.displayName?.takeIf { it.isNotBlank() }
                ?: linked.firstNotNullOfOrNull { info -> info.displayName?.takeIf { it.isNotBlank() } },
            email = user.email?.takeIf { it.isNotBlank() }
                ?: linked.firstNotNullOfOrNull { info -> info.email?.takeIf { it.isNotBlank() } },
            emailVerified = user.isEmailVerified,
            phone = user.phoneNumber?.takeIf { it.isNotBlank() },
            photoUrl = (user.photoUrl ?: linked.firstNotNullOfOrNull { it.photoUrl })?.toString(),
            providers = linked.map { it.providerId }.toSet(),
            createdAt = user.metadata?.creationTimestamp?.takeIf { it > 0 },
            lastSignInAt = user.metadata?.lastSignInTimestamp?.takeIf { it > 0 },
        )
    }

    /** The menu row's subtitle: the signed-in email, else name; null when signed out or login is not set up. */
    fun summary(context: Context): String? {
        val profile = profile(context) ?: return null
        return profile.email ?: profile.name ?: context.getString(R.string.account_signed_in_generic)
    }

    suspend fun signInWithEmail(context: Context, email: String, password: String) {
        requireAuth(context).signInWithEmailAndPassword(email, password).await()
    }

    /** Creates the account (which also signs in) and sends the first verification email. */
    suspend fun createAccount(context: Context, email: String, password: String) {
        val user = requireAuth(context).createUserWithEmailAndPassword(email, password).await().user ?: return
        try {
            user.sendEmailVerification().await()
        } catch (error: FirebaseException) {
            // The account exists either way; the profile offers to send the email again.
            Log.w(TAG, "Verification email failed", error)
        }
    }

    suspend fun sendPasswordReset(context: Context, email: String) {
        requireAuth(context).sendPasswordResetEmail(email).await()
    }

    suspend fun signInWithGoogle(activity: Activity) {
        val credential = googleCredential(activity)
        requireAuth(activity).signInWithCredential(credential).await()
    }

    suspend fun linkGoogle(activity: Activity) {
        val user = requireUser(activity)
        user.linkWithCredential(googleCredential(activity)).await()
    }

    /**
     * Picks a Google account through Credential Manager and turns its ID token into a Firebase credential. Backing
     * out throws [GetCredentialCancellationException], which [errorMessage] keeps quiet about.
     */
    private suspend fun googleCredential(activity: Activity): AuthCredential {
        if (!hasGoogle) throw UnavailableException()
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.FIREBASE_WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("Credential Manager returned ${credential.type}, not a Google ID token")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return GoogleAuthProvider.getCredential(idToken, null)
    }

    /** GitHub opens in the browser; a flow the system interrupted (app killed meanwhile) is finished instead of restarted. */
    suspend fun signInWithGitHub(activity: Activity) {
        val auth = requireAuth(activity)
        (auth.pendingAuthResult ?: auth.startActivityForSignInWithProvider(activity, github())).await()
    }

    suspend fun linkGitHub(activity: Activity) {
        val auth = requireAuth(activity)
        val user = auth.currentUser ?: throw UnavailableException()
        (auth.pendingAuthResult ?: user.startActivityForLinkWithProvider(activity, github())).await()
    }

    private fun github(): OAuthProvider = OAuthProvider.newBuilder(PROVIDER_GITHUB).build()

    /** A GitHub sign-in or link the browser finished after the app was restarted is waiting to be collected. */
    fun hasPendingResult(context: Context): Boolean = auth(context)?.pendingAuthResult != null

    suspend fun finishPendingResult(context: Context) {
        auth(context)?.pendingAuthResult?.await()
    }

    /** Refreshes the user from the server, so a link clicked in the verification email shows up as verified. */
    suspend fun refresh(context: Context) {
        auth(context)?.currentUser?.reload()?.await()
    }

    suspend fun updateName(context: Context, name: String) {
        val request = UserProfileChangeRequest.Builder().setDisplayName(name).build()
        requireUser(context).updateProfile(request).await()
    }

    suspend fun sendVerification(context: Context) {
        requireUser(context).sendEmailVerification().await()
    }

    /** Re-checks the current password first, since Firebase only changes it for a recent sign-in. */
    suspend fun changePassword(context: Context, current: String, new: String) {
        val user = requireUser(context)
        val email = user.email ?: throw UnavailableException()
        user.reauthenticate(EmailAuthProvider.getCredential(email, current)).await()
        user.updatePassword(new).await()
    }

    suspend fun deleteAccount(context: Context) {
        requireUser(context).delete().await()
        clearCredentials(context)
    }

    /** Signs out of Firebase and tells Credential Manager to forget the Google account choice. */
    suspend fun signOut(context: Context) {
        auth(context)?.signOut()
        clearCredentials(context)
    }

    private suspend fun clearCredentials(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (error: ClearCredentialException) {
            // Only the account picker's memory is affected; Firebase is already signed out.
            Log.w(TAG, "Clearing credential state failed", error)
        }
    }

    /** Deleting the account or changing sign-in details needs a fresh login first. */
    fun needsRecentLogin(error: Throwable): Boolean = error is FirebaseAuthRecentLoginRequiredException

    /** The Hindi message for a failed request, or null when the user simply backed out of a picker or browser. */
    @StringRes
    fun errorMessage(error: Throwable): Int? = when (error) {
        is GetCredentialCancellationException -> null
        is NoCredentialException -> R.string.account_error_no_google_account
        is GetCredentialException, is GoogleIdTokenParsingException -> R.string.account_error_google
        is FirebaseAuthWebException ->
            if (error.errorCode == "ERROR_WEB_CONTEXT_CANCELED") null else R.string.account_error_browser
        // Subclasses before their parents: weak password is an invalid credential, recent login an invalid user.
        is FirebaseAuthWeakPasswordException -> R.string.account_error_weak_password
        is FirebaseAuthRecentLoginRequiredException -> R.string.account_relogin_message
        is FirebaseAuthInvalidUserException ->
            if (error.errorCode == "ERROR_USER_DISABLED") R.string.account_error_disabled else R.string.account_error_no_user
        is FirebaseAuthInvalidCredentialsException ->
            if (error.errorCode == "ERROR_INVALID_EMAIL") R.string.account_error_bad_email else R.string.account_error_wrong_password
        is FirebaseAuthUserCollisionException -> when (error.errorCode) {
            "ERROR_EMAIL_ALREADY_IN_USE" -> R.string.account_error_email_used
            "ERROR_CREDENTIAL_ALREADY_IN_USE" -> R.string.account_error_already_linked
            else -> R.string.account_error_other_method
        }
        is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_PROVIDER_ALREADY_LINKED" -> R.string.account_error_already_linked
            "ERROR_OPERATION_NOT_ALLOWED" -> R.string.account_error_method_off
            else -> R.string.account_error_generic
        }
        is FirebaseNetworkException -> R.string.account_error_network
        is FirebaseTooManyRequestsException -> R.string.account_error_too_many
        is UnavailableException -> R.string.account_unconfigured_title
        else -> R.string.account_error_generic
    }
}
