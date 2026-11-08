package com.neststudio.pocketshell.core

/**
 * SemVer 2.0.0 version (DESIGN.md §11.1). Precedence: major/minor/patch numerically, then a version with a
 * prerelease is lower than the same version without; prerelease identifiers compare left to right, numeric
 * identifiers numerically and lower than alphanumeric ones, a shorter identifier list is lower when all
 * preceding identifiers are equal. Build metadata (`+...`) is ignored for precedence.
 */
data class SemVer(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        val n = minOf(prerelease.size, other.prerelease.size)
        for (i in 0 until n) {
            val c = compareIdentifier(prerelease[i], other.prerelease[i])
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    companion object {
        private val CORE = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
        private val IDENT = Regex("""^[0-9A-Za-z-]+$""")

        /** Parses `1.2.3`, `1.2.3-alpha.1`, `1.2.3+build`; a leading `v`/`V` (git tag) is accepted. Null if invalid. */
        fun parse(text: String): SemVer? {
            var s = text.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            val plus = s.indexOf('+')
            if (plus >= 0) {
                val build = s.substring(plus + 1)
                if (build.isEmpty() || build.split('.').any { !IDENT.matches(it) }) return null
                s = s.substring(0, plus)
            }
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1) else null
            val m = CORE.matchEntire(core) ?: return null
            val nums = m.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            val ids = if (pre == null) {
                emptyList()
            } else {
                val parts = pre.split('.')
                for (p in parts) {
                    if (!IDENT.matches(p)) return null
                    if (p.length > 1 && p.startsWith('0') && p.all { it.isDigit() }) return null
                }
                parts
            }
            return SemVer(nums[0], nums[1], nums[2], ids)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = a.all { it.isDigit() }
            val bn = b.all { it.isDigit() }
            return when {
                an && bn -> {
                    // Compare by length first so arbitrarily long numeric identifiers don't overflow.
                    if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                }
                an -> -1
                bn -> 1
                else -> a.compareTo(b) // ASCII order
            }
        }
    }
}
