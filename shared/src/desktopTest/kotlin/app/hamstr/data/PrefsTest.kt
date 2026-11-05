package app.hamstr.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrefsTest {
    /** Settings in memory, counting what each apply writes. */
    private class Counting : KeyValues {
        val values = HashMap<String, Any?>()
        val writes = mutableListOf<Set<String>>()
        var failNext = false
        override fun getString(key: String, default: String?) = values[key] as String? ?: default
        override fun getInt(key: String, default: Int) = values[key] as Int? ?: default
        override fun getLong(key: String, default: Long) = values[key] as Long? ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as Boolean? ?: default
        override fun all(): Map<String, Any?> = values
        override fun edit(): KeyValues.Editor = object : KeyValues.Editor {
            val changes = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun apply() {
                if (failNext) { failNext = false; throw java.io.IOException("disk full") }
                values.putAll(changes); writes += changes.keys.toSet()
            }
        }
    }

    @Test
    fun anEditWritesOnlyTheKeysItChanges() {
        val store = Counting()
        Prefs.init(store)
        try {
            Prefs.edit { it }
            Prefs.edit { it.copy(accent = it.accent) }
            assertTrue(store.writes.isEmpty(), "an edit that changes nothing writes nothing")
            Prefs.edit { it.copy(accent = 0x112233, eqGains = List(10) { i -> i.toFloat() }) }
            assertEquals(listOf(setOf("accent", "eq.gains")), store.writes)
            // Read back as it was written.
            Prefs.init(store)
            assertEquals(0x112233, Prefs.settings.value.accent)
            assertEquals(List(10) { i -> i.toFloat() }, Prefs.settings.value.eqGains)
            assertEquals(Settings().copy(accent = 0x112233, eqGains = List(10) { i -> i.toFloat() }), Prefs.settings.value)
        } finally {
            Prefs.init(Counting())
        }
    }

    @Test
    fun aFailedWriteIsMadeUpByTheNextEdit() {
        val store = Counting()
        Prefs.init(store)
        try {
            store.failNext = true
            assertFailsWith<java.io.IOException> { Prefs.edit { it.copy(accent = 0x445566) } }
            assertTrue(store.writes.isEmpty())
            // The same value again: memory has it already, the store doesn't, so it is written now.
            Prefs.edit { it.copy(accent = 0x445566) }
            assertEquals(0x445566, store.values["accent"])
            Prefs.init(store)
            assertEquals(0x445566, Prefs.settings.value.accent)
            // Caught up: the next edit writes only what it changes again.
            store.writes.clear()
            Prefs.edit { it.copy(crossfade = 3) }
            assertEquals(listOf(setOf("crossfade")), store.writes)
        } finally {
            Prefs.init(Counting())
        }
    }
}
