package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MyTubeApplication
import com.example.data.model.AppSettings
import com.example.data.model.DownloadItem
import com.example.data.model.PlaylistEntity
import com.example.data.model.VideoEntity
import com.example.data.repository.StorageBreakdown
import com.example.data.repository.SyncResult
import com.example.service.VideoDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyTubeApplication
    private val repo = app.videoRepository
    private val settingsStore = app.settingsDataStore
    private val tgRepo = app.telegramSyncRepository

    val videos: StateFlow<List<VideoEntity>> = repo.allVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repo.allPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadItem>> = repo.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _storageInfo = MutableStateFlow<StorageBreakdown?>(null)
    val storageInfo: StateFlow<StorageBreakdown?> = _storageInfo.asStateFlow()

    private val _currentPlayingVideo = MutableStateFlow<VideoEntity?>(null)
    val currentPlayingVideo: StateFlow<VideoEntity?> = _currentPlayingVideo.asStateFlow()

    private val _currentQueue = MutableStateFlow<List<VideoEntity>>(emptyList())
    val currentQueue: StateFlow<List<VideoEntity>> = _currentQueue.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    init {
        refreshStorageInfo()
        // Check if app has auto-sync on open
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { s ->
                if (!s.isAppLockEnabled) {
                    _isUnlocked.value = true
                }
                if (s.autoSyncOnOpen && s.telegramBotToken.isNotBlank() && s.telegramChannelId.isNotBlank()) {
                    tgRepo.backupToTelegram(s.telegramBotToken, s.telegramChannelId)
                }
            }
        }
    }

    fun unlockApp() {
        _isUnlocked.value = true
    }

    fun refreshStorageInfo() {
        viewModelScope.launch {
            _storageInfo.value = repo.calculateCacheAndStorage()
        }
    }

    fun addVideo(
        url: String,
        customTitle: String?,
        targetPlaylist: String?,
        andPlay: Boolean,
        onComplete: (VideoEntity?, String?, String?) -> Unit
    ) {
        viewModelScope.launch {
            val cookie = settings.value.teraBoxCookie
            if (com.example.data.repository.TeraBoxResolver.isTeraBoxHost(url)) {
                val r = repo.addTeraBoxLink(url, cookie, targetPlaylist)
                val res = r.getOrNull()
                if (res == null) {
                    onComplete(null, r.exceptionOrNull()?.message, null)
                } else if (res.added.isEmpty()) {
                    onComplete(null, "নতুন ভিডিও নেই; ${res.alreadyHad}টি ভিডিও আগে থেকেই আছে (আপডেট হয়েছে)", null)
                } else {
                    val first = res.added.first()
                    if (andPlay) playVideo(first, res.added)
                    val info = buildString {
                        append("${res.added.size}টি ভিডিও যুক্ত হয়েছে")
                        if (res.playlistName != null) append(" (প্লেলিস্ট: ${res.playlistName})")
                        if (res.alreadyHad > 0) append(", ${res.alreadyHad}টির লিংক আপডেট হয়েছে")
                                        if (res.truncated) append("। ফোল্ডার অনেক বড়, প্রথম অংশ যুক্ত হয়েছে")
                    }
                    onComplete(first, null, info)
                }
                return@launch
            }
            val result = repo.addVideoFromUrl(url, customTitle, targetPlaylist, cookie)
            val video = result.getOrNull()
            if (video != null && andPlay) {
                playVideo(video, listOf(video))
            }
            onComplete(video, result.exceptionOrNull()?.message, null)
        }
    }

    fun toggleFavorite(videoId: String) {
        viewModelScope.launch {
            repo.toggleFavorite(videoId)
        }
    }

    fun updatePlaybackPosition(videoId: String, positionMs: Long) {
        viewModelScope.launch {
            repo.updatePlaybackPosition(videoId, positionMs)
        }
    }

    fun deleteVideo(video: VideoEntity) {
        viewModelScope.launch {
            if (_currentPlayingVideo.value?.id == video.id) {
                _currentPlayingVideo.value = null
            }
            repo.deleteVideo(video)
            refreshStorageInfo()
        }
    }

    private val _teraBoxStatus = MutableStateFlow<String?>(null)
    val teraBoxStatus: StateFlow<String?> = _teraBoxStatus

    fun clearTeraBoxStatus() {
        _teraBoxStatus.value = null
    }

    // videoId -> (playlist url, time); TeraBox links expire, so reuse only briefly
    private val playbackCache = HashMap<String, Pair<String, Long>>()

    fun playVideo(video: VideoEntity, queue: List<VideoEntity> = emptyList()) {
        val needsResolve = video.localFilePath == null &&
            video.originalUrl.contains("#fs=") &&
            com.example.data.repository.TeraBoxResolver.isTeraBoxHost(video.originalUrl)
        if (!needsResolve) {
            setPlaying(video, queue)
            return
        }
        viewModelScope.launch {
            val cached = playbackCache[video.id]
            if (cached != null && System.currentTimeMillis() - cached.second < 20 * 60_000L &&
                java.io.File(android.net.Uri.parse(cached.first).path ?: "").exists()
            ) {
                setPlaying(video.copy(streamUrl = cached.first), queue)
                return@launch
            }
            _teraBoxStatus.value = "TeraBox ভিডিও প্রস্তুত হচ্ছে…"
            val r = repo.resolveTeraBoxPlayback(video.originalUrl, settings.value.teraBoxCookie)
            val src = r.getOrNull()
            if (src != null) {
                playbackCache[video.id] = src.url to System.currentTimeMillis()
                setPlaying(video.copy(streamUrl = src.url), queue)
            } else {
                _teraBoxStatus.value = "ভিডিও চালু করা যায়নি: ${r.exceptionOrNull()?.message.orEmpty()}"
            }
        }
    }

    private fun setPlaying(video: VideoEntity, queue: List<VideoEntity>) {
        _currentPlayingVideo.value = video
        val base = if (queue.isNotEmpty()) queue else listOf(video)
        _currentQueue.value = base.map { if (it.id == video.id) video else it }
    }

    fun stopPlayer() {
        _currentPlayingVideo.value = null
    }

    // Playlists
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repo.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repo.deletePlaylist(playlistId)
        }
    }

    fun addToPlaylist(videoId: String, playlistName: String) {
        viewModelScope.launch {
            repo.addVideoToPlaylist(videoId, playlistName)
        }
    }

    fun removeFromPlaylist(videoId: String, playlistName: String) {
        viewModelScope.launch {
            repo.removeVideoFromPlaylist(videoId, playlistName)
        }
    }

    // Downloads
    fun startDownload(video: VideoEntity) {
        viewModelScope.launch {
            val toPrivate = settings.value.downloadToAppPrivate
            repo.enqueueDownload(video, toPrivate)
            VideoDownloadService.start(app)
            refreshStorageInfo()
        }
    }

    fun pauseDownload(id: String) {
        val intent = android.content.Intent(app, VideoDownloadService::class.java).apply {
            action = VideoDownloadService.ACTION_PAUSE
            putExtra(VideoDownloadService.EXTRA_DOWNLOAD_ID, id)
        }
        app.startService(intent)
    }

    fun resumeDownload(id: String) {
        viewModelScope.launch {
            app.database.downloadDao().updateStatus(id, com.example.data.model.DownloadStatus.QUEUED)
            VideoDownloadService.start(app)
        }
    }

    fun cancelDownload(id: String) {
        val intent = android.content.Intent(app, VideoDownloadService::class.java).apply {
            action = VideoDownloadService.ACTION_CANCEL
            putExtra(VideoDownloadService.EXTRA_DOWNLOAD_ID, id)
        }
        app.startService(intent)
        viewModelScope.launch {
            repo.cancelDownload(id)
            refreshStorageInfo()
        }
    }

    fun deleteDownload(id: String) {
        viewModelScope.launch {
            repo.cancelDownload(id)
            refreshStorageInfo()
        }
    }

    // Settings
    fun updateSpeed(speed: Float) {
        viewModelScope.launch {
            settingsStore.updatePlaybackSpeed(speed)
        }
    }

    fun updateAutoPlay(autoPlay: Boolean) {
        viewModelScope.launch {
            settingsStore.updateAutoPlay(autoPlay)
        }
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            settingsStore.setPin(pin)
            _isUnlocked.value = true
        }
    }

    fun disablePin() {
        viewModelScope.launch {
            settingsStore.disablePin()
            _isUnlocked.value = true
        }
    }

    fun updateTheme(isDark: Boolean, isAmoled: Boolean) {
        viewModelScope.launch {
            settingsStore.updateTheme(isDark, isAmoled)
        }
    }

    fun updateLayout(isGrid: Boolean) {
        viewModelScope.launch {
            settingsStore.updateLayout(isGrid)
        }
    }

    fun updateDownloadLocation(toAppPrivate: Boolean) {
        viewModelScope.launch {
            settingsStore.updateDownloadLocation(toAppPrivate)
        }
    }

    fun updateTelegramConfig(token: String, channel: String, autoSync: Boolean) {
        viewModelScope.launch {
            settingsStore.updateTelegramConfig(token, channel, autoSync)
        }
    }

    fun updateTeraBoxCookie(cookie: String) {
        viewModelScope.launch {
            settingsStore.updateTeraBoxCookie(cookie)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repo.clearCache()
            refreshStorageInfo()
        }
    }

    suspend fun exportJson(): String {
        return tgRepo.createExportJson()
    }

    suspend fun importJson(json: String): Result<Pair<Int, Int>> {
        val res = tgRepo.importFromJson(json)
        refreshStorageInfo()
        return res
    }

    suspend fun backupToTelegram(): SyncResult {
        val s = settings.value
        return tgRepo.backupToTelegram(s.telegramBotToken, s.telegramChannelId)
    }
}
