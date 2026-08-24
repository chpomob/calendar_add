package com.calendaradd.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.calendaradd.R

/**
 * Receives PackageInstaller session status callbacks. Non-exported: only the pending intent
 * minted by [ApkInstaller] can address it.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = confirmationIntent(intent)
                if (confirmation == null) {
                    AppLog.w(TAG, "STATUS_PENDING_USER_ACTION without EXTRA_INTENT")
                    Toast.makeText(context, "Update needs your confirmation.", Toast.LENGTH_LONG).show()
                } else {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (isAppInForeground()) {
                        AppLog.i(TAG, "Launching install confirmation")
                        context.startActivity(confirmation)
                    } else {
                        AppLog.i(TAG, "App in background, posting confirmation notification")
                        postConfirmationNotification(context, confirmation)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i(TAG, "Install succeeded")
                Toast.makeText(context, "Update installed.", Toast.LENGTH_LONG).show()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.e(TAG, "Install failed (status=$status): ${message ?: "unknown error"}")
                Toast.makeText(context, "Update failed to install.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun postConfirmationNotification(context: Context, confirmation: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            CONFIRMATION_NOTIFICATION_ID,
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Calendar Add update")
            .setContentText("Update ready \u2014 tap to confirm installation.")
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        manager.notify(CONFIRMATION_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "InstallStatusReceiver"
        private const val CHANNEL_ID = "install_updates"
        private const val CHANNEL_NAME = "App updates"
        private const val CONFIRMATION_NOTIFICATION_ID = 1401
    }
}
