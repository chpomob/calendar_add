package com.calendaradd.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Terminal outcome of a PackageInstaller session. */
enum class InstallStatus { SUCCESS, FAILURE, ABORTED }

/**
 * Receives PackageInstaller session status callbacks. Non-exported: only the pending intent
 * minted by [ApkInstaller] can address it. All user feedback flows through [statusFlow]
 * (consumed by the Settings UI, R10/AC18) — the receiver itself never touches the UI.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // R11/AC19: the only confirmation path on every API level — launch the OS
                // install dialog the system attached to this status.
                val confirmation = confirmationIntent(intent)
                if (confirmation == null) {
                    // A4: without the confirmation intent the install can never proceed —
                    // publish FAILURE so the UI stops claiming that the installer opened.
                    AppLog.e(TAG, "STATUS_PENDING_USER_ACTION without EXTRA_INTENT")
                    publishTerminal(context, InstallStatus.FAILURE)
                } else {
                    AppLog.i(TAG, "Launching install confirmation")
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                }
            }
            else -> {
                val terminal = terminalInstallStatus(status)
                publishTerminal(context, terminal)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (terminal == InstallStatus.SUCCESS) {
                    AppLog.i(TAG, "Install succeeded")
                } else {
                    AppLog.e(TAG, "Install $terminal (status=$status): ${message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * A2: terminal outcomes must outlive this process — a successful self-update replaces it
     * before the user returns to Settings. Publish in-process AND durably (commit(), not
     * apply(): the process may die the moment onReceive returns).
     */
    private fun publishTerminal(context: Context, status: InstallStatus) {
        publish(status)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TERMINAL_STATUS, status.name).commit()
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
        private const val PREFS_NAME = "install_status"
        private const val KEY_TERMINAL_STATUS = "terminal"
        private val _statusFlow = MutableStateFlow<InstallStatus?>(null)

        /** Last terminal install outcome; null until the session succeeds, fails or is aborted. */
        val statusFlow: StateFlow<InstallStatus?> = _statusFlow.asStateFlow()

        /** Sets the terminal outcome; also used to rehydrate a persisted outcome at UI startup (A2). */
        fun publish(status: InstallStatus) {
            _statusFlow.value = status
        }

        /** Clears the terminal outcome, e.g. when a fresh update install begins. */
        fun reset() {
            _statusFlow.value = null
        }

        /** Reads and clears the durable outcome (A2); null when none is pending. */
        internal fun consumePersistedOutcome(context: Context): InstallStatus? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_TERMINAL_STATUS, null) ?: return null
            prefs.edit().remove(KEY_TERMINAL_STATUS).commit()
            return runCatching { InstallStatus.valueOf(name) }.getOrNull()
        }

        /** Clears the durable outcome, e.g. when a fresh update install begins. */
        internal fun clearPersistedOutcome(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_TERMINAL_STATUS).commit()
        }

        /** Maps any PackageInstaller status code to a terminal outcome; unknown codes are failures, never silent. */
        fun terminalInstallStatus(status: Int): InstallStatus = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallStatus.SUCCESS
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.ABORTED
            else -> InstallStatus.FAILURE
        }
    }
}
