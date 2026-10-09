package com.litas615.evenlyrics.companion

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object LyricsFetcher {
    private const val TAG = "LyricsFetcher"

    /** Synced lyrics come only from the public LRCLIB API (https://lrclib.net). */
    fun fetch(title: String, artist: String?, durationMs: Long?): String? {
        val cleanTitle = title.trim()
        val cleanArtist = artist?.trim().orEmpty()
        if (cleanTitle.isEmpty()) return null
        return try {
            fetchFromLrclib(cleanTitle, cleanArtist, durationMs)?.takeIf { it.isNotBlank() }
                ?.also { Log.i(TAG, "Resolved lyrics from LRCLIB for $cleanTitle ($cleanArtist)") }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB lookup failed: ${e.message}")
            null
        }
    }

    private fun fetchFromLrclib(title: String, artist: String, durationMs: Long?): String? {
        val durationSec = if (durationMs != null && durationMs > 0) Math.round(durationMs / 1000.0) else 0L

        // 1. Exact GET
        if (artist.isNotEmpty() && !artist.equals("未知演出者", true)) {
            val getUrl = "https://lrclib.net/api/get?track_name=${URLEncoder.encode(title, "UTF-8")}&artist_name=${URLEncoder.encode(artist, "UTF-8")}" +
                    if (durationSec > 0) "&duration=$durationSec" else ""
            val json = httpGet(getUrl)
            if (json != null) {
                val obj = JSONObject(json)
                val synced = obj.optString("syncedLyrics")
                if (synced.isNotEmpty()) return synced
            }
        }

        // 2. Search query
        val q = if (artist.isNotEmpty() && !artist.equals("未知演出者", true)) "$artist $title" else title
        val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(q, "UTF-8")}"
        val listJson = httpGet(searchUrl)
        if (listJson != null) {
            val list = org.json.JSONArray(listJson)
            val candidateList = mutableListOf<JSONObject>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val synced = item.optString("syncedLyrics")
                if (synced.isNotEmpty()) {
                    candidateList.add(item)
                }
            }
            if (durationSec > 0) {
                candidateList.sortBy { item ->
                    val dur = item.optLong("duration")
                    if (dur > 0) Math.abs(dur - durationSec) else 50L
                }
            }
            if (candidateList.isNotEmpty()) {
                return candidateList[0].optString("syncedLyrics")
            }
        }

        // 3. Title alone fallback
        if (artist.isNotEmpty()) {
            val titleOnlyUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(title, "UTF-8")}"
            val titleListJson = httpGet(titleOnlyUrl)
            if (titleListJson != null) {
                val list = org.json.JSONArray(titleListJson)
                val candidateList = mutableListOf<JSONObject>()
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val synced = item.optString("syncedLyrics")
                    val dur = item.optLong("duration")
                    if (synced.isNotEmpty()) {
                        if (durationSec > 0 && dur > 0 && Math.abs(dur - durationSec) <= 15) {
                            candidateList.add(item)
                        } else if (durationSec == 0L) {
                            candidateList.add(item)
                        }
                    }
                }
                if (durationSec > 0) {
                    candidateList.sortBy { item ->
                        val dur = item.optLong("duration")
                        if (dur > 0) Math.abs(dur - durationSec) else 50L
                    }
                }
                if (candidateList.isNotEmpty()) {
                    return candidateList[0].optString("syncedLyrics")
                }
            }
        }

        return null
    }

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 4000): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    return reader.readText()
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
        return null
    }
}
