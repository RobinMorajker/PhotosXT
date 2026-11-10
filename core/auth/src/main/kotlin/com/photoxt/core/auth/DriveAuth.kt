package com.photoxt.core.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.DriveDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drive scopes (spec §2): read-only from the first folder, full Drive from the first write
 * (Phase 5), app data for settings sync (Phase 6).
 */
enum class DriveScope(val uri: String) {
    READONLY("https://www.googleapis.com/auth/drive.readonly"),
    FULL("https://www.googleapis.com/auth/drive"),
    APP_DATA("https://www.googleapis.com/auth/drive.appdata");

    /** Whether [granted] scopes (space-separated) cover this one. */
    fun grantedIn(granted: String): Boolean {
        val scopes = granted.split(' ')
        return uri in scopes || (this == READONLY && FULL.uri in scopes)
    }
}

/** What asking Google for Drive access came to. */
sealed interface Authorization {
    data class Granted(val token: String, val scopes: List<String>) : Authorization

    /** The user must see Google's consent screen first: launch [intent] from an activity. */
    data class NeedsConsent(val intent: PendingIntent) : Authorization
}

/**
 * Drive refused the account's grant (it expired, which Testing-status grants do after 7 days, or
 * was revoked). The account is flagged and screens offer "Reconnect Google Drive" (spec §2).
 */
class ConsentRequiredException(val accountId: String) :
    IOException("Google Drive needs the user's consent again")

/** Tokens for Drive requests, as the HTTP layer sees them (faked in tests). */
interface DriveTokens {
    /** A token for [accountId]; throws [ConsentRequiredException] when the user is needed. */
    suspend fun token(accountId: String): String

    /** Drive answered 401 to [token]: forget it. */
    suspend fun invalidate(accountId: String, token: String)
}

/**
 * Drive access per account through Play services' AuthorizationClient (spec §2). Play services
 * keeps grants and tokens; PhotoXT holds a token only in memory, for its lifetime, and never
 * logs or stores it (spec §5.7).
 */
@Singleton
class DriveAuth @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: DriveDao
) : DriveTokens {
    private val client get() = Identity.getAuthorizationClient(context)
    private val tokens = ConcurrentHashMap<String, CachedToken>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Whether [accountId] granted [scope] (spec §2: full Drive from the first write). Only what
     * PhotoXT last heard from Google; a write that finds it lapsed asks again.
     */
    suspend fun hasScope(accountId: String, scope: DriveScope): Boolean =
        dao.account(accountId)?.scopes?.let(scope::grantedIn) == true

    /** Asks for [scope] on [accountId]'s behalf. Screens launch the consent screen if needed. */
    suspend fun authorize(accountId: String, scope: DriveScope): Authorization {
        val account = checkNotNull(dao.account(accountId)) { "No such account" }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(scope.uri)))
            .setAccount(account.androidAccount())
            .build()
        return handle(accountId, client.authorize(request).await(), scope)
    }

    /** The consent screen's answer for [accountId]: true when Drive access was granted. */
    suspend fun completeConsent(accountId: String, data: Intent?): Boolean {
        val result = runCatching { client.getAuthorizationResultFromIntent(data) }
            .onFailure { PxtLog.w(TAG, "Consent was not given", it) }
            .getOrNull() ?: return false
        return handle(accountId, result) is Authorization.Granted
    }

    private suspend fun handle(
        accountId: String,
        result: AuthorizationResult,
        requested: DriveScope? = null
    ): Authorization {
        if (result.hasResolution()) {
            // A grant that existed and now needs the screen has lapsed: say so on screen. Asking
            // for more (full Drive at the first write) is no lapse.
            val granted = dao.account(accountId)?.scopes.orEmpty()
            if (granted.isNotEmpty() && (requested == null || requested.grantedIn(granted))) {
                dao.setNeedsConsent(accountId, true)
            }
            return Authorization.NeedsConsent(checkNotNull(result.pendingIntent))
        }
        val token = checkNotNull(result.accessToken) { "Authorized without a token" }
        tokens[accountId] = CachedToken(token, System.currentTimeMillis())
        dao.setScopes(accountId, result.grantedScopes.joinToString(" "))
        return Authorization.Granted(token, result.grantedScopes)
    }

    /**
     * A token for background Drive requests on [accountId]'s behalf. Never shows UI: throws
     * [ConsentRequiredException] when the grant needs the user again.
     */
    override suspend fun token(accountId: String): String {
        fresh(accountId)?.let { return it }
        return locks.getOrPut(accountId) { Mutex() }.withLock {
            fresh(accountId) ?: when (val result = authorize(accountId, DriveScope.READONLY)) {
                is Authorization.Granted -> result.token
                is Authorization.NeedsConsent -> throw ConsentRequiredException(accountId)
            }
        }
    }

    private fun fresh(accountId: String): String? = tokens[accountId]
        ?.takeIf { System.currentTimeMillis() - it.obtainedAt < TOKEN_REUSE_MS }
        ?.value

    /** Drive answered 401 to [token]: forget it here and in Play services' cache. */
    override suspend fun invalidate(accountId: String, token: String) {
        tokens.computeIfPresent(accountId) { _, cached -> cached.takeIf { it.value != token } }
        runCatching {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
            .onFailure { PxtLog.w(TAG, "Could not clear a token", it) }
    }

    /** Gives back every Drive grant of [accountId], when the account is removed from PhotoXT. */
    suspend fun revoke(accountId: String) {
        val account = dao.account(accountId) ?: return
        tokens.remove(accountId)
        val scopes = account.scopes.split(' ').filter { it.isNotBlank() }.map(::Scope)
        if (scopes.isEmpty()) return
        val request = RevokeAccessRequest.builder()
            .setAccount(account.androidAccount())
            .setScopes(scopes)
            .build()
        runCatching { client.revokeAccess(request).await() }
            .onFailure { PxtLog.w(TAG, "Could not revoke Drive access", it) }
    }

    private class CachedToken(val value: String, val obtainedAt: Long)

    private companion object {
        const val TAG = "DriveAuth"

        // Google access tokens last an hour; a 401 before this runs out is handled anyway.
        const val TOKEN_REUSE_MS = 45 * 60 * 1000L
    }
}

/** Awaits a Play services task without the coroutines-play-services artifact. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface AuthModule {
    @dagger.Binds
    fun tokens(auth: DriveAuth): DriveTokens
}
