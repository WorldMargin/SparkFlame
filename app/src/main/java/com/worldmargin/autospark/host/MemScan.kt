package com.worldmargin.autospark.host

/** 临时：扫描本进程内存里含 lite_emoji 的片段。 */
object MemScan {
    fun dump() {
        val sb = StringBuilder()
        runCatching {
            val maps = java.io.File("/proc/self/maps").readLines()
            java.io.RandomAccessFile("/proc/self/mem", "r").use { mem ->
                maps.forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 2 || !parts[1].startsWith("r")) return@forEach
                    val range = parts[0].split("-")
                    if (range.size != 2) return@forEach
                    val start = range[0].toLong(16)
                    val end = range[1].toLong(16)
                    if (end <= start || end - start > 300L * 1024 * 1024) return@forEach
                    val buf = ByteArray(1024 * 1024)
                    var pos = start
                    runCatching { mem.seek(start) }
                    while (pos < end) {
                        val want = minOf(buf.size.toLong(), end - pos).toInt()
                        val n = runCatching { mem.read(buf, 0, want) }.getOrDefault(-1)
                        if (n <= 0) break
                        val text = String(buf, 0, n, Charsets.ISO_8859_1)
                        var idx = text.indexOf("lite_emoji")
                        while (idx >= 0) {
                            val from = maxOf(0, idx - 1200)
                            val to = minOf(n, idx + 1200)
                            sb.append(String(buf, from, to - from, Charsets.UTF_8)).append("\n---\n")
                            idx = text.indexOf("lite_emoji", idx + 1)
                        }
                        pos += n
                    }
                }
            }
        }
        runCatching {
            java.io.File("/data/user/0/com.ss.android.ugc.aweme/files/spark_mem_hits.txt").writeText(sb.toString())
        }
    }
}
