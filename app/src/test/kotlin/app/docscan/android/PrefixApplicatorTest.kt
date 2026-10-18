package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefixApplicatorTest {

    @Test
    fun `applyPrefix prepends prefix when no old prefix was present`() {
        val result = PrefixApplicator.applyPrefix("2026-10-17 17-05.pdf", "", "договор за газ")
        assertEquals("договор за газ 2026-10-17 17-05.pdf", result)
    }

    @Test
    fun `applyPrefix replaces old prefix cleanly`() {
        val result = PrefixApplicator.applyPrefix("договор за газ 2026-10-17 17-05.pdf", "договор за газ", "договор за воду")
        assertEquals("договор за воду 2026-10-17 17-05.pdf", result)
    }

    @Test
    fun `applyPrefix strips old prefix when new prefix is blank`() {
        val result = PrefixApplicator.applyPrefix("договор за газ 2026-10-17 17-05.pdf", "договор за газ", "")
        assertEquals("2026-10-17 17-05.pdf", result)
    }

    @Test
    fun `removePrefix strips prefix with space`() {
        val result = PrefixApplicator.removePrefix("договор за газ 2026-10-17 17-05.pdf", "договор за газ")
        assertEquals("2026-10-17 17-05.pdf", result)
    }

    @Test
    fun `removePrefix returns unchanged string if prefix not matching`() {
        val result = PrefixApplicator.removePrefix("2026-10-17 17-05.pdf", "договор за газ")
        assertEquals("2026-10-17 17-05.pdf", result)
    }

    @Test
    fun `applyPrefix works when user appended extra text after the date`() {
        val result = PrefixApplicator.applyPrefix("договор за газ 2026-10-17 17-05 квартира 5.pdf", "договор за газ", "акт")
        assertEquals("акт 2026-10-17 17-05 квартира 5.pdf", result)
    }
}
