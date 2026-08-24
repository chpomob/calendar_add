package com.calendaradd.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Terminal outcome of a PackageInstaller session. */
enum class InstallStatus { SUCCESS, FAILURE, ABORTED }

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
                    AppLog.i(TAG, "Launching install confirmation")
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                }
            }
            else -> {
                val terminal = terminalInstallStatus(status)
                _statusFlow.value = terminal
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (terminal == InstallStatus.SUCCESS) {
                    AppLog.i(TAG, "Install succeeded")
                    Toast.makeText(context, "Update installed.", Toast.LENGTH_LONG).show()
                } else {
                    AppLog.e(TAG, "Install $terminal (status=$status): ${message ?: "unknown error"}")
                    Toast.makeText(context, "Update failed to install.", Toast.LENGTH_LONG).show()
                }
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

    companion object {
        private const val TAG = "InstallStatusReceiver"
        private val _statusFlow = MutableStateFlow<InstallStatus?>(null)

        /** Last terminal install outcome; null until the session succeeds, fails or is aborted. */
        val statusFlow: StateFlow<InstallStatus?> = _statusFlow.asStateFlow()

        /** Maps any PackageInstaller status code to a terminal outcome; unknown codes are failures, never silent. */
        fun terminalInstallStatus(status: Int): InstallStatus = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallStatus.SUCCESS
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.ABORTED
            else -> InstallStatus.FAILURE
        }
    }
}
