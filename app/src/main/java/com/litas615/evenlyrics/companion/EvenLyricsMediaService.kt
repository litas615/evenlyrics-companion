package com.litas615.evenlyrics.companion

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

class EvenLyricsMediaService : NotificationListenerService() {
    private val tag = "EvenLyricsMediaService"

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var localServer: LocalPlaybackServer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentPackage = ""
    private var currentDurationMs: Long? = null
    private var currentPositionMs = 0L
    private var isPlaying = false
    private var playbackSpeed = 1.0f
    private var currentLrc: String? = null
    private val lyricsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.i(tag, "Active sessions changed. Found ${controllers?.size ?: 0} sessions.")
        updateActiveController(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onSessionDestroyed() {
            Log.i(tag, "Session destroyed for $currentPackage")
            activeController = null
            // Re-scan active sessions
            mediaSessionManager?.let { mgr ->
                val component = ComponentName(this@EvenLyricsMediaService, EvenLyricsMediaService::class.java)
                updateActiveController(mgr.getActiveSessions(component))
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            scanActiveSessions()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(tag, "EvenLyricsMediaService created.")
        startLocalServer()
        handler.post(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        stopLocalServer()
        if (instance == this) instance = null
        Log.i(tag, "EvenLyricsMediaService destroyed.")
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val pkg = sbn?.packageName ?: return
        if (pkg.contains("music", ignoreCase = true) || pkg.contains("youtube", ignoreCase = true) || pkg.contains("spotify", ignoreCase = true)) {
            scanActiveSessions()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(tag, "NotificationListener connected.")
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = ComponentName(this, EvenLyricsMediaService::class.java)

        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            scanActiveSessions()
        } catch (e: Exception) {
            Log.e(tag, "Failed to register active sessions listener: ${e.message}", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(tag, "NotificationListener disconnected.")
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
    }

    private fun scanActiveSessions() {
        try {
            val mgr = mediaSessionManager ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
            mediaSessionManager = mgr
            val component = ComponentName(this, EvenLyricsMediaService::class.java)
            val controllers = mgr?.getActiveSessions(component)
            updateActiveController(controllers)
        } catch (e: Exception) {
            Log.w(tag, "scanActiveSessions error: ${e.message}")
        }
    }

    private fun startLocalServer() {
        if (localServer == null) {
            localServer = LocalPlaybackServer(port = 5288) { action, posMs ->
                handleCommand(action, posMs)
            }
            localServer?.start()
        }
    }

    private fun stopLocalServer() {
        localServer?.stop()
        localServer = null
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) return

        // Preferred music apps priority
        val preferred = listOf(
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "com.google.android.youtube",
            "com.apple.android.music",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic"
        )

        val blacklist = setOf(
            "com.d2nova.gc.fairlady1",
            "com.openai.chatgpt",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.talk"
        )

        val valid = controllers.filter { !blacklist.contains(it.packageName) }
        if (valid.isEmpty()) return

        val selected = valid.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && preferred.contains(it.packageName) }
            ?: valid.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: valid.firstOrNull { preferred.contains(it.packageName) && it.metadata != null }
            ?: valid.firstOrNull { preferred.contains(it.packageName) }
            ?: valid.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true }
            ?: valid.firstOrNull()

        if (selected != null) {
            val controllerChanged = selected != activeController
            if (controllerChanged) {
                activeController?.unregisterCallback(controllerCallback)
                activeController = selected
                currentPackage = selected.packageName
                selected.registerCallback(controllerCallback)
                Log.i(tag, "Selected active controller: $currentPackage")
            }
            updateMetadata(selected.metadata)
            updatePlaybackState(selected.playbackState)
        }
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return

        var rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.description?.title?.toString()
            ?: ""

        var rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.description?.subtitle?.toString()
            ?: ""

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0) {
            currentDurationMs = duration
        }

        val oldTitle = currentTitle
        val oldArtist = currentArtist

        // Clean & normalize metadata
        val normalized = normalizeMetadata(rawTitle, rawArtist)
        currentTitle = normalized.first
        currentArtist = normalized.second

        val songChanged = currentTitle != oldTitle || currentArtist != oldArtist
        if (songChanged && currentTitle.isNotEmpty()) {
            currentLrc = null
            fetchLyricsAsync(currentTitle, currentArtist, currentDurationMs)
        }

        Log.i(tag, "Metadata updated: $currentArtist - $currentTitle (${currentDurationMs ?: 0}ms)")
        pushState()
    }

    private fun fetchLyricsAsync(title: String, artist: String, durationMs: Long?) {
        lyricsExecutor.execute {
            try {
                val lrc = LyricsFetcher.fetch(title, artist, durationMs)
                if (lrc != null && currentTitle == title) {
                    currentLrc = lrc
                    Log.i(tag, "Fetched lyrics for $title, lines: ${lrc.split("\n").size}")
                    pushState()
                }
            } catch (e: Exception) {
                Log.w(tag, "Async lyrics fetch failed: ${e.message}")
            }
        }
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        if (state == null) return

        // Proactively detect metadata changes in case onMetadataChanged was missed
        activeController?.metadata?.let { meta ->
            val metaTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta.description?.title?.toString()
                ?: ""
            if (metaTitle.isNotEmpty() && (currentTitle.isEmpty() || !metaTitle.equals(currentTitle, ignoreCase = true))) {
                updateMetadata(meta)
            }
        }

        isPlaying = state.state == PlaybackState.STATE_PLAYING
        currentPositionMs = state.position
        playbackSpeed = if (state.playbackSpeed > 0) state.playbackSpeed else 1.0f

        Log.i(tag, "PlaybackState: isPlaying=$isPlaying, pos=$currentPositionMs ms, speed=$playbackSpeed")
        pushState()
    }

    private fun pushState() {
        if (currentTitle.isEmpty()) return

        localServer?.broadcastState(
            packageName = currentPackage,
            title = currentTitle,
            artist = currentArtist,
            durationMs = currentDurationMs,
            positionMs = currentPositionMs,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            lrc = currentLrc
        )

        // Notify UI update
        onStateChangedListener?.invoke(
            CurrentMusicInfo(
                packageName = currentPackage,
                title = currentTitle,
                artist = currentArtist,
                durationMs = currentDurationMs,
                positionMs = currentPositionMs,
                isPlaying = isPlaying
            )
        )
    }

    private fun handleCommand(action: String, positionMs: Long?) {
        handler.post {
            val controls = activeController?.transportControls ?: return@post
            Log.i(tag, "Executing command: $action (pos=$positionMs)")
            when (action) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> {
                    if (isPlaying) controls.pause() else controls.play()
                }
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
                "seek" -> {
                    if (positionMs != null && positionMs >= 0) {
                        controls.seekTo(positionMs)
                    }
                }
            }
        }
    }

    private fun normalizeMetadata(rawTitle: String, rawArtist: String): Pair<String, String> {
        var title = rawTitle.trim()
        var artist = rawArtist.trim()

        // Strip noise tags like (Official MV), [4K], etc.
        val noisePattern = "(?i)\\((?:official\\s*)?(?:music\\s*video|video|mv|audio|lyrics?|4k|hd|1080p|中字|完整版|動態歌詞)[^)]*\\)|\\[(?:official\\s*)?(?:music\\s*video|video|mv|audio|lyrics?|4k|hd|1080p|中字|完整版|動態歌詞)[^\\]]*\\]|【(?:official\\s*)?(?:music\\s*video|video|mv|audio|lyrics?|4k|hd|1080p|中字|完整版|動態歌詞)[^】]*】".toRegex()
        title = title.replace(noisePattern, " ").replace("\\s+".toRegex(), " ").trim()

        // Handle quotes like 『我們是對方 特別的人』 (excerpt subtitle) vs 『特別的人』 (title in quotes)
        val quoteMatch = "^(.*?)[『「]([^』」]+)[』」](.*)$".toRegex().find(title)
        if (quoteMatch != null) {
            val before = quoteMatch.groupValues[1].trim()
            val inside = quoteMatch.groupValues[2].trim()
            val after = quoteMatch.groupValues[3].trim()
            val dashMatch = "^(.+?)\\s*[-—–]\\s*(.+)$".toRegex().find(before)
            title = when {
                dashMatch != null && dashMatch.groupValues[2].trim().isNotEmpty() -> "$before $after"
                before.endsWith("-") || before.endsWith("–") || before.endsWith("—") -> "$before $inside $after"
                before.isEmpty() -> "$inside $after"
                else -> "$before $after"
            }
        } else {
            title = title.replace("[『』「」《》“”\"']".toRegex(), " ")
        }
        title = title.replace("\\s+".toRegex(), " ").trim()
        artist = artist.replace("[『』「」《》“”\"']".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()

        // Handle "Artist - Title" format
        val dashMatch = "^(.+?)\\s*[-—–]\\s*(.+)$".toRegex().find(title)
        if (dashMatch != null) {
            val pArtist = dashMatch.groupValues[1].trim()
            val pTitle = dashMatch.groupValues[2].trim()

            val isChannel = artist.isEmpty() ||
                    artist.contains("channel", ignoreCase = true) ||
                    artist.contains("vevo", ignoreCase = true) ||
                    artist.contains("topic", ignoreCase = true) ||
                    artist.contains("records", ignoreCase = true) ||
                    artist.contains("官方頻道", ignoreCase = true) ||
                    artist == "未知演出者"

            if (isChannel) {
                artist = pArtist
                title = pTitle
            } else if (artist.equals(pArtist, ignoreCase = true)) {
                title = pTitle
            }
        }

        title = title.replace("[【】\\[\\]()]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()

        return Pair(title.ifEmpty { rawTitle }, artist)
    }

    fun injectMockTrack(mockTitle: String, mockArtist: String, mockDurationMs: Long, mockLrc: String) {
        currentTitle = mockTitle
        currentArtist = mockArtist
        currentPackage = "com.google.android.apps.youtube.music"
        currentDurationMs = mockDurationMs
        currentPositionMs = 2000L
        isPlaying = true
        playbackSpeed = 1.0f
        currentLrc = mockLrc
        pushState()
        onStateChangedListener?.invoke(
            CurrentMusicInfo(
                packageName = currentPackage,
                title = currentTitle,
                artist = currentArtist,
                durationMs = currentDurationMs,
                positionMs = currentPositionMs,
                isPlaying = isPlaying
            )
        )
    }

    data class CurrentMusicInfo(
        val packageName: String,
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val positionMs: Long,
        val isPlaying: Boolean
    )

    companion object {
        var instance: EvenLyricsMediaService? = null
        var onStateChangedListener: ((CurrentMusicInfo) -> Unit)? = null
    }
}
