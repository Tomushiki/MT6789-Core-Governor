/*
 * CoreGovernor - custom CPU/GPU throttling governor
 * Copyright (C) 2026 Azrael
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.example.root

/**
 * v4.1 Pure helper for ordered multi-file sysfs writes in ONE root-shell round trip.
 * Each write reports its own exit code, so a failure on one file never hides another.
 */
object SysfsBatchWrite {
    const val MARKER = "__CGW_"

    private fun q(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

    /** [needsChmod] is asked once per path; true adds the same first-time chmod the single write uses. */
    fun buildCommand(writes: List<Pair<String, String>>, needsChmod: (String) -> Boolean): String {
        val sb = StringBuilder()
        writes.forEachIndexed { i, (path, value) ->
            val qp = q(path)
            if (needsChmod(path)) sb.append("chmod u+w $qp 2>/dev/null; ")
            sb.append("{ printf '%s' ${q(value)} > $qp; } 2>/dev/null; echo \"${MARKER}${i}_\$?\"\n")
        }
        return sb.toString()
    }

    /** Exit code per write index; -1 where no result line was found. */
    fun parseCodes(stdout: String, count: Int): IntArray {
        val codes = IntArray(count) { -1 }
        for (raw in stdout.lines()) {
            val line = raw.trim()
            if (!line.startsWith(MARKER)) continue
            val parts = line.removePrefix(MARKER).split('_')
            if (parts.size != 2) continue
            val idx = parts[0].toIntOrNull() ?: continue
            val code = parts[1].toIntOrNull() ?: continue
            if (idx in 0 until count) codes[idx] = code
        }
        return codes
    }
}
