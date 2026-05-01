package com.calendaradd.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtReleasePackagingTest {

    @Test
    fun `release minification should keep LiteRT LM JNI facing classes`() {
        val proguardRules = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro")
        ).first { it.exists() }.readText()

        assertTrue(
            "LiteRT-LM native code uses exact JNI class and method names.",
            proguardRules.contains("-keep class com.google.ai.edge.litertlm.** { *; }")
        )
    }
}
