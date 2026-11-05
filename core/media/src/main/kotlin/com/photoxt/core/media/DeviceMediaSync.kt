package com.photoxt.core.media

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.photoxt.core.common.PxtLog
import com.photoxt.core.model.MediaAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Keeps the Room index in step with MediaStore while PhotoXT is in the foreground: one pass
 * every time the app comes back (so items deleted elsewhere are gone on resume), and one after
 * every MediaStore change while it is open (so a new camera photo shows within moments). Inserts
 * and edits need only the cheap generation scan; deletions, returns and new permissions also
 * compare every id.
 */
@Singleton
class DeviceMediaSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val indexer: MediaStoreIndexer,
    private val access: MediaAccessMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val diffRequested = AtomicBoolean(true)
    private val foreground = MutableStateFlow(false)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
            request(diff = flags == 0 || flags and ContentResolver.NOTIFY_DELETE != 0)
        }
    }

    /** Re-reads media access and runs a full pass, e.g. after a permission answer. */
    fun refresh() {
        access.refresh()
        request(diff = true)
    }

    private fun request(diff: Boolean) {
        if (diff) diffRequested.set(true)
        requests.trySend(Unit)
    }

    /** Call once, from `Application.onCreate`. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    access.refresh()
                    foreground.value = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground.value = false
                }
            }
        )
        scope.launch {
            combine(foreground, access.state) { visible, granted ->
                visible && granted != MediaAccess.DENIED
            }.distinctUntilChanged().collect { active ->
                observeMediaStore(active)
                if (active) request(diff = true)
            }
        }
        scope.launch {
            requests.receiveAsFlow().collect {
                // A capture or a bulk change notifies several times; let the burst land first.
                delay(COALESCE_MS)
                val diff = diffRequested.getAndSet(false)
                if (access.state.value != MediaAccess.DENIED) indexOnce(diff)
            }
        }
    }

    /** A failed pass is logged and retried on the next trigger; it must never crash the app. */
    private suspend fun indexOnce(diff: Boolean) {
        runCatching { indexer.index(diff = diff) }
            .onSuccess { PxtLog.d(TAG, "Indexed: ${it.changed} changed, ${it.removed} removed") }
            .onFailure {
                if (it is CancellationException) throw it
                PxtLog.w(TAG, "MediaStore indexing failed", it)
            }
    }

    private fun observeMediaStore(on: Boolean) {
        val resolver = context.contentResolver
        if (on) {
            resolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            resolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        } else {
            resolver.unregisterContentObserver(observer)
        }
    }

    private companion object {
        const val TAG = "DeviceMediaSync"
        const val COALESCE_MS = 150L
    }
}
