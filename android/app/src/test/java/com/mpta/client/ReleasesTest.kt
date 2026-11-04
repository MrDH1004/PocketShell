package com.mpta.client

import com.mpta.client.core.Releases
import com.mpta.client.core.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleasesTest {

    private val hexA = "a".repeat(64)
    private val hexB = "0123456789abcdef".repeat(4)

    private fun asset(name: String, digest: String? = null) =
        """{"name":"$name","browser_download_url":"https://example.test/$name"${if (digest != null) ""","digest":"$digest"""" else ""","digest":null"""}}"""

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, pre: Boolean = true, body: String = "notes $tag") =
        """{"tag_name":"$tag","name":"mpta $tag","draft":$draft,"prerelease":$pre,"body":"$body","assets":[${assets.joinToString(",")}]}"""

    @Test
    fun picksHighestSemverNotListOrderAndSkipsDrafts() {
        val json = "[" + listOf(
            release("v0.1.0-alpha", asset("mpta-android-v0.1.0-alpha.apk", "sha256:$hexA")),
            release("v0.2.0", asset("mpta-android-v0.2.0.apk", "sha256:$hexA"), draft = true),
            release("v0.1.1-alpha", asset("mpta-android-v0.1.1-alpha.apk", "sha256:${hexB.uppercase()}")),
            release("v0.1.1-alpha.0", asset("mpta-android-v0.1.1-alpha.0.apk", "sha256:$hexA")),
            release("nightly", asset("mpta-android-vnightly.apk")),
        ).joinToString(",") + "]"
        val c = Releases.pickLatestAndroid(json)!!
        assertEquals(SemVer.parse("0.1.1-alpha.0"), c.version)
        assertEquals("mpta-android-v0.1.1-alpha.0.apk", c.apkName)
        assertEquals("https://example.test/mpta-android-v0.1.1-alpha.0.apk", c.apkUrl)
        assertEquals(hexA, c.apkSha256)
        assertEquals("notes v0.1.1-alpha.0", c.notes)
    }

    @Test
    fun skipsReleaseWithoutAndroidAsset() {
        val json = "[" + release("v0.3.0", asset("mpta-host-win-x64-v0.3.0.zip", "sha256:$hexA")) + "," +
            release("v0.2.0", asset("mpta-android-v0.2.0.apk", "sha256:$hexB")) + "]"
        assertEquals(SemVer.parse("0.2.0"), Releases.pickLatestAndroid(json)!!.version)
    }

    @Test
    fun digestNormalizedAndSumsFallback() {
        val json = "[" + release(
            "v0.1.1-alpha",
            asset("mpta-android-v0.1.1-alpha.apk"),
            asset("SHA256SUMS.txt"),
        ) + "]"
        val c = Releases.pickLatestAndroid(json)!!
        assertNull(c.apkSha256)
        assertEquals("https://example.test/SHA256SUMS.txt", c.sumsUrl)

        val sums = "$hexA  mpta-host-win-x64-v0.1.1-alpha.zip\n${hexB.uppercase()} *mpta-android-v0.1.1-alpha.apk\n"
        assertEquals(hexB, Releases.parseSha256Sums(sums, c.apkName))
        assertNull(Releases.parseSha256Sums(sums, "other.apk"))
        assertNull(Releases.parseSha256Sums("zz  mpta-android-v0.1.1-alpha.apk", c.apkName))
    }

    @Test
    fun parseDigestRejectsOtherAlgorithmsAndBadHex() {
        assertEquals(hexA, Releases.parseDigest("sha256:$hexA"))
        assertEquals(hexA, Releases.parseDigest("SHA256:${hexA.uppercase()}"))
        assertNull(Releases.parseDigest("sha512:$hexA"))
        assertNull(Releases.parseDigest("sha256:${hexA.dropLast(1)}"))
        assertNull(Releases.parseDigest(null))
    }

    @Test
    fun newerThanUsesSemverPrecedence() {
        val json = "[" + release("v0.1.1-alpha", asset("mpta-android-v0.1.1-alpha.apk", "sha256:$hexA")) + "]"
        val c = Releases.pickLatestAndroid(json)
        assertNotNull(Releases.newerThan("0.1.0-alpha", c))
        assertNotNull(Releases.newerThan("0.1.0", c))
        assertNull(Releases.newerThan("0.1.1-alpha", c))
        assertNull(Releases.newerThan("0.1.1", c))
        assertNull(Releases.newerThan("garbage", c))
        assertNull(Releases.newerThan("0.1.0", null))
    }

    @Test
    fun emptyAndInvalidBodies() {
        assertNull(Releases.pickLatestAndroid("[]"))
        assertThrows(IllegalArgumentException::class.java) { Releases.pickLatestAndroid("{\"message\":\"rate limited\"}") }
    }
}
