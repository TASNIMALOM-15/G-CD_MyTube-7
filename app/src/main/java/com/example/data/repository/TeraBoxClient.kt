package com.example.data.repository

import android.util.Base64
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class TeraBoxException(message: String) : Exception(message)

data class ShareFile(
    val fsId: Long,
    val path: String,
    val name: String,
    val isDir: Boolean,
    val category: Int,
    val size: Long = 0L,
    val thumb: String = ""
) {
    fun isVideo(): Boolean {
        if (isDir) return false
        if (category == 1) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "flv", "wmv", "3gp", "mpg", "mpeg")
    }
}

data class ShareScan(
    val shareId: String,
    val uk: String,
    val folderName: String?,
    val files: List<ShareFile>,
    val truncated: Boolean
)

private class Http(
    val code: Int,
    val body: String,
    val location: String?,
    val logid: String?,
    val regionPrefix: String?
)

private data class RemoteItem(val path: String, val name: String, val size: Long, val mtime: Long)

/**
 * Kotlin client for the few TeraBox calls we need, ported from the working TeraPlay app
 * (which itself ports the Node.js "terabox-api" library): desktop-app User-Agent, /main token scraping,
 * share listing, share/transfer into the user's own account, then /api/streaming for a full-length HLS playlist.
 * Blocking calls: always run off the main thread.
 */
class TeraBoxClient(cookieInput: String) {

    companion object {
        const val UA = "terabox;1.40.0.132;PC;PC-Windows;10.0.26100;WindowsTeraBox"
        const val DEST_DIR = "/MyTube-temp"
        const val DOMAIN = "terabox.com"
        const val STREAM_TYPE = "M3U8_AUTO_480"
        const val KEEP_REMOTE = 4
    }

    private val logLines = ArrayList<String>()
    private fun log(s: String) { logLines.add(s) }
    fun drainLog(): String {
        val s = logLines.joinToString("; ")
        logLines.clear()
        return s
    }

    private var whost = "https://www.$DOMAIN"
    private val cookies = LinkedHashMap<String, String>()
    private var jsToken = ""
    private var bdsToken = ""
    private var logId = "0"
    private var loggedIn = false
    private var destReady = false
    private val shareIdCache = HashMap<String, Pair<String, String>>()

    private val appParams = listOf(
        "app_id" to "250528",
        "web" to "1",
        "channel" to "dubox",
        "clienttype" to "0"
    )

    // The original library removes ECDHE-RSA-AES128-SHA from the TLS cipher list; do the same here.
    private val tlsSpec: ConnectionSpec = run {
        val suites = ConnectionSpec.MODERN_TLS.cipherSuites
            ?.filter { it != CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA }
            ?: emptyList()
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(*suites.toTypedArray())
            .build()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionSpecs(listOf(tlsSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        cookies["lang"] = "en"
        val c = cookieInput.trim().trim('"', '\'')
        if (c.contains("=")) {
            c.split(";").forEach { part ->
                val i = part.indexOf('=')
                if (i > 0) cookies[part.substring(0, i).trim()] = part.substring(i + 1).trim()
            }
        } else if (c.isNotEmpty()) {
            cookies["ndus"] = c
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun mergeCookies(r: Response) {
        for (h in r.headers("Set-Cookie")) {
            val first = h.substringBefore(';')
            val i = first.indexOf('=')
            if (i > 0) {
                val v = first.substring(i + 1).trim()
                if (v.isNotEmpty()) cookies[first.substring(0, i).trim()] = v
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun form(vararg p: Pair<String, String>): String =
        p.joinToString("&") { enc(it.first) + "=" + enc(it.second) }

    private fun buildUrl(path: String, q: List<Pair<String, String>> = emptyList()): String {
        val b = (whost + path).toHttpUrl().newBuilder()
        q.forEach { b.addQueryParameter(it.first, it.second) }
        return b.build().toString()
    }

    private fun snip(s: String, n: Int = 300): String = if (s.length > n) s.substring(0, n) + "…" else s

    private fun http(
        url: String,
        post: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        cookieSuffix: String = "",
        saveCookies: Boolean = false
    ): Http {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", cookieHeader() + cookieSuffix)
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        if (post != null) {
            b.post(post.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        }
        client.newCall(b.build()).execute().use { r ->
            if (saveCookies) mergeCookies(r)
            return Http(
                code = r.code,
                body = r.body?.string() ?: "",
                location = r.header("Location"),
                logid = r.header("logid"),
                regionPrefix = r.header("region-domain-prefix")
            )
        }
    }

    private fun parse(r: Http, what: String): JSONObject {
        if (r.code != 200) throw TeraBoxException("$what: HTTP ${r.code} ${snip(r.body)}")
        return try {
            JSONObject(r.body)
        } catch (e: Exception) {
            throw TeraBoxException("$what: উত্তর JSON নয়: ${snip(r.body)}")
        }
    }

    // ---------------------------------------------------------------- session

    /** Scrapes /main for jsToken / bdstoken. */
    private fun updateAppData() {
        var url = "$whost/main"
        var body = ""
        var finalUrl = url
        for (i in 1..6) {
            val r = http(url, saveCookies = true)
            finalUrl = url
            if (r.logid != null) logId = r.logid
            if (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307) {
                var loc = r.location ?: break
                if (loc == "/login") loc = "$whost/login"
                val resolved = url.toHttpUrl().resolve(loc) ?: break
                val newOrigin = "${resolved.scheme}://${resolved.host}"
                if (newOrigin != whost) whost = newOrigin
                url = resolved.toString()
                continue
            }
            body = r.body
            break
        }

        if (finalUrl.contains("/login")) {
            throw TeraBoxException("লগইন পেজে পাঠিয়ে দিয়েছে — ndus কুকি ভুল বা মেয়াদোত্তীর্ণ")
        }

        var tdata = JSONObject()
        val m = Regex("<script>var templateData = (.*);</script>").find(body)
        if (m != null) {
            try {
                tdata = JSONObject(m.groupValues[1].split(";</script>")[0])
            } catch (e: Exception) {
                log("templateData parse error: ${e.message}")
            }
        }

        var js = tdata.optString("jsToken", "")
        js = if (js.isNotEmpty()) {
            Regex("%28%22(.*)%22%29").find(js)?.groupValues?.get(1) ?: ""
        } else {
            Regex("window.jsToken%20%3D%20a%7D%3Bfn%28%22(.*)%22%29").find(body)?.groupValues?.get(1) ?: ""
        }
        jsToken = js
        bdsToken = tdata.optString("bdstoken", "")
        log("tokens: jsToken=${if (jsToken.isEmpty()) "NO" else "ok"}, bdstoken=${if (bdsToken.isEmpty()) "NO" else "ok"}")
    }

    private fun ensureTokens() {
        if (jsToken.isEmpty()) updateAppData()
        if (jsToken.isEmpty()) {
            throw TeraBoxException("jsToken পাওয়া যায়নি (কুকি ভুল হতে পারে, অথবা TeraBox পেজ বদলেছে)")
        }
    }

    private fun checkLogin() {
        for (attempt in 1..2) {
            val r = http(buildUrl("/api/check/login"))
            val region = r.regionPrefix
            if (!region.isNullOrEmpty() && attempt == 1) {
                whost = "https://$region.$DOMAIN"
                continue
            }
            val j = parse(r, "check/login")
            if (j.optInt("errno", -1) != 0) {
                throw TeraBoxException("লগইন হয়নি (errno=${j.optInt("errno")}) — ndus কুকি যাচাই করুন")
            }
            return
        }
    }

    private fun ensureSession() {
        if (!cookies.containsKey("ndus")) throw TeraBoxException("ndus কুকি দিন (Settings → TeraBox)")
        if (!loggedIn) {
            checkLogin()
            loggedIn = true
        }
        ensureTokens()
    }

    // ---------------------------------------------------------------- share link

    fun extractSurl(input: String): String? {
        val t = input.trim()
        Regex("[?&]surl=([A-Za-z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
        Regex("/s/1([A-Za-z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
        return null
    }

    private fun shareInfo(surl: String): JSONObject {
        val browserId = Base64.encodeToString(ByteArray(44).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val r = http(
            buildUrl("/api/shorturlinfo", listOf("shorturl" to "1$surl", "root" to "1")),
            cookieSuffix = "; browserid=$browserId"
        )
        return parse(r, "shorturlinfo")
    }

    private fun shareIds(info: JSONObject): Pair<String, String> {
        var shareId = info.optString("shareid", "")
        if (shareId.isEmpty()) shareId = info.optString("share_id", "")
        var uk = info.optString("uk", "")
        if (uk.isEmpty()) uk = info.optString("share_uk", "")
        if (shareId.isEmpty() || uk.isEmpty()) {
            throw TeraBoxException("shareid/uk পাওয়া যায়নি: ${snip(info.toString())}")
        }
        return shareId to uk
    }

    private fun shareList(surl: String, dir: String, retry: Boolean = true): JSONObject {
        ensureTokens()
        val q = mutableListOf(
            "jsToken" to jsToken,
            "shorturl" to surl,
            "by" to "name",
            "order" to "asc",
            "num" to "20000",
            "dir" to dir,
            "page" to "1"
        )
        if (dir.isEmpty()) q.add("root" to "1")
        val j = parse(http(buildUrl("/share/list", appParams + q)), "share/list")
        if (j.optInt("errno") == 4000020 && retry) {
            updateAppData()
            return shareList(surl, dir, false)
        }
        return j
    }

    private fun toShareFile(o: JSONObject): ShareFile {
        val path = o.optString("path", "")
        var name = o.optString("server_filename", "")
        if (name.isEmpty()) name = path.substringAfterLast('/')
        val thumbs = o.optJSONObject("thumbs")
        val thumb = thumbs?.optString("url3", "").orEmpty()
            .ifBlank { thumbs?.optString("url2", "").orEmpty() }
            .ifBlank { thumbs?.optString("url1", "").orEmpty() }
        return ShareFile(
            fsId = o.optLong("fs_id"),
            path = path,
            name = name,
            isDir = o.optInt("isdir") == 1,
            category = o.optInt("category", 0),
            size = o.optLong("size", 0L),
            thumb = thumb
        )
    }

    private fun toFiles(arr: JSONArray?): List<ShareFile> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { toShareFile(arr.getJSONObject(it)) }
    }

    /** Lists every file in the share (folders are walked recursively). */
    fun scanShare(surl: String, maxFiles: Int = 500, maxDirs: Int = 150): ShareScan {
        ensureSession()
        val info = shareInfo(surl)
        val errno = info.optInt("errno", 0)
        if (errno != 0) {
            throw TeraBoxException("শেয়ার লিঙ্ক পড়া যায়নি (errno=$errno): ${snip(info.toString())}")
        }
        val (shareId, uk) = shareIds(info)
        shareIdCache[surl] = shareId to uk

        var top = toFiles(info.optJSONArray("list"))
        if (top.isEmpty()) top = toFiles(shareList(surl, "").optJSONArray("list"))

        val folderName = if (top.size == 1 && top[0].isDir) top[0].name.ifBlank { null } else null
        val out = ArrayList<ShareFile>()
        val queue = ArrayDeque<String>()
        for (f in top) if (f.isDir) queue.addLast(f.path) else out.add(f)

        var dirs = 0
        while (queue.isNotEmpty() && out.size < maxFiles && dirs < maxDirs) {
            val dir = queue.removeFirst()
            dirs++
            val j = try { shareList(surl, dir) } catch (e: Exception) { log("dir skip: ${e.message}"); continue }
            for (f in toFiles(j.optJSONArray("list"))) {
                if (f.isDir) queue.addLast(f.path) else out.add(f)
            }
        }
        val truncated = queue.isNotEmpty() || out.size >= maxFiles
        return ShareScan(shareId, uk, folderName, out, truncated)
    }

    // ---------------------------------------------------------------- own account (transfer + stream)

    private fun createDirOnce() {
        if (destReady) return
        try {
            http("$whost/api/create?a=commit", post = form("path" to DEST_DIR, "isdir" to "1", "block_list" to "[]"))
        } catch (e: Exception) {
            log("createDir ignored: ${e.message}")
        }
        destReady = true
    }

    private fun listRemote(): List<RemoteItem> {
        return try {
            val r = http(
                "$whost/api/list",
                post = form(
                    "order" to "name", "desc" to "0", "dir" to DEST_DIR,
                    "num" to "20000", "page" to "1", "showempty" to "0"
                )
            )
            val j = parse(r, "api/list")
            if (j.optInt("errno", -1) != 0) return emptyList()
            val arr = j.optJSONArray("list") ?: return emptyList()
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                if (o.optInt("isdir") == 1) null
                else RemoteItem(
                    path = o.optString("path"),
                    name = o.optString("server_filename"),
                    size = o.optLong("size", 0L),
                    mtime = o.optLong("server_mtime", 0L)
                )
            }
        } catch (e: Exception) {
            log("listRemote failed: ${e.message}")
            emptyList()
        }
    }

    /** Keep only the newest few temp copies so the account never fills up (and stays far below the 20-file limit). */
    private fun cleanupRemote(keepPath: String) {
        try {
            val items = listRemote().sortedByDescending { it.mtime }
            val doomed = items.drop(KEEP_REMOTE).map { it.path }.filter { it != keepPath }
            if (doomed.isEmpty()) return
            val q = appParams + listOf("jsToken" to jsToken, "onnest" to "fail", "opera" to "delete")
            val r = http(
                buildUrl("/api/filemanager", q),
                post = form("filelist" to JSONArray(doomed).toString())
            )
            log("cleanup ${doomed.size}: errno=${runCatching { JSONObject(r.body).optInt("errno") }.getOrDefault(-1)}")
        } catch (e: Exception) {
            log("cleanup ignored: ${e.message}")
        }
    }

    private fun transfer(shareId: String, uk: String, f: ShareFile, retry: Boolean = true): String {
        ensureTokens()
        val q = appParams + listOf(
            "jsToken" to jsToken,
            "dp-logid" to logId,
            "ondup" to "newcopy",
            "async" to "1",
            "shareid" to shareId,
            "from" to uk
        )
        val r = http(
            buildUrl("/share/transfer", q),
            post = form("fsidlist" to "[${f.fsId}]", "path" to DEST_DIR),
            extraHeaders = mapOf("Referer" to whost)
        )
        val j = parse(r, "share/transfer")
        val errno = j.optInt("errno", -1)
        if (errno == 400810 && retry) {
            updateAppData()
            return transfer(shareId, uk, f, false)
        }
        if (errno != 0) {
            throw TeraBoxException("ট্রান্সফার ব্যর্থ (errno=$errno): ${snip(r.body)}")
        }
        val to = j.optJSONObject("extra")?.optJSONArray("list")?.optJSONObject(0)?.optString("to", "")
        return if (!to.isNullOrEmpty()) to else "$DEST_DIR/${f.name}"
    }

    private fun getStream(path: String): String {
        val r = http(
            "$whost/api/streaming",
            post = form("path" to path, "type" to STREAM_TYPE, "vip" to "2")
        )
        return r.body
    }

    /** Resolves the share's ids (cached per link, so replays need fewer requests). */
    fun idsFor(surl: String): Pair<String, String> {
        shareIdCache[surl]?.let { return it }
        ensureSession()
        val info = shareInfo(surl)
        val errno = info.optInt("errno", 0)
        if (errno != 0) throw TeraBoxException("শেয়ার লিঙ্ক পড়া যায়নি (errno=$errno): ${snip(info.toString())}")
        return shareIds(info).also { shareIdCache[surl] = it }
    }

    /** Copies ONE shared file into the user's own account (or reuses an earlier copy) and returns its HLS playlist text. */
    fun prepareStream(shareId: String, uk: String, f: ShareFile): String {
        ensureSession()
        createDirOnce()

        val existing = listRemote().firstOrNull { it.name == f.name && (f.size <= 0L || it.size == f.size) }
        val remotePath = if (existing != null) {
            log("reuse copy")
            existing.path
        } else {
            transfer(shareId, uk, f).also { log("transfer ok") }
        }

        var last = ""
        for (i in 1..15) {
            val body = getStream(remotePath)
            if (body.trimStart().startsWith("#EXTM3U")) {
                cleanupRemote(remotePath)
                return body
            }
            last = snip(body, 200)
            log("stream try $i: $last")
            Thread.sleep(2000)
        }
        throw TeraBoxException("স্ট্রিম পাওয়া যায়নি: $last")
    }
}
