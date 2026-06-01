package com.calendaradd.service

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerServiceTest {
    @Test
    fun `SemanticVersion parse should default missing minor and patch components`() {
        assertEquals(
            UpdateCheckerService.SemanticVersion(major = 1, minor = 2, patch = 0, preRelease = emptyList()),
            UpdateCheckerService.SemanticVersion.parse("v1.2")
        )
        assertEquals(
            UpdateCheckerService.SemanticVersion(major = 1, minor = 0, patch = 0, preRelease = emptyList()),
            UpdateCheckerService.SemanticVersion.parse("1")
        )
    }

    @Test
    fun `SemanticVersion parse should keep prerelease ordering`() {
        val release = requireNotNull(UpdateCheckerService.SemanticVersion.parse("1.2.0"))
        val alpha = requireNotNull(UpdateCheckerService.SemanticVersion.parse("1.2-alpha.1"))

        assertTrue(release > alpha)
    }

    @Test
    fun `SemanticVersion parse should reject malformed numeric components`() {
        assertNull(UpdateCheckerService.SemanticVersion.parse("1.2.3.4"))
        assertNull(UpdateCheckerService.SemanticVersion.parse("beta"))
    }

    @Test
    fun `findChecksumInReleaseNotes should reject ambiguous global hashes`() {
        val service = updateCheckerService()
        val first = "a".repeat(64)
        val second = "b".repeat(64)

        assertNull(service.invokeFindChecksumInReleaseNotes("$first\n$second", "CalendarAdd.apk"))
        assertEquals(first, service.invokeFindChecksumInReleaseNotes("CalendarAdd.apk $first\n$second", "CalendarAdd.apk"))
        assertEquals(first, service.invokeFindChecksumInReleaseNotes(first, "CalendarAdd.apk"))
    }
}

private fun updateCheckerService(): UpdateCheckerService {
    val prefs = mockk<SharedPreferences>(relaxed = true)
    val context = mockk<Context>(relaxed = true)
    every { context.applicationContext } returns context
    every { context.getSharedPreferences(any(), any()) } returns prefs
    return UpdateCheckerService(context)
}

private fun UpdateCheckerService.invokeFindChecksumInReleaseNotes(notes: String, apkName: String): String? {
    val method = UpdateCheckerService::class.java.getDeclaredMethod(
        "findChecksumInReleaseNotes",
        String::class.java,
        String::class.java
    )
    method.isAccessible = true
    return method.invoke(this, notes, apkName) as String?
}
