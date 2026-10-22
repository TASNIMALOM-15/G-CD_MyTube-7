package com.example.data.repository

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class TeraBoxBatch(
    val folderName: String?,
    val items: List<ResolvedVideoInfo>,
    val truncated: Boolean,
    val nonVideoCount: Int,
    val debug: String = ""
)

data class PlaybackSource(val url: String, val method: String, val log: String)

/**
 * App-facing TeraBox logic.
 *  - resolveAll: scan a share link (single video or whole folder) and describe every video found.
 *  - resolveForPlayback: at play time, copy that ONE file into the user's account and get a full HLS playlist.
 * Saved videos keep: originalUrl = shareUrl#fs=<id>&sz=<size>&p=<share path>
 */
class TeraBoxResolver(private val cacheDir: File) {

    companion object {
        const val UA = TeraBoxClient.UA

        fun isTeraBoxHostName(host: String): Boolean {
            val h = host.lowercase()
            return h.contains("terabox") || h.contains("1024tera") ||
                h.contains("mirrobox") || h.contains("nephobox") || h.contains("4funbox")
        }

        fun isTeraBoxHost(url: String): Boolean = try {
            URI(url.trim()).host?.let { isTeraBoxHostName(it) } ?: false
        } catch (_: Exception) {
            false
        }

        fun normalizeCookie(raw: String): String {
            val c = raw.replace("\n", "").replace("\r", "").trim()
            return when {
                c.isEmpty() -> ""
                c.contains("=") -> c
                else -> "ndus=$c"
            }
        }
    }

    private val lock = Any()
    private var client: TeraBoxClient? = null
    private var clientCookie = ""

    private fun clientFor(rawCookie: String): TeraBoxClient {
        val c = rawCookie.trim()
        val existing = client
        if (existing != null && clientCookie == c) return existing
        return TeraBoxClient(c).also { client = it; clientCookie = c }
    }

    private fun fail(e: Exception, c: TeraBoxClient?): Exception {
        val log = c?.drainLog().orEmpty()
        client = null // force a fresh login next time
        val base = e.message ?: e.toString()
        return Exception(if (log.isNotBlank()) "$base [$log]" else base)
    }

    private fun urlHost(url: String): String = try { URI(url).host ?: "www.terabox.com" } catch (_: Exception) { "www.terabox.com" }

    /** Scans a share link: one video or a whole folder (with sub-folders). */
    fun resolveAll(url: String, rawCookie: String, maxFiles: Int = 500): Result<TeraBoxBatch> = synchronized(lock) {
        if (rawCookie.isBlank()) {
            return Result.failure(Exception("TeraBox কুকি সেট করা নেই। Settings → TeraBox থেকে ndus কুকি দিন।"))
        }
        val cleanUrl = url.trim().substringBefore('#')
        var c: TeraBoxClient? = null
        try {
            c = clientFor(rawCookie)
            val surl = c.extractSurl(cleanUrl)
                ?: return Result.failure(Exception("TeraBox লিংকটি চেনা যায়নি (/s/1xxxx বা ?surl=xxxx হতে হবে)।"))
            val scan = c.scanShare(surl, maxFiles)
            val videos = scan.files.filter { it.isVideo() }
                .sortedWith { a, b -> naturalCompare(a.path, b.path) }
            if (videos.isEmpty()) {
                return Result.failure(Exception("এই লিংকে কোনো ভিডিও পাওয়া যায়নি।"))
            }
            val host = urlHost(cleanUrl)
            val items = videos.map { f ->
                ResolvedVideoInfo(
                    title = f.name.substringBeforeLast('.').ifBlank { "TeraBox Video" },
                    streamUrl = cleanUrl, // placeholder; a fresh playable URL is made when the video is played
                    originalUrl = "$cleanUrl#fs=${f.fsId}&sz=${f.size}&p=${URLEncoder.encode(f.path, "UTF-8")}",
                    thumbnailUrl = f.thumb,
                    domain = host,
                    isDirectStream = false
                )
            }
            Result.success(
                TeraBoxBatch(
                    folderName = scan.folderName,
                    items = items,
                    truncated = scan.truncated,
                    nonVideoCount = scan.files.size - videos.size,
                    debug = c.drainLog()
                )
            )
        } catch (e: Exception) {
            Result.failure(fail(e, c))
        }
    }

    /** First video of a link (used by the generic single-link path). */
    fun resolveSingle(url: String, rawCookie: String): Result<ResolvedVideoInfo> =
        resolveAll(url, rawCookie).map { it.items.first() }

    /** Makes a full-length HLS playlist file for one saved TeraBox video. */
    fun resolveForPlayback(originalUrl: String, rawCookie: String): Result<PlaybackSource> = synchronized(lock) {
        if (rawCookie.isBlank()) return Result.failure(Exception("TeraBox কুকি সেট করা নেই।"))
        val pageUrl = originalUrl.substringBefore('#')
        val frag = originalUrl.substringAfter('#', "")
        val params = frag.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8") else null
        }.toMap()
        val fsId = params["fs"]?.toLongOrNull()
            ?: return Result.failure(Exception("ভিডিওর ফাইল আইডি নেই; ফোল্ডার লিংকটি আবার যুক্ত করুন।"))

        var c: TeraBoxClient? = null
        try {
            c = clientFor(rawCookie)
            val surl = c.extractSurl(pageUrl) ?: return Result.failure(Exception("TeraBox লিংক চেনা যায়নি।"))

            var path = params["p"].orEmpty()
            var size = params["sz"]?.toLongOrNull() ?: 0L
            var ids: Pair<String, String>
            if (path.isEmpty()) {
                // older saved entries: look the file up in the share
                val scan = c.scanShare(surl)
                val f = scan.files.firstOrNull { it.fsId == fsId }
                    ?: return Result.failure(Exception("ফাইলটি শেয়ারে আর নেই।"))
                path = f.path
                size = f.size
                ids = scan.shareId to scan.uk
            } else {
                ids = c.idsFor(surl)
            }
            val file = ShareFile(
                fsId = fsId, path = path, name = path.substringAfterLast('/'),
                isDir = false, category = 1, size = size
            )
            val m3u8 = c.prepareStream(ids.first, ids.second, file)

            // unique file name each time so the player cache can never serve an old playlist
            cacheDir.listFiles { f -> f.name.startsWith("tb_") && f.name.endsWith(".m3u8") }
                ?.filter { System.currentTimeMillis() - it.lastModified() > 30 * 60_000L }
                ?.forEach { it.delete() }
            val out = File(cacheDir, "tb_${fsId}_${System.currentTimeMillis()}.m3u8")
            out.writeText(m3u8)
            Result.success(PlaybackSource(android.net.Uri.fromFile(out).toString(), "hls", c.drainLog()))
        } catch (e: Exception) {
            Result.failure(fail(e, c))
        }
    }

    private fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
}
