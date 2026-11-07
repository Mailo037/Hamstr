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
    fun desktopVolumeDefaultsToFullVolume() {
        Prefs.init(Counting())
        try {
            assertEquals(1f, Settings().desktopVolume)
            assertEquals(1f, Prefs.settings.value.desktopVolume)
            assertEquals(1f, Prefs.decode(emptyMap()).desktopVolume)
        } finally {
            Prefs.init(Counting())
        }
    }

    @Test
    fun desktopVolumeRoundTripsAsAStringAndWritesOnlyItsKey() {
        val store = Counting()
        Prefs.init(store)
        try {
            for (volume in listOf(0.37f, 0f, 1f)) {
                store.writes.clear()
                Prefs.edit { it.copy(desktopVolume = volume) }
                assertEquals(volume, Prefs.settings.value.desktopVolume)
                assertEquals(volume.toString(), store.values["desktopVolume"])
                assertEquals(listOf(setOf("desktopVolume")), store.writes)
                assertEquals(volume.toString(), Prefs.encode()["desktopVolume"])
                assertEquals(volume, Prefs.decode(Prefs.encode()).desktopVolume)
                Prefs.init(store)
                assertEquals(volume, Prefs.settings.value.desktopVolume)
                Prefs.edit { it.copy(desktopVolume = volume) }
                assertEquals(1, store.writes.size, "an unchanged volume writes nothing")
            }
        } finally {
            Prefs.init(Counting())
        }
    }

    @Test
    fun desktopVolumeClampsFiniteValuesWhenReadingAndWriting() {
        val store = Counting()
        try {
            for ((volume, expected) in listOf(-0.5f to 0f, 1.5f to 1f)) {
                store.values["desktopVolume"] = volume.toString()
                Prefs.init(store)
                assertEquals(expected, Prefs.settings.value.desktopVolume)
                assertEquals(expected, Prefs.decode(mapOf("desktopVolume" to volume.toString())).desktopVolume)
                assertEquals(expected.toString(), Prefs.encode(Settings(desktopVolume = volume))["desktopVolume"])

                Prefs.edit { it.copy(desktopVolume = 0.5f) }
                Prefs.edit { it.copy(desktopVolume = volume) }
                assertEquals(expected, Prefs.settings.value.desktopVolume)
                assertEquals(expected.toString(), store.values["desktopVolume"])
                Prefs.init(store)
                assertEquals(expected, Prefs.settings.value.desktopVolume)
            }
        } finally {
            Prefs.init(Counting())
        }
    }

    @Test
    fun corruptDesktopVolumeFallsBackToFullVolume() {
        val store = Counting()
        try {
            for (value in listOf(null, "", "not a volume", "NaN", "Infinity", "-Infinity", "1e100", true)) {
                store.values["desktopVolume"] = value
                Prefs.init(store)
                assertEquals(1f, Prefs.settings.value.desktopVolume, "stored value: $value")
                assertEquals(1f, Prefs.decode(mapOf("desktopVolume" to value)).desktopVolume, "backup value: $value")
            }
        } finally {
            Prefs.init(Counting())
        }
    }

    @Test
    fun nonFiniteDesktopVolumeEditsAndEncodingFallBackToFullVolume() {
        val store = Counting()
        Prefs.init(store)
        try {
            for (volume in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                Prefs.edit { it.copy(desktopVolume = 0.5f) }
                Prefs.edit { it.copy(desktopVolume = volume) }
                assertEquals(1f, Prefs.settings.value.desktopVolume)
                assertEquals("1.0", store.values["desktopVolume"])
                assertEquals("1.0", Prefs.encode(Settings(desktopVolume = volume))["desktopVolume"])
                Prefs.init(store)
                assertEquals(1f, Prefs.settings.value.desktopVolume)
            }
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
