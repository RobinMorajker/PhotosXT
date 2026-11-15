package com.photoxt.core.ui

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.photoxt.core.designsystem.R as DesignR

/**
 * The lock on Hidden and the vault (spec §4.8): open from a fingerprint or screen-lock unlock
 * until the app goes to the background ([lock], from the activity) or the Library list shows.
 */
object PrivateLock {
    var unlocked by mutableStateOf(false)
        private set

    /** An unlock prompt is showing: the app stopping under it doesn't lock. */
    var prompting = false
        private set

    fun lock() {
        if (!prompting) unlocked = false
    }

    internal fun prompt(context: Context, title: String) {
        if (prompting) return
        prompting = true
        BiometricPrompt.Builder(context)
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
            .authenticate(
                CancellationSignal(),
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        prompting = false
                        unlocked = true
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        prompting = false
                    }
                }
            )
    }

    /** Whether the phone has a screen lock or fingerprint to unlock with. */
    internal fun canUnlock(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
}

/**
 * Shows [content] once [PrivateLock] is open, asking for the unlock ([promptTitle]) as it opens;
 * until then the locked page [title], with [onBack] to leave.
 */
@Composable
fun UnlockGate(
    title: String,
    promptTitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (PrivateLock.unlocked) {
        content()
        return
    }
    val context = LocalContext.current
    val canUnlock = remember { PrivateLock.canUnlock(context) }
    LaunchedEffect(Unit) { if (canUnlock) PrivateLock.prompt(context, promptTitle) }
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        IconButton(onClick = onBack) {
            Icon(
                painterResource(DesignR.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.lock_back)
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            EmptyState(
                icon = painterResource(DesignR.drawable.ic_lock),
                title = title,
                body = stringResource(
                    if (canUnlock) R.string.lock_body else R.string.lock_no_screen_lock
                )
            )
            if (canUnlock) {
                Button(onClick = { PrivateLock.prompt(context, promptTitle) }) {
                    Text(stringResource(R.string.lock_unlock))
                }
            } else {
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }) {
                    Text(stringResource(R.string.lock_settings))
                }
            }
        }
    }
}

/**
 * [content] only while [PrivateLock] is open; otherwise [onLocked], for a screen restored after
 * the app was away.
 */
@Composable
fun RequireUnlocked(onLocked: () -> Unit, content: @Composable () -> Unit) {
    if (PrivateLock.unlocked) {
        content()
    } else {
        LaunchedEffect(Unit) { onLocked() }
    }
}

/** No screenshots, screen recording or recents preview while this shows (spec §4.8, vault). */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        secureScreens++
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            secureScreens--
            if (secureScreens == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Secure screens showing at once (the vault under its viewer); main thread only. */
private var secureScreens = 0
