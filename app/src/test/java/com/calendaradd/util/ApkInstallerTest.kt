package com.calendaradd.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.calendaradd.BuildConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApkInstallerTest {
    private lateinit var apkFile: File

    @Before
    fun setup() {
        apkFile = File.createTempFile("update", ".apk").apply { writeText("apk bytes") }
        InstallStatusReceiver.reset()
    }

    /** Fresh mock harness for one API level, so per-level verify counts never accumulate. */
    private class Harness(
        val context: Context,
        val params: PackageInstaller.SessionParams,
        val session: PackageInstaller.Session,
        val pendingIntent: PendingIntent,
        installerSdk: Int
    ) {
        val installer = ApkInstaller(
            context = context,
            sdkInt = installerSdk,
            paramsFactory = { params },
            callbackIntentFactory = { mockk(relaxed = true) },
            pendingIntentFactory = { _, _, _, _ -> pendingIntent }
        )

        companion object {
            fun forApi(api: Int): Harness {
                val context = mockk<Context>(relaxed = true)
                val packageManager = mockk<PackageManager>(relaxed = true)
                val packageInstaller = mockk<PackageInstaller>(relaxed = true)
                val params = mockk<PackageInstaller.SessionParams>(relaxed = true)
                val session = mockk<PackageInstaller.Session>(relaxed = true)
                every { context.packageManager } returns packageManager
                every { packageManager.canRequestPackageInstalls() } returns true
                every { packageManager.packageInstaller } returns packageInstaller
                every { packageInstaller.createSession(any()) } returns 11
                every { packageInstaller.openSession(11) } returns session
                return Harness(context, params, session, mockk(relaxed = true), api)
            }
        }
    }

    // ---- B03/AC19: API 26-35 all go through PackageInstaller + user confirmation ----

    @Test
    fun `every API level 26 to 35 installs via PackageInstaller with user confirmation`() = runBlocking {
        (26..35).forEach { api ->
            val harness = Harness.forApi(api)
            val result = harness.installer.install(apkFile)

            assertEquals("API $api", InstallResult.Started, result)
            verify(exactly = 1) { harness.params.setAppPackageName(BuildConfig.APPLICATION_ID) }
            // The session commits with a status callback to InstallStatusReceiver, which is
            // the only path that launches the OS confirmation dialog.
            verify(exactly = 1) { harness.session.commit(harness.pendingIntent.intentSender) }
            if (api >= 31) {
                verify(exactly = 1) {
                    harness.params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            // No silent ACTION_INSTALL_PACKAGE fallback on any API level.
            verify(exactly = 0) { harness.context.startActivity(any()) }
        }
    }

    @Test
    fun `PackageInstaller failure surfaces as Failed instead of falling back`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val packageManager = mockk<PackageManager>(relaxed = true)
        val packageInstaller = mockk<PackageInstaller>(relaxed = true)
        every { context.packageManager } returns packageManager
        every { packageManager.canRequestPackageInstalls() } returns true
        every { packageManager.packageInstaller } returns packageInstaller
        every { packageInstaller.createSession(any()) } throws SecurityException("session denied")
        val installer = ApkInstaller(context = context, sdkInt = 33)

        val result = installer.install(apkFile)

        assertTrue(result is InstallResult.Failed)
        // B03: no ACTION_INSTALL_PACKAGE fallback path may run.
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `missing apk file fails fast`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val installer = ApkInstaller(context = context, sdkInt = 30)

        val result = installer.install(File("no-such.apk"))

        assertTrue(result is InstallResult.Failed)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    // ---- R10/AC18: install status publishes to the StateFlow consumed by SettingsViewModel ----

    @Test
    fun `terminal install status is published to the state flow`() {
        val intent = mockk<Intent>()
        every { intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) } returns
            PackageInstaller.STATUS_FAILURE
        every { intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) } returns "boom"

        InstallStatusReceiver().onReceive(mockk(relaxed = true), intent)

        assertEquals(InstallStatus.FAILURE, InstallStatusReceiver.statusFlow.value)
    }

    @Test
    fun `reset clears the terminal install status`() {
        val intent = mockk<Intent>()
        every { intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) } returns
            PackageInstaller.STATUS_SUCCESS
        every { intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) } returns null
        InstallStatusReceiver().onReceive(mockk(relaxed = true), intent)
        assertEquals(InstallStatus.SUCCESS, InstallStatusReceiver.statusFlow.value)

        InstallStatusReceiver.reset()

        assertNull(InstallStatusReceiver.statusFlow.value)
    }

    @Test
    fun `unknown PackageInstaller status codes map to FAILURE, never silent`() {
        assertEquals(InstallStatus.SUCCESS, InstallStatusReceiver.terminalInstallStatus(PackageInstaller.STATUS_SUCCESS))
        assertEquals(InstallStatus.ABORTED, InstallStatusReceiver.terminalInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(InstallStatus.FAILURE, InstallStatusReceiver.terminalInstallStatus(PackageInstaller.STATUS_FAILURE))
        assertEquals(InstallStatus.FAILURE, InstallStatusReceiver.terminalInstallStatus(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertEquals(InstallStatus.FAILURE, InstallStatusReceiver.terminalInstallStatus(999))
    }

    // ---- R11/AC19: the receiver itself launches the OS confirmation activity ----

    @Test
    fun `STATUS_PENDING_USER_ACTION launches the confirmation activity with NEW_TASK`() {
        val receiver = InstallStatusReceiver()
        val context = mockk<Context>(relaxed = true)
        val confirmation = mockk<Intent>(relaxed = true)
        val intent = mockk<Intent>()
        every { intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) } returns
            PackageInstaller.STATUS_PENDING_USER_ACTION
        every { intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) } returns confirmation

        receiver.onReceive(context, intent)

        verify(exactly = 1) { confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        verify(exactly = 1) { context.startActivity(confirmation) }
    }

    @Test
    fun `STATUS_PENDING_USER_ACTION without EXTRA_INTENT publishes FAILURE and never crashes`() {
        val receiver = InstallStatusReceiver()
        val context = mockk<Context>(relaxed = true)
        val intent = mockk<Intent>()
        every { intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) } returns
            PackageInstaller.STATUS_PENDING_USER_ACTION
        every { intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) } returns null

        receiver.onReceive(context, intent)

        verify(exactly = 0) { context.startActivity(any()) }
        // A4: confirmation can never proceed, so the UI must stop claiming the installer opened.
        assertEquals(InstallStatus.FAILURE, InstallStatusReceiver.statusFlow.value)
    }

    // ---- A2: terminal outcomes are durable so they survive process death ----

    @Test
    fun `terminal outcome is persisted durably so it survives process death`() {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        val intent = mockk<Intent>()
        every { intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) } returns
            PackageInstaller.STATUS_SUCCESS
        every { intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) } returns null

        InstallStatusReceiver().onReceive(context, intent)

        // commit(), not apply(): the process may die the moment onReceive returns.
        verify { editor.putString(any(), InstallStatus.SUCCESS.name) }
        verify { editor.commit() }
    }
}
