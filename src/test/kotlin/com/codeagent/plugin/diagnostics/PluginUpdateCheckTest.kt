package com.codeagent.plugin.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginUpdateCheckTest {
    @Test
    fun `shouldNotify returns false when current version is unknown`() {
        assertFalse(PluginUpdateCheck.shouldNotify(null, "1.2.3", null))
        assertFalse(PluginUpdateCheck.shouldNotify("", "1.2.3", null))
        assertFalse(PluginUpdateCheck.shouldNotify("   ", "1.2.3", null))
    }

    @Test
    fun `shouldNotify returns false when latest version is unknown`() {
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.3", null, null))
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.3", "", null))
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.3", "   ", null))
    }

    @Test
    fun `shouldNotify returns false when latest was already notified`() {
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.3", "1.2.4", "1.2.4"))
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.3", "1.2.4", " 1.2.4 "))
    }

    @Test
    fun `shouldNotify returns true when latest is newer and not yet notified`() {
        assertTrue(PluginUpdateCheck.shouldNotify("1.2.3", "1.2.4", null))
        assertTrue(PluginUpdateCheck.shouldNotify("1.2.3", "1.2.4", ""))
        assertTrue(PluginUpdateCheck.shouldNotify("1.2.3", "1.2.4", "1.2.3"))
        assertTrue(PluginUpdateCheck.shouldNotify("1.2.3", "1.3.0", "1.2.4"))
    }

    @Test
    fun `shouldNotify returns false when current is already the latest`() {
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.3", "1.2.3", null))
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.3", "1.2.3", "1.2.2"))
    }

    @Test
    fun `shouldNotify returns false when current is newer than latest`() {
        assertFalse(PluginUpdateCheck.shouldNotify("1.2.4", "1.2.3", null))
        assertFalse(PluginUpdateCheck.shouldNotify("2.0.0", "1.9.9", null))
    }

    @Test
    fun `compareVersions orders numeric segments correctly`() {
        assertTrue(PluginUpdateCheck.compareVersions("1.2.4", "1.2.3") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.3.0", "1.2.9") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3", "1.2.4") < 0)
        assertEquals(0, PluginUpdateCheck.compareVersions("1.2.3", "1.2.3"))
    }

    @Test
    fun `compareVersions handles different segment counts`() {
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3.1", "1.2.3") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2", "1.2.0") == 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2", "1.1.9") > 0)
    }

    @Test
    fun `compareVersions treats release as newer than pre-release`() {
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3", "1.2.3-alpha") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3", "1.2.3-beta.1") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-alpha", "1.2.3") < 0)
    }

    @Test
    fun `compareVersions orders pre-release identifiers lexicographically`() {
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-beta", "1.2.3-alpha") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-beta.2", "1.2.3-beta.1") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-rc.1", "1.2.3-beta.9") > 0)
    }

    @Test
    fun `compareVersions treats numeric pre-release identifiers as numbers`() {
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-alpha.10", "1.2.3-alpha.9") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-alpha.2", "1.2.3-alpha.10") < 0)
    }

    @Test
    fun `compareVersions ignores build metadata after plus`() {
        assertEquals(0, PluginUpdateCheck.compareVersions("1.2.3+build.123", "1.2.3+build.456"))
        assertEquals(0, PluginUpdateCheck.compareVersions("1.2.3+build", "1.2.3"))
        assertTrue(PluginUpdateCheck.compareVersions("1.2.4+build", "1.2.3+build") > 0)
    }

    @Test
    fun `compareVersions handles v prefix`() {
        assertEquals(0, PluginUpdateCheck.compareVersions("v1.2.3", "1.2.3"))
        assertTrue(PluginUpdateCheck.compareVersions("v1.2.4", "v1.2.3") > 0)
    }

    @Test
    fun `compareVersions handles pre-release of different lengths`() {
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-alpha.1.2", "1.2.3-alpha.1") > 0)
        assertTrue(PluginUpdateCheck.compareVersions("1.2.3-alpha.1", "1.2.3-alpha.1.2") < 0)
    }
}
