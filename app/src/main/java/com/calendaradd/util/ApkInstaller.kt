package com.calendaradd.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.calendaradd.BuildConfig
import com.calendaradd.MainActivity
import java.io.File
import java.io.FileInputStream

class ApkInstaller(private val context: Context) {
    companion object {
        private const val TAG = "ApkInstaller"
    }

    fun install(apkFile: File): InstallResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return InstallResult.Failed("Downloaded APK is missing.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return openUnknownSourcesSettings()
        }

        return tryPackageInstaller(apkFile).takeIf { it != null }
            ?: tryActionInstallPackage(apkFile)
            ?: InstallResult.Failed("No package installer is available on this device.")
    }

    private fun tryPackageInstaller(apkFile: File): InstallResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(BuildConfig.APPLICATION_ID)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            session.use {
                FileInputStream(apkFile).use { input ->
                    it.openWrite(apkFile.name, 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        it.fsync(output)
                    }
                }
                val callbackIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // On API 31+ PackageInstaller must be able to attach EXTRA_STATUS/EXTRA_INTENT
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    0
                }
                val pendingIntent = PendingIntent.getActivity(context, sessionId, callbackIntent, flags)
                it.commit(pendingIntent.intentSender)
            }
            InstallResult.Started
        } catch (e: SecurityException) {
            AppLog.w(TAG, "PackageInstaller blocked, falling back to ACTION_INSTALL_PACKAGE", e)
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "PackageInstaller failed, falling back to ACTION_INSTALL_PACKAGE", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun tryActionInstallPackage(apkFile: File): InstallResult? {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
            context.startActivity(intent)
            InstallResult.Started
        } catch (e: SecurityException) {
            AppLog.w(TAG, "ACTION_INSTALL_PACKAGE was blocked", e)
            InstallResult.Failed("Android blocked APK installation. Allow Calendar Add to install unknown apps.")
        } catch (e: Exception) {
            AppLog.w(TAG, "ACTION_INSTALL_PACKAGE failed", e)
            null
        }
    }

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
