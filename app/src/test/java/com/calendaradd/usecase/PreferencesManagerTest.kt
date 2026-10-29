package com.calendaradd.usecase

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PreferencesManagerTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var context: Context
    private lateinit var modelDir: File

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        modelDir = File("build/tmp/preferences-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun manager(memoryGb: Double?) = PreferencesManager(
        context,
        deviceMemoryGbProvider = { memoryGb },
        modelStorageRootProvider = { modelDir }
    )

    @Test
    fun `first read with empty preferences picks a memory-aware model and persists it`() {
        val cases = listOf(
            12.0 to "gemma-4-e4b",
            16.0 to "gemma-4-e4b",
            11.99 to "gemma-4-e2b",
            8.0 to "gemma-4-e2b",
            6.0 to "gemma-4-e2b",
            4.0 to "gemma-4-e2b",
            null to "gemma-4-e2b"
        )
        for ((memoryGb, expected) in cases) {
            prefs.values.clear()
            assertEquals("memory=$memoryGb", expected, manager(memoryGb).selectedModelId)
            assertEquals("memory=$memoryGb", expected, prefs.values["selected_model_id"])
        }
    }

    @Test
    fun `persisted first selection is stable across a different reported memory`() {
        assertEquals("gemma-4-e2b", manager(8.0).selectedModelId)
        assertEquals("gemma-4-e2b", manager(16.0).selectedModelId)
    }

    @Test
    fun `a stored valid id is never changed by the app`() {
        val ids = listOf("gemma-4-e2b", "gemma-4-e2b-compact", "gemma-4-e4b", "qwen-3_5-0_8b")
        for (id in ids) {
            for (memoryGb in listOf(4.0, 16.0)) {
                prefs.values.clear()
                prefs.values["selected_model_id"] = id
                assertEquals(id, manager(memoryGb).selectedModelId)
                assertEquals(id, prefs.values["selected_model_id"])
            }
        }
    }

    @Test
    fun `a stored removed Gemma 3n id is re-resolved and replaced`() {
        for (removed in listOf("gemma-3n-e2b", "gemma-3n-e4b")) {
            for ((memoryGb, expected) in listOf(16.0 to "gemma-4-e4b", 8.0 to "gemma-4-e2b")) {
                prefs.values.clear()
                prefs.values["selected_model_id"] = removed
                assertEquals(expected, manager(memoryGb).selectedModelId)
                assertEquals(expected, prefs.values["selected_model_id"])
            }
        }
    }

    @Test
    fun `upgrading install with an existing E2B file keeps E2B even on a 16 GB device`() {
        File(modelDir, "gemma-4-E2B-it.litertlm").writeText("existing")
        assertNull(prefs.values["selected_model_id"])

        assertEquals("gemma-4-e2b", manager(16.0).selectedModelId)
        assertEquals("gemma-4-e2b", prefs.values["selected_model_id"])
    }

    @Test
    fun `a user selection of Compact on a 16 GB device persists unchanged`() {
        val manager = manager(16.0)
        manager.selectedModelId = "gemma-4-e2b-compact"
        assertEquals("gemma-4-e2b-compact", manager.selectedModelId)
    }

    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? =
            if (values.containsKey(key)) values[key] as String? else defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            if (values.containsKey(key)) values[key] as MutableSet<String>? else defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { values[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { values[key] = value }
            override fun putInt(key: String, value: Int) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
