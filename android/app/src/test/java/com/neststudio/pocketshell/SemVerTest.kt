package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(s: String) = requireNotNull(SemVer.parse(s)) { "unparsable $s" }

    @Test
    fun prereleaseOrderingFromDesign() {
        // DESIGN.md §11.1 and task contract
        assertTrue(v("0.1.0-alpha") < v("0.1.0"))
        assertTrue(v("0.1.0") < v("0.1.1-alpha"))
        assertTrue(v("0.1.0-alpha") < v("0.1.1-alpha"))
        assertTrue(v("0.1.1-alpha") < v("0.1.1"))
        assertTrue(v("0.1.1") < v("0.1.2-alpha"))
    }

    @Test
    fun semverSpecExampleChain() {
        // semver.org §11 example: 1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta
        // < 1.0.0-beta.2 < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0
        val chain = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        ).map(::v)
        for (i in 0 until chain.size - 1) assertTrue("${chain[i]} < ${chain[i + 1]}", chain[i] < chain[i + 1])
        assertEquals(chain, chain.shuffled(java.util.Random(7)).sorted())
    }

    @Test
    fun numericComparisonIsNumeric() {
        assertTrue(v("0.2.0") < v("0.10.0"))
        assertTrue(v("1.9.9") < v("1.10.0"))
        assertTrue(v("1.0.0-alpha.9") < v("1.0.0-alpha.10"))
    }

    @Test
    fun tagPrefixAndBuildMetadata() {
        assertEquals(v("0.1.1-alpha"), v("v0.1.1-alpha"))
        assertEquals(0, v("1.0.0+build.5").compareTo(v("1.0.0")))
        assertEquals("0.1.1-alpha", v("v0.1.1-alpha").toString())
    }

    @Test
    fun rejectsInvalid() {
        for (bad in listOf("", "1", "1.2", "1.2.3.4", "01.2.3", "1.2.3-", "1.2.3-01", "1.2.3-a..b", "x1.2.3", "1.2.3+")) {
            assertNull(bad, SemVer.parse(bad))
        }
    }
}
