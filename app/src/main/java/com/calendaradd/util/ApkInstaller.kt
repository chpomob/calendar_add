package com.calendaradd.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.calendaradd.BuildConfig
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B03/AC19: PackageInstaller is the ONLY install path on every supported API level (26-35).
 * The deprecated ACTION_INSTALL_PACKAGE fallback is gone: it could complete without the OS
 * confirmation dialog. User confirmation is guaranteed because the session commits with a
 * callback to [InstallStatusReceiver], which launches the system dialog on
 * STATUS_PENDING_USER_ACTION (and on API 31+ the session additionally demands
 * [PackageInstaller.SessionParams.USER_ACTION_REQUIRED]).
 *
 * The constructor seams (sdkInt + factories) exist for the per-API-level unit tests; the
 * android.jar stubs make real construction impossible on the JVM.
 */
class ApkInstaller(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val paramsFactory: () -> PackageInstaller.SessionParams =
        { PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL) },
    private val callbackIntentFactory: (Context) -> Intent = { Intent(it, InstallStatusReceiver::class.java) },
    private val pendingIntentFactory: (Context, Int, Intent, Int) -> PendingIntent =
        { ctx, requestCode, intent, flags -> PendingIntent.getBroadcast(ctx, requestCode, intent, flags) }
) {
    companion object {
        private const val TAG = "ApkInstaller"
    }

    suspend fun install(apkFile: File): InstallResult = withContext(Dispatchers.IO) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return@withContext InstallResult.Failed("Downloaded APK is missing.")
        }

        if (sdkInt >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return@withContext openUnknownSourcesSettings()
        }

        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = paramsFactory().apply {
                setAppPackageName(BuildConfig.APPLICATION_ID)
                if (sdkInt >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                FileInputStream(apkFile).use { input ->
                    session.openWrite(apkFile.name, 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = callbackIntentFactory(context)
                val flags = if (sdkInt >= Build.VERSION_CODES.S) {
                    // On API 31+ PackageInstaller must be able to attach EXTRA_STATUS/EXTRA_INTENT
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = pendingIntentFactory(context, sessionId, callbackIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
            InstallResult.Started
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No fallback: a failed PackageInstaller session is a real failure, never a silent
            // install path.
            AppLog.e(TAG, "PackageInstaller session failed", e)
            InstallResult.Failed("Android blocked the update install: ${e.message ?: e::class.java.simpleName}")
        }
    }

    /**
     * A2: durable terminal outcome of the last install session — survives the process swap a
     * self-update causes. Reads and clears; for the Settings UI startup rehydration.
     */
    fun consumeLastInstallOutcome(): InstallStatus? = InstallStatusReceiver.consumePersistedOutcome(context)

    /** A2: drop any durable outcome before a fresh install attempt. */
    fun clearLastInstallOutcome() = InstallStatusReceiver.clearPersistedOutcome(context)

    private fun openUnknownSourcesSettings(): InstallResult {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            InstallResult.PermissionRequired(
                "Allow Calendar Add to install unknown apps, then run the update again."
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Unable to open unknown app sources settings", e)
            InstallResult.PermissionRequired("Allow Calendar Add to install unknown apps in Android settings.")
        }
    }
}

sealed class InstallResult {
    object Started : InstallResult()
    data class PermissionRequired(val message: String) : InstallResult()
    data class Failed(val message: String) : InstallResult()
}
