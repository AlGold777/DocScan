package app.docscan.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentifiersTest {
    @Test
    fun generatedIdentifiersAreCanonicalAndParseable() {
        val id = PageId.new()

        assertEquals(id, PageId.parse(id.value))
        assertEquals(36, id.value.length)
    }

    @Test
    fun malformedAndNonCanonicalIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PageId.parse("") }
        assertThrows(IllegalArgumentException::class.java) {
            PageId.parse("A0B1C2D3-E4F5-4678-9ABC-DEF012345678")
        }
    }
}
