package app.pillion.core

/**
 * Minimal semantic-version comparison: `MAJOR.MINOR.PATCH` with an optional `-prerelease` suffix
 * (a leading `v` is tolerated). A stable build outranks a prerelease at the same core version.
 * Single responsibility: ordering version strings; no I/O.
 */
object SemVer {
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun compare(a: String, b: String): Int {
        val (ac, ap) = parse(a)
        val (bc, bp) = parse(b)
        for (i in 0 until maxOf(ac.size, bc.size)) {
            val x = ac.getOrElse(i) { 0 }
            val y = bc.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        if (ap == bp) return 0
        if (ap.isEmpty()) return 1   // stable > prerelease
        if (bp.isEmpty()) return -1
        return ap.compareTo(bp)
    }

    private fun parse(v: String): Pair<List<Int>, String> {
        val s = v.trim().removePrefix("v").removePrefix("V")
        val dash = s.indexOf('-')
        val core = if (dash >= 0) s.substring(0, dash) else s
        val pre = if (dash >= 0) s.substring(dash + 1) else ""
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }
}
