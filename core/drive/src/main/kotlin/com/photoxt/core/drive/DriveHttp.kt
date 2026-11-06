package com.photoxt.core.drive

import com.photoxt.core.auth.DriveTokens
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * PhotoXT's one HTTP client for Drive (spec §5.1): the REST calls through [api], and file bytes
 * (thumbnails, originals, video) through [client]. A request names its account with a
 * [DriveAccount] tag or the [ACCOUNT_HEADER] header (for Media3, which sets headers only); the
 * header never leaves the phone. Drive data and tokens go only to Google (spec §5.7).
 */
class DriveHttp(private val tokens: DriveTokens, val baseUrl: HttpUrl = DEFAULT_BASE_URL) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokens))
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    val api: DriveApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create()

    /** The URL of a file's bytes (`alt=media`), for streaming and downloads. */
    fun mediaUrl(fileId: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("drive/v3/files")
        .addPathSegment(fileId)
        .addQueryParameter("alt", "media")
        .addQueryParameter("supportsAllDrives", "true")
        .build()

    /**
     * Opens [url] for [accountId], from byte [offset] to [end] inclusive (an HTTP Range request;
     * to the end of the file when [end] is null). The caller closes the response. Throws
     * [DriveHttpException] on any status but 200 and 206.
     */
    suspend fun open(
        accountId: String,
        url: HttpUrl,
        offset: Long = 0,
        end: Long? = null
    ): Response {
        val request = Request.Builder()
            .url(url)
            .tag(DriveAccount::class.java, DriveAccount(accountId))
            .apply {
                if (offset > 0 || end != null) header("Range", "bytes=$offset-${end ?: ""}")
            }
            .build()
        val response = client.newCall(request).await()
        if (response.code != HTTP_OK && response.code != HTTP_PARTIAL) {
            response.close()
            throw DriveHttpException(response.code)
        }
        return response
    }

    companion object {
        val DEFAULT_BASE_URL = "https://www.googleapis.com/".toHttpUrl()

        /** Names the account of a request that cannot carry a tag; removed before sending. */
        const val ACCOUNT_HEADER = "X-PhotoXT-Account"

        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        private const val HTTP_UNAUTHORIZED = 401
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L

        val json = Json { ignoreUnknownKeys = true }
    }

    /** Signs each request with its account's token, and retries once with a new one on 401. */
    private class AuthInterceptor(private val tokens: DriveTokens) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val accountId = original.tag(DriveAccount::class.java)?.id
                ?: original.header(ACCOUNT_HEADER)
                ?: return chain.proceed(original)
            val request = original.newBuilder().removeHeader(ACCOUNT_HEADER).build()
            // OkHttp calls interceptors on its own threads, where blocking is expected.
            val token = runBlocking { tokens.token(accountId) }
            val response = chain.proceed(request.signed(token))
            return if (response.code == HTTP_UNAUTHORIZED) {
                response.close()
                val fresh = runBlocking {
                    tokens.invalidate(accountId, token)
                    tokens.token(accountId)
                }
                chain.proceed(request.signed(fresh))
            } else {
                response
            }
        }

        private fun Request.signed(token: String) =
            newBuilder().header("Authorization", "Bearer $token").build()
    }
}

/** Drive answered [code] to a file request. */
class DriveHttpException(val code: Int) : IOException("Drive answered HTTP $code")

/** Runs [this] call on OkHttp's threads, cancelling it with the coroutine. */
suspend fun Call.await(): Response = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(Result.success(response))
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }
            }
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriveModule {
    @Provides
    @Singleton
    fun driveHttp(tokens: DriveTokens): DriveHttp = DriveHttp(tokens)
}
