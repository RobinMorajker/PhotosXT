package com.photoxt.core.auth

import android.accounts.Account
import android.app.Activity
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.DriveDao
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** What adding a Google account came to. */
sealed interface SignInResult {
    data class Added(val account: AccountEntity) : SignInResult

    data object Cancelled : SignInResult

    /** This build has no Web client id (`PHOTOXT_GOOGLE_WEB_CLIENT_ID`, see core/auth). */
    data object NotConfigured : SignInResult

    data object Failed : SignInResult
}

/**
 * The Google accounts added to PhotoXT (spec §4.2: personal and work, any number). Sign-in is
 * Credential Manager's "Sign in with Google" (spec §2); Drive access is asked separately, per
 * account, through [DriveAuth].
 */
@Singleton
class GoogleAccounts @Inject constructor(private val dao: DriveDao) {
    val accounts: Flow<List<AccountEntity>> = dao.accounts()

    /** Shows Google's account chooser over [activity] and adds (or refreshes) the chosen one. */
    suspend fun signIn(activity: Activity): SignInResult {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isEmpty()) return SignInResult.NotConfigured
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        return runCatching {
            CredentialManager.create(activity).getCredential(activity, request).credential
        }.fold(
            onSuccess = { save(it) },
            onFailure = { error ->
                when (error) {
                    is GetCredentialCancellationException -> SignInResult.Cancelled

                    is GetCredentialException -> {
                        PxtLog.w(TAG, "Sign-in failed: ${error.type}")
                        SignInResult.Failed
                    }

                    else -> throw error
                }
            }
        )
    }

    private suspend fun save(credential: Credential): SignInResult {
        val google = (credential as? CustomCredential)
            ?.takeIf { it.type in GOOGLE_TYPES }
            ?.let { GoogleIdTokenCredential.createFrom(it.data) }
        val id = google?.uniqueId.orEmpty().ifEmpty { google?.idToken?.let(::subjectOf).orEmpty() }
        val email = google?.email.orEmpty()
        if (google == null || id.isEmpty() || email.isEmpty()) return SignInResult.Failed
        val known = dao.account(id)
        val account = AccountEntity(
            id = id,
            email = email,
            displayName = google.displayName,
            photoUrl = google.profilePictureUri?.toString(),
            scopes = known?.scopes.orEmpty(),
            needsConsent = known?.needsConsent ?: false,
            changesToken = known?.changesToken,
            lastSyncAt = known?.lastSyncAt,
            addedAt = known?.addedAt ?: System.currentTimeMillis()
        )
        dao.upsertAccount(account)
        return SignInResult.Added(account)
    }

    private companion object {
        const val TAG = "GoogleAccounts"
        val GOOGLE_TYPES = setOf(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
        )
    }
}

/** The Android account Play services knows [this] Google account by. */
fun AccountEntity.androidAccount(): Account = Account(email, "com.google")

/**
 * The `sub` claim of a Google ID token, the account's stable id. Read on the phone from a token
 * Play services just handed over; it is never sent anywhere, so its signature is not checked.
 */
internal fun subjectOf(idToken: String): String? = runCatching {
    val payload = String(Base64.getUrlDecoder().decode(idToken.split('.')[1]))
    SUBJECT.find(payload)?.groupValues?.get(1)
}.getOrNull()

private val SUBJECT = Regex(""""sub"\s*:\s*"([^"]+)"""")
