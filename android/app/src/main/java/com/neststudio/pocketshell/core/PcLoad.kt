package com.neststudio.pocketshell.core

import org.json.JSONObject
import kotlin.math.roundToInt

/** `GET /api/system` (host ≥ 0.2.105): CPU and GPU in percent (null: not known), memory in bytes. */
data class PcLoad(val cpu: Double?, val gpu: Double?, val memUsed: Long, val memTotal: Long) {
    /** Memory in use, percent; null when the PC did not say. */
    val memPercent: Double? get() = if (memTotal > 0) 100.0 * memUsed / memTotal else null

    companion object {
        fun parse(json: String): PcLoad {
            val o = JSONObject(json)
            fun pct(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() }?.coerceIn(0.0, 100.0) else null
            return PcLoad(pct("cpu"), pct("gpu"), o.optLong("memUsed", 0).coerceAtLeast(0), o.optLong("memTotal", 0).coerceAtLeast(0))
        }

        /** "12%", or "–" when unknown. */
        fun percentText(p: Double?): String = if (p == null) "–" else "${p.roundToInt()}%"

        /** "18.2/31.9GB" (one decimal). */
        fun gbText(used: Long, total: Long): String {
            fun gb(b: Long) = String.format(java.util.Locale.US, "%.1f", b / 1073741824.0)
            return "${gb(used)}/${gb(total)}GB"
        }
    }
}
