/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("DEPRECATION")

package com.metrolist.music.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.lastfm.LastFM
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoTargetPlaylistKey
import com.metrolist.music.constants.AudioFocusEnabledKey
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AudioOffload
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.EnableQobuzKey
import com.metrolist.music.constants.QobuzAudioQuality
import com.metrolist.music.constants.QobuzAudioQualityKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.QobuzJumoEndpointKey
import com.metrolist.music.constants.QobuzKennyEndpointKey
import com.metrolist.music.constants.QobuzMatchOverridesKey
import com.metrolist.music.constants.QobuzSquidEndpointKey
import com.metrolist.music.constants.QobuzTryptEndpointKey
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.resolver.AudioDiagnostics
import com.metrolist.music.resolver.AudioFallbackEngine
import com.metrolist.music.resolver.AudioProviderId
import com.metrolist.music.resolver.FallbackIds
import com.metrolist.music.resolver.ResolverPreferences
import com.metrolist.music.qobuz.QobuzMatchOverride
import com.metrolist.music.qobuz.QobuzMatchOverrides
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.AutoLoadMoreKey
import com.metrolist.music.constants.AutoSkipNextOnErrorKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.CrossfadeGaplessKey
import com.metrolist.music.constants.DisableLoadMoreWhenRepeatAllKey
import com.metrolist.music.constants.DiscordActivityNameKey
import com.metrolist.music.constants.DiscordActivityTypeKey
import com.metrolist.music.constants.DiscordAdvancedModeKey
import com.metrolist.music.constants.DiscordAvatarKey
import com.metrolist.music.constants.DiscordButton1TextKey
import com.metrolist.music.constants.DiscordButton1UrlKey
import com.metrolist.music.constants.DiscordButton1VisibleKey
import com.metrolist.music.constants.DiscordButton2TextKey
import com.metrolist.music.constants.DiscordButton2UrlKey
import com.metrolist.music.constants.DiscordButton2VisibleKey
import com.metrolist.music.constants.DiscordStatusKey
import com.metrolist.music.constants.DiscordTokenKey
import com.metrolist.music.constants.DiscordUseDetailsKey
import com.metrolist.music.constants.EnableDiscordRPCKey
import com.metrolist.music.constants.EnableLastFMScrobblingKey
import com.metrolist.music.constants.EnableSongCacheKey
import com.metrolist.music.constants.PreCacheOnlyWifiKey
import com.metrolist.music.constants.PreCacheTracksKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.constants.LastFMUseNowPlaying
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.MediaSessionConstants.CommandAddToTargetPlaylist
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleLike
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleStartRadio
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.PauseOnMute
import com.metrolist.music.constants.SPONSORBLOCK_DEFAULT_CATEGORIES
import com.metrolist.music.constants.SponsorBlockCategoriesKey
import com.metrolist.music.constants.SponsorBlockEnabledKey
import com.metrolist.music.constants.SponsorBlockShowToastKey
import com.metrolist.music.constants.PersistentQueueKey
import com.metrolist.music.constants.PersistentShuffleAcrossQueuesKey
import com.metrolist.music.constants.PlayerVolumeKey
import com.metrolist.music.constants.PreventDuplicateTracksInQueueKey
import com.metrolist.music.constants.RememberShuffleAndRepeatKey
import com.metrolist.music.constants.RepeatModeKey
import com.metrolist.music.constants.ResumeOnBluetoothConnectKey
import com.metrolist.music.constants.ScrobbleDelayPercentKey
import com.metrolist.music.constants.ScrobbleDelaySecondsKey
import com.metrolist.music.constants.ScrobbleMinSongDurationKey
import com.metrolist.music.constants.ShowLyricsKey
import com.metrolist.music.constants.ShuffleModeKey
import com.metrolist.music.constants.ShufflePlaylistFirstKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.constants.SimilarContent
import com.metrolist.music.constants.SkipSilenceInstantKey
import com.metrolist.music.constants.SkipSilenceKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.QobuzMatchEntity
import com.metrolist.music.db.entities.RelatedSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.eq.EqualizerService
import com.metrolist.music.eq.audio.CustomEqualizerAudioProcessor
import com.metrolist.music.eq.data.EQProfileRepository
import com.metrolist.music.extensions.SilentHandler
import com.metrolist.music.extensions.tryOrNull
import com.metrolist.music.extensions.collect
import com.metrolist.music.extensions.collectLatest
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.findNextMediaItemById
import com.metrolist.music.extensions.mediaItems
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.setOffloadEnabled
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toPersistQueue
import com.metrolist.music.extensions.toQueue
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.PersistPlayerState
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.alarm.MusicAlarmScheduler
import com.metrolist.music.playback.alarm.MusicAlarmStore
import com.metrolist.music.playback.audio.SilenceDetectorAudioProcessor
import com.metrolist.music.playback.datasource.HlsConcatDataSource
import com.metrolist.music.playback.queues.EmptyQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.queues.filterExplicit
import com.metrolist.music.playback.queues.filterVideoSongs
import com.metrolist.music.utils.CoilBitmapLoader
import com.metrolist.music.utils.DiscordRPC
import com.metrolist.music.utils.InternetProbe
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.ScrobbleManager
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.Fix403
import com.metrolist.music.utils.YTPlayerUtils
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.widget.MetrolistWidgetManager
import com.metrolist.music.widget.MusicWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val INSTANT_SILENCE_SKIP_STEP_MS = 15_000L
private const val INSTANT_SILENCE_SKIP_SETTLE_MS = 350L

/** When the queue has this many or fewer items (or items ahead of current), load more from paginated queues (e.g. Spotify). */
private const val QUEUE_PRELOAD_AHEAD_THRESHOLD = 20
// Hard cap on shuffle preload to prevent runaway queue growth on continuous
// recommendation feeds (e.g. Quick Picks radio) where hasNextPage() never
// returns false. Issue #139.
private const val SHUFFLE_PRELOAD_MAX_ITEMS = 500

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var equalizerService: EqualizerService

    @Inject
    lateinit var eqProfileRepository: EQProfileRepository

    @Inject
    lateinit var widgetManager: MetrolistWidgetManager

    @Inject
    lateinit var listenTogetherManager: com.metrolist.music.listentogether.ListenTogetherManager

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var hasAudioFocus = false
    @Volatile private var audioFocusEnabled = true
    private var reentrantFocusGain = false
    private var wasPlayingBeforeVolumeMute = false
    private var isPausedByVolumeMute = false

    private var crossfadeEnabled = false
    private var crossfadeDuration = 5000f
    private var crossfadeGapless = true
    private var crossfadeTriggerJob: Job? = null

    // SponsorBlock: per-track job that fetches skip segments and polls playback
    // position to seek past them. Cancelled and restarted on every track change.
    private var sponsorBlockJob: Job? = null

    private val secondaryPlayerListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).e(error, "Secondary player error")
                secondaryPlayer?.stop()
                secondaryPlayer?.clearMediaItems()
                secondaryPlayer = null
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private lateinit var audioQuality: com.metrolist.music.constants.AudioQuality

    private var currentQueue: Queue = EmptyQueue

    /** The in-flight initial load of [currentQueue]; cancelled when a new queue is started. */
    private var queueLoadJob: kotlinx.coroutines.Job? = null
    var queueTitle: String? = null

    val currentMediaMetadata = MutableStateFlow<com.metrolist.music.models.MediaMetadata?>(null)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    lateinit var playerVolume: MutableStateFlow<Float>
    val isMuted = MutableStateFlow(false)
    private val sleepTimerVolumeMultiplier = MutableStateFlow(1f)
    private val audioFocusVolumeMultiplier = MutableStateFlow(1f)

    fun toggleMute() {
        val newMutedState = !isMuted.value
        isMuted.value = newMutedState
        applyEffectiveVolume()
    }

    fun setMuted(muted: Boolean) {
        isMuted.value = muted
        applyEffectiveVolume()
    }

    private fun calculateEffectiveVolume(
        volume: Float = playerVolume.value,
        muted: Boolean = isMuted.value,
        sleepTimerMultiplier: Float = sleepTimerVolumeMultiplier.value,
        focusMultiplier: Float = audioFocusVolumeMultiplier.value,
    ): Float {
        if (muted) return 0f
        return (volume * sleepTimerMultiplier * focusMultiplier).coerceIn(0f, 1f)
    }

    private fun applyEffectiveVolume() {
        if (!::player.isInitialized || isCrossfading) return
        player.volume = calculateEffectiveVolume()
    }

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
        private set
    private var secondaryPlayer: ExoPlayer? = null
    private var fadingPlayer: ExoPlayer? = null
    private var isCrossfading = false
    private var crossfadeJob: Job? = null

    private lateinit var mediaSession: MediaLibrarySession

    // Tracks if player has been properly initilized
    private val playerInitialized = MutableStateFlow(false)
    val isPlayerReady: kotlinx.coroutines.flow.StateFlow<Boolean> = playerInitialized.asStateFlow()

    // Expose active player flow for UI/Connection updates
    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    private val playerSilenceProcessors = HashMap<Player, SilenceDetectorAudioProcessor>()

    private val instantSilenceSkipEnabled = MutableStateFlow(false)

    private var isAudioEffectSessionOpened = false
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var discordRpc: DiscordRPC? = null
    private var lastPlaybackSpeed = 1.0f
    private var discordUpdateJob: kotlinx.coroutines.Job? = null

    private var scrobbleManager: ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    // Tracks the original queue size to distinguish original items from auto-added ones
    private var originalQueueSize: Int = 0

    private var consecutivePlaybackErr = 0
    private var retryJob: Job? = null
    private var retryCount = 0
    private var silenceSkipJob: Job? = null
    private var preCacheJob: Job? = null

    // URL cache for stream URLs - class-level so it can be invalidated on errors
    private val songUrlCache = HashMap<String, Pair<String, Long>>()

    // Flag to bypass cache when quality changes - forces fresh stream fetch
    private val bypassCacheForQualityChange = mutableSetOf<String>()

    // In-memory negative cache for Qobuz: tracks that recently failed to resolve.
    // Without this, every playback of a YT-native track without a Qobuz match
    // re-runs the full search × backend × quality cascade (up to ~60s on the
    // ExoPlayer loader thread before falling back to YouTube). Map value = epoch
    // ms when the entry becomes invalid; we re-try Qobuz after the TTL or when
    // the user clears the override / changes match data.
    private val qobuzMissUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val QOBUZ_MISS_TTL_MS = 24 * 60 * 60 * 1000L

    // Enhanced error tracking for strict retry management
    private var currentMediaIdRetryCount = mutableMapOf<String, Int>()
    private val MAX_RETRY_PER_SONG = 3
    private val RETRY_DELAY_MS = 1000L

    // Track failed songs to prevent infinite retry loops
    private val recentlyFailedSongs = mutableSetOf<String>()
    private var failedSongsClearJob: Job? = null

    // Google Cast support
    var castConnectionHandler: CastConnectionHandler? = null
        private set

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (playerInitialized.value && !player.isPlaying) {
                            scope.launch(Dispatchers.IO) {
                                discordRpc?.closeRPC()
                            }
                        }
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        if (playerInitialized.value && player.isPlaying) {
                            scope.launch {
                                currentSong.value?.let { song ->
                                    updateDiscordRPC(song)
                                }
                            }
                        }
                    }
                }
            }
        }

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                super.onAudioDevicesAdded(addedDevices)
                val hasBluetooth =
                    addedDevices?.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    } == true

                if (hasBluetooth) {
                    if (dataStore.get(ResumeOnBluetoothConnectKey, false)) {
                        if (player.playbackState == Player.STATE_READY && !player.isPlaying) {
                            player.play()
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Player rediness reset to false
        playerInitialized.value = false

        // 3. Connect the processor to the service
        // handled in createExoPlayer

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.music_player),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification: Notification =
                NotificationCompat
                    .Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText("")
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentIntent(pending)
                    .setOngoing(true)
                    .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Timber.tag(TAG).w("Foreground service start not allowed (likely app in background)")
            } else {
                Timber.tag(TAG).e(e, "Failed to create foreground notification")
                reportException(e)
            }
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player,
            ).apply {
                setSmallIcon(R.drawable.small_icon)
            },
        )
        player = createExoPlayer()
        player.addListener(this@MusicService)
        sleepTimer =
            SleepTimer(scope, player) { multiplier ->
                sleepTimerVolumeMultiplier.value = multiplier
            }
        player.addListener(sleepTimer)

        // Mark player as initialized after successful creation
        playerInitialized.value = true
        Timber.tag(TAG).d("Player successfully initialized")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocusRequest()

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
            addToTargetPlaylist = ::addToTargetPlaylist
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Restore shuffle mode if remember option is enabled
        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            player.shuffleModeEnabled = dataStore.get(ShuffleModeKey, false)
        }

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        val screenStateFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        registerReceiver(screenStateReceiver, screenStateFilter)

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        audioQuality = dataStore.get(AudioQualityKey).toEnum(com.metrolist.music.constants.AudioQuality.HIGH)
        playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

        // Initialize Google Cast
        initializeCast()

        // Update lyrics provider order preference
        // Collecting this flow activates the internal map that updates lyricsProviders in LyricsHelper
        lyricsHelper.preferred.collectLatest(scope) {}

        // 4. Watch for EQ profile changes
        scope.launch {
            eqProfileRepository.activeProfile.collect { profile ->
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    if (result.isSuccess && player.playbackState == Player.STATE_READY && player.isPlaying) {
                        // Instant update: flush buffers and seek slightly to re-process audio
                        // Small seek to force re-buffer through the new EQ settings
                        // Seek to current position effectively resets the pipeline
                        player.seekTo(player.currentPosition)
                    }
                } else {
                    equalizerService.disable()
                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        player.seekTo(player.currentPosition)
                    }
                }
            }
        }

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    forgetFailuresForOutage(player.currentMediaItem?.mediaId)
                    triggerRetry()
                }
                // Update Discord RPC when network becomes available
                if (isConnected && discordRpc != null && player.isPlaying) {
                    val mediaId = player.currentMetadata?.id
                    if (mediaId != null) {
                        database.song(mediaId).first()?.let { song ->
                            updateDiscordRPC(song)
                        }
                    }
                }
            }
        }

        // Watch for audio quality setting changes
        var isFirstQualityEmit = true
        scope.launch {
            dataStore.data
                .map {
                    it[AudioQualityKey]?.let { value ->
                        com.metrolist.music.constants.AudioQuality.entries
                            .find { it.name == value }
                    } ?: com.metrolist.music.constants.AudioQuality.HIGH
                }.distinctUntilChanged()
                .collect { newQuality ->
                    val oldQuality = audioQuality
                    audioQuality = newQuality

                    // Skip reload on first emit (app startup)
                    if (isFirstQualityEmit) {
                        isFirstQualityEmit = false
                        Timber.tag("MusicService").i("QUALITY INIT: $newQuality")
                        return@collect
                    }

                    Timber.tag("MusicService").i("QUALITY CHANGED: $oldQuality -> $newQuality")

                    // Reload current song with new quality
                    val mediaId = player.currentMediaItem?.mediaId ?: return@collect
                    val currentPosition = player.currentPosition
                    val wasPlaying = player.isPlaying
                    val currentIndex = player.currentMediaItemIndex

                    Timber.tag("MusicService").i("RELOADING STREAM: $mediaId at position ${currentPosition}ms")

                    // Clear cached URL to force fresh fetch
                    songUrlCache.remove(mediaId)

                    // Clear caches before reload so the new quality isn't served a stale
                    // byte-range. Using withContext(IO) instead of runBlocking keeps this
                    // sequential with the reload below, without blocking the main thread
                    // (this collector is suspending, we can safely suspend here).
                    withContext(Dispatchers.IO) {
                        try {
                            playerCache.removeResource(mediaId)
                            downloadCache.removeResource(mediaId)
                            Timber.tag("MusicService").d("Cleared player and download cache for $mediaId")
                        } catch (e: Exception) {
                            Timber.tag("MusicService").e(e, "Failed to clear cache for $mediaId")
                        }
                    }

                    // Set bypass flag so resolver skips cache checks
                    bypassCacheForQualityChange.add(mediaId)
                    Timber.tag("MusicService").d("Set bypass cache flag for $mediaId")

                    // Reload player at same position
                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
        }

        // Watch for Qobuz source-selection changes. Toggling on/off, switching
        // backend, country, or quality should take effect on the next track
        // boundary without requiring a full app restart — reload the current
        // item so it picks up the new source immediately.
        // Apply any user-configured endpoint overrides at startup so the first
        // resolve already uses them.
        applyQobuzEndpointOverrides()

        var isFirstQobuzEmit = true
        scope.launch {
            dataStore.data
                .map {
                    listOf(
                        it[EnableQobuzKey]?.toString().orEmpty(),
                        it[QobuzAudioQualityKey].orEmpty(),
                        it[QobuzBackendKey].orEmpty(),
                        it[QobuzCountryKey].orEmpty(),
                        it[QobuzSquidEndpointKey].orEmpty(),
                        it[QobuzKennyEndpointKey].orEmpty(),
                        it[QobuzTryptEndpointKey].orEmpty(),
                        it[QobuzJumoEndpointKey].orEmpty(),
                    ).joinToString("|")
                }.distinctUntilChanged()
                .collect {
                    if (isFirstQobuzEmit) {
                        isFirstQobuzEmit = false
                        return@collect
                    }
                    // A settings change may have edited an endpoint — re-apply
                    // before reloading the current stream.
                    applyQobuzEndpointOverrides()
                    val mediaId = player.currentMediaItem?.mediaId ?: return@collect
                    val currentPosition = player.currentPosition
                    val wasPlaying = player.isPlaying
                    val currentIndex = player.currentMediaItemIndex

                    Timber.tag("MusicService").i(
                        "QOBUZ SETTING CHANGED, reloading current stream for $mediaId",
                    )

                    songUrlCache.remove(mediaId)
                    // Toggling Qobuz settings is an explicit user retry signal —
                    // wipe the negative cache so previously-missed tracks get a
                    // fresh resolve attempt instead of silently falling through
                    // to YouTube again.
                    qobuzMissUntilMs.clear()
                    withContext(Dispatchers.IO) {
                        try {
                            playerCache.removeResource(mediaId)
                            downloadCache.removeResource(mediaId)
                        } catch (e: Exception) {
                            Timber.tag("MusicService").e(e, "Failed to clear cache on Qobuz toggle for $mediaId")
                        }
                    }
                    bypassCacheForQualityChange.add(mediaId)

                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
        }

        combine(
            playerVolume,
            isMuted,
            sleepTimerVolumeMultiplier,
            audioFocusVolumeMultiplier,
        ) { volume, muted, timerMultiplier, focusMultiplier ->
            calculateEffectiveVolume(
                volume = volume,
                muted = muted,
                sleepTimerMultiplier = timerMultiplier,
                focusMultiplier = focusMultiplier,
            )
        }.collectLatest(scope) {
            if (!isCrossfading) {
                player.volume = it
            }
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            updateWidgetUI(player.isPlaying)
        }

        // The widget and notification can't query the database on the main thread, and with the
        // app closed nobody else loads the account's likes: do both here when the track changes.
        currentMediaMetadata
            .map { it?.id }
            .distinctUntilChanged()
            .collect(scope) { mediaId ->
                if (mediaId == null) return@collect
                val catalogId = withContext(Dispatchers.IO) { catalogTrackIdOf(mediaId) }
                currentCatalogId = mediaId to catalogId
                if (catalogId != null) {
                    withContext(Dispatchers.IO) { runCatching { SpotifyLikeCache.ensureLoaded(listOf(catalogId)) } }
                }
                updateNotification()
                updateWidgetUI(player.isPlaying)
            }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database
                    .lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyricsWithProvider.lyrics,
                            provider = lyricsWithProvider.provider,
                        ),
                    )
                }
            }
        }

        dataStore.data
            .map { (it[SkipSilenceKey] ?: false) to (it[SkipSilenceInstantKey] ?: false) }
            .distinctUntilChanged()
            .collectLatest(scope) { (skipSilence, instantSkip) ->
                player.skipSilenceEnabled = skipSilence
                secondaryPlayer?.skipSilenceEnabled = skipSilence

                val enableInstant = skipSilence && instantSkip
                instantSilenceSkipEnabled.value = enableInstant

                playerSilenceProcessors.values.forEach { processor ->
                    processor.instantModeEnabled = enableInstant
                    if (!enableInstant) {
                        processor.resetTracking()
                    }
                }

                if (!enableInstant) {
                    silenceSkipJob?.cancel()
                }
            }

        dataStore.data
            .map { it[AudioFocusEnabledKey] ?: true }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                audioFocusEnabled = enabled
                if (!enabled) {
                    // User opted to keep playing alongside other audio: give up focus so the
                    // system stops asking us to pause/duck, and mark ourselves free to play.
                    abandonAudioFocus()
                    hasAudioFocus = true
                    audioFocusVolumeMultiplier.value = 1f
                    applyEffectiveVolume()
                } else {
                    // Restore normal focus management; reacquire now if we're actively playing.
                    hasAudioFocus = false
                    if (player.playWhenReady &&
                        (player.playbackState == Player.STATE_READY ||
                            player.playbackState == Player.STATE_BUFFERING)
                    ) {
                        requestAudioFocus()
                    }
                }
            }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) -> setupLoudnessEnhancer() }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
        ) { offloadPref, crossfadeEnabled ->
            // Force disable offload if crossfade is enabled to prevent volume ramp issues
            if (crossfadeEnabled) false else offloadPref
        }.distinctUntilChanged()
            .collectLatest(scope) { useOffload ->
                player.setOffloadEnabled(useOffload)
                secondaryPlayer?.setOffloadEnabled(useOffload)
            }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (key, enabled) ->
                if (discordRpc?.isRpcRunning() == true) {
                    discordRpc?.closeRPC()
                }
                discordRpc = null
                if (key != null && enabled) {
                    discordRpc = DiscordRPC(this, key)
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            updateDiscordRPC(it, true)
                        }
                    }
                }
            }

        // Watch all Discord customization preferences
        dataStore.data
            .map {
                listOf(
                    it[DiscordUseDetailsKey],
                    it[DiscordAdvancedModeKey],
                    it[DiscordStatusKey],
                    it[DiscordButton1TextKey],
                    it[DiscordButton1VisibleKey],
                    it[DiscordButton2TextKey],
                    it[DiscordButton2VisibleKey],
                    it[DiscordActivityTypeKey],
                    it[DiscordActivityNameKey],
                )
            }.debounce(300)
            .distinctUntilChanged()
            .collect(scope) {
                if (player.playbackState == Player.STATE_READY) {
                    currentSong.value?.let { song ->
                        updateDiscordRPC(song, true)
                    }
                }
            }

        dataStore.data
            .map { false } // Last.fm integration removed: scrobbling stays off
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration =
                        dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    val manager =
                        ScrobbleManager(
                            scope,
                            minSongDuration = minSongDuration,
                            scrobbleDelayPercent = delayPercent,
                            scrobbleDelaySeconds = delaySeconds,
                        )
                    scrobbleManager = manager
                    manager.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                    if (::player.isInitialized && player.isPlaying) {
                        manager.onSongStart(player.currentMetadata, duration = player.duration)
                    }
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        combine(
            dataStore.data.map { prefs ->
                Triple(
                    prefs[CrossfadeEnabledKey] ?: false,
                    prefs[CrossfadeDurationKey] ?: 5f,
                    prefs[CrossfadeGaplessKey] ?: true,
                )
            },
            listenTogetherManager.roomState,
        ) { (enabled, duration, gapless), roomState ->
            // Disable crossfade if user is in a listen together room
            Triple(enabled && roomState == null, duration, gapless)
        }.distinctUntilChanged()
            .collect(scope) { (enabled, duration, gapless) ->
                crossfadeEnabled = enabled
                crossfadeDuration = duration * 1000f // Convert to ms
                crossfadeGapless = gapless
            }

        if (dataStore.get(PersistentQueueKey, true)) {
            val queueFile = filesDir.resolve(PERSISTENT_QUEUE_FILE)
            if (queueFile.exists()) {
                runCatching {
                    queueFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        // Convert back to proper queue type
                        val restoredQueue = queue.toQueue()
                        // Wait for player initialization before playing
                        scope.launch {
                            playerInitialized.first { it }
                            if (isActive) {
                                playQueue(
                                    queue = restoredQueue,
                                    playWhenReady = false,
                                )
                            }
                        }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore persisted queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read persisted queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            val automixFile = filesDir.resolve(PERSISTENT_AUTOMIX_FILE)
            if (automixFile.exists()) {
                runCatching {
                    automixFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        automixItems.value = queue.items.map { it.toMediaItem() }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore automix queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read automix queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            // Restore player state
            val playerStateFile = filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE)
            if (playerStateFile.exists()) {
                runCatching {
                    playerStateFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistPlayerState
                        }
                    }
                }.onSuccess { playerState ->
                    // Restore player settings after queue is loaded
                    scope.launch {
                        delay(1000) // Wait for queue to be loaded
                        // Don't restore repeat/shuffle from playerState as they are already set from DataStore (source of truth)
                        // player.repeatMode = playerState.repeatMode
                        // player.shuffleModeEnabled = playerState.shuffleModeEnabled
                        playerVolume.value = playerState.volume

                        // Restore position if it's still valid
                        if (playerState.currentMediaItemIndex < player.mediaItemCount) {
                            player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
                        }
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read player state, clearing data")
                    clearPersistedQueueFiles()
                }
            }
        }

        // Single background consumer for widget renders; see updateWidgetUI
        startWidgetRenderer()

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(15.seconds)
                if (dataStore.get(PersistentQueueKey, true)) {
                    saveQueueToDisk()
                }
                // Also save episode position periodically
                val currentMetadata = player.currentMediaItem?.metadata
                if (currentMetadata?.isEpisode == true && player.isPlaying && player.currentPosition > 0) {
                    previousEpisodePosition = player.currentPosition
                    saveEpisodePosition(currentMetadata.id, player.currentPosition)
                }
            }
        }

        // Save queue more frequently when playing to ensure state is preserved
        scope.launch {
            while (isActive) {
                delay(10.seconds)
                if (dataStore.get(PersistentQueueKey, true) && player.isPlaying) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun createExoPlayer(): ExoPlayer {
        val eqProcessor = CustomEqualizerAudioProcessor()
        equalizerService.addAudioProcessor(eqProcessor)

        val silenceProcessor = SilenceDetectorAudioProcessor { handleLongSilenceDetected() }
        com.metrolist.music.playback.audio.StereoBalance.value =
            dataStore.get(com.metrolist.music.constants.StereoBalanceKey, 0f)

        // Set initial state. `dataStore.get(key, default)` is already synchronous (it
        // reads the in-memory snapshot); wrapping it in runBlocking only spun up a
        // coroutine event loop on the main thread for nothing.
        val skipSilence = dataStore.get(SkipSilenceKey, false)
        val instantSkip = dataStore.get(SkipSilenceInstantKey, false)
        silenceProcessor.instantModeEnabled = skipSilence && instantSkip

        val player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory(eqProcessor, silenceProcessor))
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()

        playerSilenceProcessors[player] = silenceProcessor

        player.apply {
            val offload = dataStore.get(AudioOffload, false)
            val crossfade = dataStore.get(CrossfadeEnabledKey, false)
            setOffloadEnabled(if (crossfade) false else offload)
            skipSilenceEnabled = skipSilence
            addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

            // Cleanup handled manually in onDestroy/release
        }
        _playerFlow.value = player
        return player
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }.setAcceptsDelayedFocusGain(true)
                .build()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        // When focus handling is disabled we never own focus, so ignore stray callbacks.
        if (!audioFocusEnabled) return
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            -> {
                hasAudioFocus = true
                audioFocusVolumeMultiplier.value = 1f

                if (wasPlayingBeforeAudioFocusLoss && !player.isPlaying && !reentrantFocusGain) {
                    reentrantFocusGain = true
                    scope.launch {
                        delay(300)
                        if (hasAudioFocus && wasPlayingBeforeAudioFocusLoss && !player.isPlaying) {
                            // Don't start local playback if casting
                            if (castConnectionHandler?.isCasting?.value != true) {
                                player.play()
                            }
                            wasPlayingBeforeAudioFocusLoss = false
                        }
                        reentrantFocusGain = false
                    }
                }

                applyEffectiveVolume()
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 1f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                abandonAudioFocus()
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 1f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // We still HOLD audio focus while ducking — the system only asks us to lower our
                // volume, not to give up focus. Keeping hasAudioFocus = true prevents the player
                // event handler from re-requesting AUDIOFOCUS_GAIN, which caused a focus ping-pong
                // with the foreground app and snapped our volume from 0.2 back to 1.0 (issue #116).
                hasAudioFocus = true
                audioFocusVolumeMultiplier.value = 0.2f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    applyEffectiveVolume()
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                audioFocusVolumeMultiplier.value = 1f
                applyEffectiveVolume()
                lastAudioFocusState = focusChange
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (!audioFocusEnabled) {
            // Never grab focus: allow playback to run mixed with other apps' audio.
            hasAudioFocus = true
            return true
        }
        if (hasAudioFocus) return true

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    private fun clearPersistedQueueFiles() {
        runCatching { filesDir.resolve(PERSISTENT_QUEUE_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_AUTOMIX_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).delete() }
    }

    fun hasAudioFocusForPlayback(): Boolean = hasAudioFocus

    /**
     * The track could not load for lack of a working connection. Waits for as long as it takes —
     * a metro ride can be minutes — and starts the track the moment the internet answers again.
     * Android's "network available" can't be relied on for that: underground the phone keeps a
     * mobile network that carries nothing, so no "available" event ever comes; the internet itself
     * is asked instead, often at first and less often as the outage drags on.
     */
    private fun waitOnNetworkError() {
        if (waitingForNetworkConnection.value) return
        waitingForNetworkConnection.value = true

        retryJob?.cancel()
        retryJob =
            scope.launch {
                var attempt = 0
                while (isActive && waitingForNetworkConnection.value) {
                    delay(NETWORK_RECHECK_DELAYS_MS[attempt.coerceAtMost(NETWORK_RECHECK_DELAYS_MS.lastIndex)])
                    attempt++
                    if (InternetProbe.isOnline()) {
                        Timber.tag(TAG).d("Internet is back after %d check(s), retrying the track", attempt)
                        retryCount++
                        forgetFailuresForOutage(player.currentMediaItem?.mediaId)
                        triggerRetry()
                        break
                    }
                }
            }
    }

    private fun triggerRetry() {
        waitingForNetworkConnection.value = false
        retryJob?.cancel()

        if (player.currentMediaItem != null) {
            // After 3+ failed retries, try to refresh the stream URL by seeking to current position
            // This forces ExoPlayer to re-resolve the data source and get a fresh URL
            if (retryCount > 3) {
                Timber.tag(TAG).d("Retry count > 3, attempting to refresh stream URL")
                val currentPosition = player.currentPosition
                player.seekTo(player.currentMediaItemIndex, currentPosition)
            }
            player.prepare()
            // Don't call play() here - let the player auto-resume via playWhenReady
            // This avoids stealing audio focus during retry attempts
        }
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            // Don't start local playback if casting
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (isCurrentFavorite()) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    ).setIconResId(if (isCurrentFavorite()) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                    .setSessionCommand(CommandToggleLike)
                    // Enable as long as a track is playing, even if it isn't cached in the
                    // local DB yet (e.g. Spotify/YouTube tracks). toggleLike() inserts it on demand.
                    .setEnabled(currentMediaMetadata.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.android_auto_target_playlist))
                    .setIconResId(R.drawable.playlist_add)
                    .setSessionCommand(CommandAddToTargetPlaylist)
                    .setEnabled(currentSong.value != null)
                    .build(),
            ),
        )
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata =
            withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
            } ?: return
        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata.duration.takeIf { it != -1 }
                ?: (
                    playbackData?.videoDetails ?: YTPlayerUtils
                        .playerResponseForMetadata(mediaId)
                        .getOrNull()
                        ?.videoDetails
                )?.lengthSeconds?.toInt()
                ?: -1
        database.query {
            if (song == null) {
                insert(mediaMetadata.copy(duration = duration))
            } else {
                var updatedSong = song.song
                if (song.song.duration == -1) {
                    updatedSong = updatedSong.copy(duration = duration)
                }
                // Update isVideo flag if it's different from the current value
                if (song.song.isVideo != mediaMetadata.isVideoSong) {
                    updatedSong = updatedSong.copy(isVideo = mediaMetadata.isVideoSong)
                }
                if (updatedSong != song.song) {
                    update(updatedSong)
                }
            }
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .filter { songExistsBlocking(it.id) }
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                        )
                    }.forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        // Safety Check : Ensuring player is initilized
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("playQueue called before player initialization, queuing request")
            scope.launch {
                playerInitialized.first { it }
                playQueue(queue, playWhenReady)
            }
            return
        }

        // A new tap always wins: cancel the previous queue's resolution (it could still be searching
        // YouTube for the last track and later overwrite this queue) and stop loading the old item
        // right away, so the app switches to the newly chosen track immediately.
        queueLoadJob?.cancel()
        currentQueue = queue
        RecommendedTracks.clear()
        queueTitle = null
        val persistShuffleAcrossQueues = dataStore.get(PersistentShuffleAcrossQueuesKey, false)
        val previousShuffleEnabled = player.shuffleModeEnabled
        if (!persistShuffleAcrossQueues) {
            player.shuffleModeEnabled = false
        }
        // Reset original queue size when starting a new queue
        originalQueueSize = 0
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        } else if (player.mediaItemCount > 0) {
            player.stop()
        }
        queueLoadJob = scope.launch(SilentHandler) {
            // Issue #143: previously, when shuffle was enabled we called getFullStatus()
            // here, which fetches every page of a Spotify playlist (227+ tracks) and
            // resolves each one to a YouTube MediaItem before playback could start.
            // For large playlists that took seconds. Now we always take the fast
            // initial-status path; if shuffle is enabled, the trailing block below
            // expands the queue and applies the shuffle order in the background while
            // audio already plays.

            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue
                        .getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
            // Another queue was started while this one was resolving — drop the stale result.
            if (currentQueue !== queue) return@launch
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            originalQueueSize = initialStatus.items.size
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex),
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size,
                    ),
                )
            } else {
                player.setMediaItems(
                    initialStatus.items,
                    if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0,
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
            }

            if (player.shuffleModeEnabled) {
                // Fallback when queue doesn't support getFullStatus: load remaining pages then shuffle
                withContext(Dispatchers.IO) { queue.shuffleRemainingTracks() }
                while (queue.hasNextPage() && player.mediaItemCount < SHUFFLE_PRELOAD_MAX_ITEMS) {
                    val moreItems = withContext(Dispatchers.IO) {
                        queue.nextPage()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                    }
                    if (moreItems.isEmpty()) break
                    player.addMediaItems(moreItems)
                }
                originalQueueSize = player.mediaItemCount
                val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
            } else {
                if (player.mediaItemCount <= QUEUE_PRELOAD_AHEAD_THRESHOLD && queue.hasNextPage()) {
                    val moreItems = withContext(Dispatchers.IO) {
                        queue.nextPage()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                    }
                    if (moreItems.isNotEmpty()) {
                        player.addMediaItems(moreItems)
                    }
                }
            }
        }
    }

    /** Replaces what follows the current item with a similar-artists radio. False when not a catalog track. */
    private suspend fun startCatalogRadio(currentMediaId: String, currentIndex: Int): Boolean {
        if (!com.metrolist.music.catalog.Catalog.isActive) return false
        val seed = withContext(Dispatchers.IO) {
            SpotifyMetadataRegistry.get(currentMediaId)?.takeIf { it.id.isNotBlank() }
                ?: (FallbackIds.catalogIdOf(currentMediaId)
                    ?: runCatching { database.getSpotifyMatchByYouTubeId(currentMediaId)?.spotifyId }.getOrNull())
                    ?.let { com.metrolist.music.catalog.Catalog.getTrack(it).getOrNull() }
        } ?: return false
        val radio = com.metrolist.music.playback.queues.SpotifyQueue(
            initialTrack = seed,
            mapper = SpotifyYouTubeMapper(database),
            context = this,
            database = database,
        )
        val items = withContext(Dispatchers.IO) {
            radio.nextPage()
                .filterExplicit(dataStore.get(HideExplicitKey, false))
                .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                .filter { it.mediaId != currentMediaId }
        }
        if (items.isEmpty()) return false
        if (player.currentMediaItem?.mediaId != currentMediaId) return true
        val itemCount = player.mediaItemCount
        if (itemCount > currentIndex + 1) {
            player.removeMediaItems(currentIndex + 1, itemCount)
        }
        player.addMediaItems(currentIndex + 1, items)
        if (player.shuffleModeEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
        currentQueue = radio
        return true
    }

    fun startRadioSeamlessly() {
        // A radio needs a catalog track to start from; a source-search track isn't one.
        if (com.metrolist.music.resolver.SourceSearch.isSourceTrack(player.currentMediaItem?.mediaId)) return
        // Safety Check: Ensure Player is initilized
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("startRadioSeamlessly called before player initialization")
            return
        }

        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id

        scope.launch(SilentHandler) {
            // A catalog track (Spotify / Yandex) gets a radio of similar artists. YouTube's radio for
            // it was built from an id YouTube doesn't know ("mfb:…") and returned unrelated music.
            if (startCatalogRadio(currentMediaId, currentIndex)) return@launch

            // Use simple videoId to let YouTube personalize recommendations
            val radioQueue =
                YouTubeQueue(
                    endpoint =
                        WatchEndpoint(
                            videoId = currentMediaId,
                        ),
                )

            try {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        radioQueue
                            .getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                    }

                if (initialStatus.title != null) {
                    queueTitle = initialStatus.title
                }

                // Filter radio items to exclude current media item
                val radioItems =
                    initialStatus.items.filter { item ->
                        item.mediaId != currentMediaId
                    }

                if (radioItems.isNotEmpty()) {
                    val itemCount = player.mediaItemCount

                    if (itemCount > currentIndex + 1) {
                        player.removeMediaItems(currentIndex + 1, itemCount)
                    }

                    player.addMediaItems(currentIndex + 1, radioItems)
                    if (player.shuffleModeEnabled) {
                        val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                    }
                }

                currentQueue = radioQueue
            } catch (e: Exception) {
                // Fallback: try with related endpoint
                try {
                    val nextResult =
                        withContext(Dispatchers.IO) {
                            YouTube.next(WatchEndpoint(videoId = currentMediaId)).getOrNull()
                        }
                    nextResult?.relatedEndpoint?.let { relatedEndpoint ->
                        val relatedPage =
                            withContext(Dispatchers.IO) {
                                YouTube.related(relatedEndpoint).getOrNull()
                            }
                        relatedPage?.songs?.let { songs ->
                            val radioItems =
                                songs
                                    .filter { it.id != currentMediaId }
                                    .map { it.toMediaItem() }
                                    .filterExplicit(dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))

                            if (radioItems.isNotEmpty()) {
                                val itemCount = player.mediaItemCount
                                if (itemCount > currentIndex + 1) {
                                    player.removeMediaItems(currentIndex + 1, itemCount)
                                }
                                player.addMediaItems(currentIndex + 1, radioItems)
                                if (player.shuffleModeEnabled) {
                                    val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                                    applyShuffleOrder(
                                        player.currentMediaItemIndex,
                                        player.mediaItemCount,
                                        shufflePlaylistFirst,
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Silent fail
                }
            }
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore.get(SimilarContent, true) &&
            !(dataStore.get(DisableLoadMoreWhenRepeatAllKey, false) && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                try {
                    // Try primary method
                    YouTube
                        .next(WatchEndpoint(playlistId = playlistId))
                        .onSuccess { firstResult ->
                            YouTube
                                .next(WatchEndpoint(playlistId = firstResult.endpoint.playlistId))
                                .onSuccess { secondResult ->
                                    automixItems.value =
                                        secondResult.items.map { song ->
                                            song.toMediaItem()
                                        }
                                }.onFailure {
                                    // Fallback: use first result items
                                    if (firstResult.items.isNotEmpty()) {
                                        automixItems.value =
                                            firstResult.items.map { song ->
                                                song.toMediaItem()
                                            }
                                    }
                                }
                        }.onFailure {
                            // Fallback: try with radio format
                            val currentSong = player.currentMetadata
                            if (currentSong != null) {
                                // Use simple videoId for better personalized recommendations
                                YouTube
                                    .next(
                                        WatchEndpoint(
                                            videoId = currentSong.id,
                                        ),
                                    ).onSuccess { radioResult ->
                                        val filteredItems =
                                            radioResult.items
                                                .filter { it.id != currentSong.id }
                                                .map { it.toMediaItem() }
                                        if (filteredItems.isNotEmpty()) {
                                            automixItems.value = filteredItems
                                        }
                                    }.onFailure {
                                        // Final fallback: try related endpoint
                                        YouTube
                                            .next(WatchEndpoint(videoId = currentSong.id))
                                            .getOrNull()
                                            ?.relatedEndpoint
                                            ?.let { relatedEndpoint ->
                                                YouTube.related(relatedEndpoint).onSuccess { relatedPage ->
                                                    val relatedItems =
                                                        relatedPage.songs
                                                            .filter { it.id != currentSong.id }
                                                            .map { it.toMediaItem() }
                                                    if (relatedItems.isNotEmpty()) {
                                                        automixItems.value = relatedItems
                                                    }
                                                }
                                            }
                                    }
                            }
                        }
                } catch (_: Exception) {
                    // Silent fail
                }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixItems.value = emptyList()
    }

    fun playNext(items: List<MediaItem>) {
        // If queue is empty or player is idle, play immediately instead
        if (player.mediaItemCount == 0 || player.playbackState == STATE_IDLE) {
            player.setMediaItems(items)
            player.prepare()
            // Don't start local playback if casting
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        // Remove duplicates if enabled
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        val insertIndex = player.currentMediaItemIndex + 1
        val shuffleEnabled = player.shuffleModeEnabled

        // Insert items immediately after the current item in the window/index space
        player.addMediaItems(insertIndex, items)
        player.prepare()

        if (shuffleEnabled) {
            // Rebuild shuffle order so that newly inserted items are played next
            val timeline = player.currentTimeline
            if (!timeline.isEmpty) {
                val size = timeline.windowCount
                val currentIndex = player.currentMediaItemIndex

                // Newly inserted indices are a contiguous range [insertIndex, insertIndex + items.size)
                val newIndices = (insertIndex until (insertIndex + items.size)).toSet()

                // Collect existing shuffle traversal order excluding current index
                val orderAfter = mutableListOf<Int>()
                var idx = currentIndex
                while (true) {
                    idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (idx == C.INDEX_UNSET) break
                    if (idx != currentIndex) orderAfter.add(idx)
                }

                val prevList = mutableListOf<Int>()
                var pIdx = currentIndex
                while (true) {
                    pIdx = timeline.getPreviousWindowIndex(pIdx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (pIdx == C.INDEX_UNSET) break
                    if (pIdx != currentIndex) prevList.add(pIdx)
                }
                prevList.reverse() // preserve original forward order

                val existingOrder = (prevList + orderAfter).filter { it != currentIndex && it !in newIndices }

                // Build new shuffle order: current -> newly inserted (in insertion order) -> rest
                val nextBlock = (insertIndex until (insertIndex + items.size)).toList()
                val finalOrder = IntArray(size)
                var pos = 0
                finalOrder[pos++] = currentIndex
                nextBlock.forEach { if (it in 0 until size) finalOrder[pos++] = it }
                existingOrder.forEach { if (pos < size) finalOrder[pos++] = it }

                // Fill any missing indices (safety) to ensure a full permutation
                if (pos < size) {
                    for (i in 0 until size) {
                        if (!finalOrder.contains(i)) {
                            finalOrder[pos++] = i
                            if (pos == size) break
                        }
                    }
                }

                player.setShuffleOrder(DefaultShuffleOrder(finalOrder, System.currentTimeMillis()))
            }
        }
    }

    /**
     * "Add to queue" on a track of an opened album ([albumQueue] starts at that track): the track and
     * the rest of the album after it go to the end of the queue in album order, and once the album
     * is over the queue goes on with recommendations built from this album.
     */
    fun addAlbumFromTrack(albumQueue: Queue) {
        if (player.mediaItemCount == 0 || player.playbackState == STATE_IDLE) {
            playQueue(albumQueue)
            return
        }
        scope.launch(SilentHandler) {
            val status = withContext(Dispatchers.IO) {
                albumQueue.getInitialStatus()
                    .filterExplicit(dataStore.get(HideExplicitKey, false))
                    .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
            }
            if (status.items.isEmpty()) return@launch
            addToQueue(status.items.drop(status.mediaItemIndex.coerceIn(0, status.items.size - 1)))
            // What follows the album now comes from the album, not from the previous queue's radio.
            currentQueue = albumQueue
        }
    }

    fun addToQueue(items: List<MediaItem>) {
        // Remove duplicates if enabled
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        // Capture the already-played part of the shuffle order *before* mutating the
        // queue. addMediaItems() appends, so existing indices stay valid afterwards.
        val playedBefore =
            if (player.shuffleModeEnabled) playedShuffleIndices(player.currentMediaItemIndex) else emptyList()

        player.addMediaItems(items)
        if (player.shuffleModeEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            if (playedBefore.isEmpty()) {
                applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
            } else {
                // Reshuffling the whole queue here would drop the listening history and put
                // already-played tracks back in front of the user (issue #232). Keep the
                // played prefix intact and randomise only what's still ahead.
                applyShuffleOrderKeepingHistory(
                    playedBefore,
                    player.currentMediaItemIndex,
                    player.mediaItemCount,
                )
            }
        }
        player.prepare()
    }

    /**
     * Indices already traversed in the current shuffle order, in play order, up to but
     * excluding [currentIndex]. Derived from the timeline instead of tracked separately so
     * it cannot drift out of sync with ExoPlayer's own shuffle order.
     */
    private fun playedShuffleIndices(currentIndex: Int): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()

        val played = mutableListOf<Int>()
        var idx = currentIndex
        // getPreviousWindowIndex with REPEAT_MODE_OFF terminates at the start of the
        // order; the step cap is a guard against a malformed order looping forever.
        var steps = 0
        while (steps++ < timeline.windowCount) {
            idx = timeline.getPreviousWindowIndex(idx, Player.REPEAT_MODE_OFF, true)
            if (idx == C.INDEX_UNSET || idx == currentIndex) break
            played.add(idx)
        }
        played.reverse()
        return played.distinct()
    }

    private fun applyShuffleOrderKeepingHistory(
        playedIndices: List<Int>,
        currentIndex: Int,
        totalCount: Int,
    ) {
        if (totalCount == 0) return

        val played = playedIndices.filter { it in 0 until totalCount && it != currentIndex }
        val playedSet = played.toSet()
        val remaining = (0 until totalCount)
            .filter { it != currentIndex && it !in playedSet }
            .toMutableList()
        remaining.shuffle()

        val order = IntArray(totalCount)
        var pos = 0
        played.forEach { order[pos++] = it }
        order[pos++] = currentIndex
        remaining.forEach { order[pos++] = it }

        player.setShuffleOrder(DefaultShuffleOrder(order, System.currentTimeMillis()))
    }

    fun toggleLibrary() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let {
                val isInLibrary = it.song.inLibrary != null
                val token = if (isInLibrary) it.song.libraryRemoveToken else it.song.libraryAddToken

                // Call YouTube API with feedback token if available
                token?.let { feedbackToken ->
                    YouTube.feedback(listOf(feedbackToken))
                }

                // Update local database
                database.query {
                    update(it.song.toggleLibrary())
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    /** Catalog (Spotify / Yandex) track id behind a media id, when the track has one. */
    private fun catalogTrackIdOf(mediaId: String?): String? {
        if (mediaId == null) return null
        // Source-search tracks have no catalog identity: nothing to like in Spotify/Yandex.
        if (com.metrolist.music.resolver.SourceSearch.isSourceTrack(mediaId)) return null
        return SpotifyMetadataRegistry.get(mediaId)?.id?.takeIf { it.isNotBlank() }
            ?: FallbackIds.catalogIdOf(mediaId)
            ?: runCatching { database.getSpotifyMatchByYouTubeId(mediaId)?.spotifyId }.getOrNull()
    }

    /** mediaId -> catalog id of the current track, filled off the main thread (it may need Room). */
    @Volatile
    private var currentCatalogId: Pair<String, String?>? = null

    /** In-memory lookup only, safe on the main thread. */
    private fun catalogTrackIdFast(mediaId: String?): String? {
        if (mediaId == null) return null
        if (com.metrolist.music.resolver.SourceSearch.isSourceTrack(mediaId)) return null
        SpotifyMetadataRegistry.get(mediaId)?.id?.takeIf { it.isNotBlank() }?.let { return it }
        FallbackIds.catalogIdOf(mediaId)?.let { return it }
        return currentCatalogId?.takeIf { it.first == mediaId }?.second
    }

    /**
     * What the heart shows for the current track everywhere (player, notification, widget): liked in
     * the app OR liked on the linked account. A track liked directly on Spotify has no local flag.
     */
    private fun isCurrentFavorite(): Boolean {
        val song = currentSong.value?.song
        if (song?.isEpisode == true) return song.inLibrary != null
        val mediaId = currentMediaMetadata.value?.id ?: player.currentMetadata?.id
        return song?.liked == true || SpotifyLikeCache.isLiked(catalogTrackIdFast(mediaId))
    }

    /**
     * Flips the heart of the current track. The target is decided NOW from what the user sees, so
     * the player, the notification and the home-screen widget all behave the same (the widget used
     * to toggle only the local flag, which did nothing for a track liked on Spotify).
     */
    fun toggleLike() {
        // A track from the experimental sources is liked locally (and, from VK, also in the user's
        // VK music) — never in the catalog account, which doesn't know it.
        val song = currentSong.value?.song
        if (song?.isEpisode == true) {
            scope.launch { toggleEpisodeSaveForLater(song) }
            return
        }
        setCurrentLiked(!isCurrentFavorite())
    }

    /**
     * Sets the heart of the current track to what the UI showed the user flipping, instead of
     * re-deciding it here (the service may not know yet that the track is liked on the account).
     */
    fun setCurrentFavorite(liked: Boolean) {
        val song = currentSong.value?.song
        if (song?.isEpisode == true) {
            if ((song.inLibrary != null) != liked) scope.launch { toggleEpisodeSaveForLater(song) }
            return
        }
        setCurrentLiked(liked)
    }

    private fun setCurrentLiked(liked: Boolean) {
        val metadata = currentMediaMetadata.value ?: player.currentMetadata ?: return
        setTrackLiked(metadata, liked)
    }

    /**
     * Likes or unlikes any track (the current one or one from a menu), the same way everywhere: in the
     * app, plus the account that can take it (Spotify / Yandex Music Liked Songs, VK music).
     */
    fun setTrackLiked(metadata: com.metrolist.music.models.MediaMetadata, liked: Boolean) {
        scope.launch {
            var librarySong = database.song(metadata.id).first()
            // The track may not be cached in the local DB yet (common for Spotify/YouTube tracks
            // played from quick picks, radio or imports). Insert it first so the like can be
            // applied and synced instead of silently no-oping.
            if (librarySong == null) {
                // Written before reading it back (database.query is fire-and-forget: the first tap
                // used to read nothing and do nothing, so a like needed a second tap).
                withContext(Dispatchers.IO) { database.withTransaction { insert(metadata) } }
                librarySong = database.song(metadata.id).first()
            }
            val songEntity = librarySong?.song ?: return@launch
            val catalogId = withContext(Dispatchers.IO) { catalogTrackIdOf(songEntity.id) }
            val isCurrent = songEntity.id == (currentMediaMetadata.value?.id ?: player.currentMetadata?.id)
            if (isCurrent) currentCatalogId = songEntity.id to catalogId

            // Update the shared like cache first so every heart flips instantly.
            if (catalogId != null) SpotifyLikeCache.setLiked(catalogId, liked)

            // The same catalog track can have two rows (its YouTube id and its source id): an unlike
            // clears both, else the other row kept lighting the heart up again.
            if (!liked && catalogId != null) {
                withContext(Dispatchers.IO) {
                    listOfNotNull(
                        FallbackIds.of(catalogId),
                        runCatching { database.getSpotifyMatch(catalogId)?.youtubeId }.getOrNull(),
                    ).filter { it != songEntity.id }.forEach { otherId ->
                        database.getSongByIdBlocking(otherId)?.song?.takeIf { it.liked }?.let {
                            database.update(it.toggleLike())
                        }
                    }
                }
            }
            val song = if (songEntity.liked == liked) songEntity else songEntity.toggleLike()
            database.query {
                update(song)
                syncUtils.likeSong(song)

                // Check if auto-download on like is enabled and the song is now liked
                if (dataStore.get(AutoDownloadOnLikeKey, false) && song.liked) {
                    val downloadRequest =
                        androidx.media3.exoplayer.offline.DownloadRequest
                            .Builder(song.id, song.id.toUri())
                            .setCustomCacheKey(song.id)
                            .setData(song.title.toByteArray())
                            .build()
                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                        this@MusicService,
                        ExoDownloadService::class.java,
                        downloadRequest,
                        false,
                    )
                }
            }

            // A VK track from the experimental search: the like also goes to the user's VK music.
            if (com.metrolist.music.resolver.SourceSearch.providerOf(songEntity.id) ==
                com.metrolist.music.resolver.AudioProviderId.VK &&
                com.metrolist.music.resolver.VkMusic.likesToAccount
            ) {
                val vkTrackId = com.metrolist.music.resolver.FallbackIds.catalogIdOf(songEntity.id)
                    ?.substringAfter("VK:")
                if (vkTrackId != null) {
                    scope.launch(Dispatchers.IO) {
                        com.metrolist.music.resolver.VkMusic.setTrackSaved(this@MusicService, vkTrackId, liked)
                            .onFailure { Timber.w(it, "VK like sync failed for $vkTrackId") }
                    }
                }
            }

            // Mirror the like to the account that owns the track's metadata: the heart means
            // "add to Liked Songs" on Spotify or Yandex Music. No-ops when not linked or unmapped.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (catalogId != null && com.metrolist.music.catalog.Catalog.canWrite(catalogId)) {
                        if (liked) {
                            com.metrolist.music.catalog.Catalog.saveTrack(catalogId).getOrThrow()
                        } else {
                            com.metrolist.music.catalog.Catalog.removeTrack(catalogId).getOrThrow()
                        }
                    }
                }.onFailure {
                    Timber.w(it, "Catalog like sync failed for ${songEntity.id}")
                    // Put the heart back so it never claims a state the account doesn't have.
                    if (catalogId != null) SpotifyLikeCache.setLiked(catalogId, !liked)
                }
            }

            if (isCurrent) {
                currentMediaMetadata.value = player.currentMetadata
                // Refresh the widget and notification right away instead of after the DB debounce.
                updateNotification()
                updateWidgetUI(player.isPlaying)
            }
        }
    }

    fun addToTargetPlaylist() {
        scope.launch {
            val currentSong = currentSong.first() ?: return@launch
            val targetPlaylistId = dataStore.get(AndroidAutoTargetPlaylistKey, MediaSessionConstants.TARGET_PLAYLIST_AUTO)

            if (targetPlaylistId == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
                Handler(Looper.getMainLooper()).post {
                    Toast
                        .makeText(
                            this@MusicService,
                            getString(R.string.android_auto_target_playlist_not_set),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                return@launch
            }

            database.query {
                insert(
                    com.metrolist.music.db.entities.PlaylistSongMap(
                        playlistId = targetPlaylistId,
                        songId = currentSong.id,
                        position = Int.MAX_VALUE,
                    ),
                )
            }
        }
    }

    private suspend fun toggleEpisodeSaveForLater(songEntity: com.metrolist.music.db.entities.SongEntity) {
        val isCurrentlySaved = songEntity.inLibrary != null
        val shouldBeSaved = !isCurrentlySaved

        // Update database first (optimistic update)
        // Also ensure isEpisode = true so it appears in saved episodes list
        database.query {
            update(
                songEntity.copy(
                    inLibrary = if (isCurrentlySaved) null else java.time.LocalDateTime.now(),
                    isEpisode = true,
                ),
            )
        }
        currentMediaMetadata.value = player.currentMetadata

        // Sync with YouTube (handles login check internally)
        val setVideoId = if (isCurrentlySaved) database.getSetVideoId(songEntity.id)?.setVideoId else null
        syncUtils.saveEpisode(songEntity.id, shouldBeSaved, setVideoId)
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun setupLoudnessEnhancer() {
        val audioSessionId = player.audioSessionId

        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            Timber
                .tag(TAG)
                .w("setupLoudnessEnhancer: invalid audioSessionId ($audioSessionId), cannot create effect yet")
            return
        }

        // Create or recreate enhancer if needed
        if (loudnessEnhancer == null) {
            try {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                Timber.tag(TAG).d("LoudnessEnhancer created for sessionId=$audioSessionId")
            } catch (e: Exception) {
                reportException(e)
                loudnessEnhancer = null
                return
            }
        }

        scope.launch {
            try {
                val currentMediaId =
                    withContext(Dispatchers.Main) {
                        player.currentMediaItem?.mediaId
                    }

                val normalizeAudio =
                    withContext(Dispatchers.IO) {
                        dataStore.data.map { it[AudioNormalizationKey] ?: true }.first()
                    }

                if (normalizeAudio && currentMediaId != null) {
                    val format =
                        withContext(Dispatchers.IO) {
                            database.format(currentMediaId).first()
                        }

                    Timber.tag(TAG).d("Audio normalization enabled: $normalizeAudio")
                    Timber
                        .tag(TAG)
                        .d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")

                    // Use loudnessDb if available, otherwise fall back to perceptualLoudnessDb
                    val loudness = format?.loudnessDb ?: format?.perceptualLoudnessDb

                    withContext(Dispatchers.Main) {
                        if (loudness != null) {
                            val loudnessDb = loudness.toFloat()
                            val targetGain = (-loudnessDb * 100).toInt()
                            val clampedGain = targetGain.coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)

                            Timber
                                .tag(TAG)
                                .d("Calculated raw normalization gain: $targetGain mB (from loudness: $loudnessDb)")

                            try {
                                loudnessEnhancer?.setTargetGain(clampedGain)
                                loudnessEnhancer?.enabled = true
                                Timber.tag(TAG).i("LoudnessEnhancer gain applied: $clampedGain mB")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to apply loudness enhancement")
                                reportException(e)
                                releaseLoudnessEnhancer()
                            }
                        } else {
                            loudnessEnhancer?.enabled = false
                            Timber
                                .tag(TAG)
                                .w("Normalization enabled but no loudness data available - no normalization applied")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        loudnessEnhancer?.enabled = false
                        Timber.tag(TAG).d("setupLoudnessEnhancer: normalization disabled or mediaId unavailable")
                    }
                }
            } catch (e: Exception) {
                reportException(e)
                releaseLoudnessEnhancer()
            }
        }
    }

    private fun releaseLoudnessEnhancer() {
        try {
            loudnessEnhancer?.release()
            Timber.tag(TAG).d("LoudnessEnhancer released")
        } catch (e: Exception) {
            reportException(e)
            Timber.tag(TAG).e(e, "Error releasing LoudnessEnhancer: ${e.message}")
        } finally {
            loudnessEnhancer = null
        }
    }

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        setupLoudnessEnhancer()
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        releaseLoudnessEnhancer()
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            },
        )
    }

    private var previousMediaItemIndex = C.INDEX_UNSET
    private var previousEpisodeId: String? = null
    private var previousEpisodePosition: Long = 0L

    /**
     * Save podcast episode playback position to database.
     * Only saves if the item is an episode and position is meaningful (> 3 seconds).
     */
    private fun saveEpisodePosition(
        episodeId: String,
        positionMs: Long,
    ) {
        if (positionMs < 3000) return // Don't save if less than 3 seconds played
        scope.launch(Dispatchers.IO + SilentHandler) {
            database.updatePlaybackPosition(episodeId, positionMs)
            Timber.tag(TAG).d("Saved episode position: $episodeId at ${positionMs}ms")
        }
    }

    /**
     * Restore podcast episode playback position from database.
     * Seeks to saved position if available.
     */
    private fun restoreEpisodePosition(episodeId: String) {
        scope.launch(Dispatchers.IO + SilentHandler) {
            val savedPosition = database.getPlaybackPosition(episodeId)
            if (savedPosition != null && savedPosition > 0) {
                withContext(Dispatchers.Main) {
                    // Only seek if we're still on the same episode
                    if (player.currentMediaItem?.mediaId == episodeId) {
                        player.seekTo(savedPosition)
                        Timber.tag(TAG).d("Restored episode position: $episodeId to ${savedPosition}ms")
                    }
                }
            }
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // Save previous episode position if it was an episode
        previousEpisodeId?.let { episodeId ->
            if (previousEpisodePosition > 0) {
                saveEpisodePosition(episodeId, previousEpisodePosition)
            }
        }
        previousEpisodeId = null
        previousEpisodePosition = 0L

        // Queues that pick songs by themselves skip what has just been heard.
        mediaItem?.mediaId?.let(SpotifyMetadataRegistry::get)?.let { track ->
            RecentlyPlayed.record(track)
            // Playing a track counts as using its album ("Recents" in the library).
            LibraryRecents.album(track.album?.id)
        }

        // Check if new item is an episode and restore its position
        val newMetadata = mediaItem?.metadata
        if (newMetadata?.isEpisode == true) {
            previousEpisodeId = newMetadata.id
            // Delay restoration to let playback start
            scope.launch {
                delay(100)
                restoreEpisodePosition(newMetadata.id)
            }
        }

        // Force Repeat One if the player ignored it and auto-advanced
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            val repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)
            if (repeatMode == REPEAT_MODE_ONE &&
                previousMediaItemIndex != C.INDEX_UNSET &&
                previousMediaItemIndex != player.currentMediaItemIndex
            ) {
                player.seekTo(previousMediaItemIndex, 0)
            }
        }
        previousMediaItemIndex = player.currentMediaItemIndex

        lastPlaybackSpeed = -1.0f // force update song

        setupLoudnessEnhancer()

        discordUpdateJob?.cancel()

        // Restart SponsorBlock for the new track (no-op when disabled).
        startSponsorBlockForCurrentTrack()

        scrobbleManager?.onSongStop(finalize = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
        }

        // Sync Cast when media changes and Cast is connected
        // Skip if this change was triggered by Cast sync (to prevent loops)
        if (castConnectionHandler?.isCasting?.value == true &&
            castConnectionHandler?.isSyncingFromCast != true &&
            mediaItem != null
        ) {
            val metadata = mediaItem.metadata
            if (metadata != null) {
                // Try to navigate to the item if it's already in Cast queue
                // This avoids a full reload which causes the widget to refresh
                val navigated = castConnectionHandler?.navigateToMediaIfInQueue(metadata.id) ?: false
                if (!navigated) {
                    // Item not in Cast queue, need to reload
                    castConnectionHandler?.loadMedia(metadata)
                }
            }
        }

        // Auto load more songs from queue (lazy load: keep QUEUE_PRELOAD_AHEAD_THRESHOLD items ahead)
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= QUEUE_PRELOAD_AHEAD_THRESHOLD
        ) {
            loadMoreOfQueue(resumeWhenLoaded = false)
        }

        // Pre-cache upcoming tracks for offline playback
        triggerPreCache()
        warmUpNextTrack()

        // Save state when media item changes
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
    }

    private var loadMoreJob: Job? = null

    /**
     * Appends the queue's next page. One load at a time: skipping fast used to start several, and
     * the queues are not built for concurrent paging. [resumeWhenLoaded]: the queue already ran out
     * (the last load came back empty or late), so playback goes on with the first new track.
     */
    private fun loadMoreOfQueue(resumeWhenLoaded: Boolean) {
        if (!dataStore.get(AutoLoadMoreKey, true) || !currentQueue.hasNextPage()) return
        if (dataStore.get(DisableLoadMoreWhenRepeatAllKey, false) && player.repeatMode == REPEAT_MODE_ALL) return
        if (loadMoreJob?.isActive == true) return
        val queue = currentQueue
        loadMoreJob = scope.launch(SilentHandler) {
            val mediaItems =
                withContext(Dispatchers.IO) {
                    queue
                        .nextPage()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
            if (currentQueue !== queue || mediaItems.isEmpty()) return@launch
            if (player.playbackState == STATE_IDLE && !resumeWhenLoaded) return@launch
            val firstNew = player.mediaItemCount
            player.addMediaItems(mediaItems)
            // Don't re-shuffle here: ExoPlayer's DefaultShuffleOrder.cloneAndInsert()
            // already places newly added items at random positions within the existing
            // shuffle order, preserving the sequence the user is currently listening to.
            if (resumeWhenLoaded && player.playbackState == Player.STATE_ENDED) {
                player.seekTo(firstNew, 0)
                player.prepare()
                player.play()
            }
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        // Listening in the background is using the app: no launch intro on the next open.
        if (player.playWhenReady) com.metrolist.music.ui.component.AppIntroState.markActive(this)
        // Force Repeat All if the player ignored it and ended playback
        if (playbackState == Player.STATE_ENDED) {
            val repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)
            if (repeatMode == REPEAT_MODE_ALL && player.mediaItemCount > 0) {
                player.seekTo(0, 0)
                player.prepare()
                player.play()
            } else {
                // The queue ran out before its next page arrived: an endless queue goes on.
                loadMoreOfQueue(resumeWhenLoaded = true)
            }
        }

        // Save state when playback state changes (but not during silence skipping)
        if (dataStore.get(PersistentQueueKey, true) && !isSilenceSkipping) {
            saveQueueToDisk()
        }

        if (playbackState == Player.STATE_READY) {
            consecutivePlaybackErr = 0
            retryCount = 0
            waitingForNetworkConnection.value = false
            retryJob?.cancel()

            // Reset retry count for current song on successful playback
            player.currentMediaItem?.mediaId?.let { mediaId ->
                resetRetryCount(mediaId)
                Timber.tag(TAG).d("Playback successful for $mediaId, reset retry count")
            }
            scheduleCrossfade()
        }

        if (playbackState == Player.STATE_ENDED) {
            scrobbleManager?.onSongStop(finalize = true)
        } else if (playbackState == Player.STATE_IDLE) {
            scrobbleManager?.onSongStop()
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        // Safety net: if local player tries to start while casting, immediately pause it
        if (playWhenReady && castConnectionHandler?.isCasting?.value == true) {
            player.pause()
            return
        }

        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            if (playWhenReady) {
                isPausedByVolumeMute = false
            }

            if (!playWhenReady && !isPausedByVolumeMute) {
                wasPlayingBeforeVolumeMute = false
            }
        }

        // Save episode position when pausing
        if (!playWhenReady) {
            val currentMetadata = player.currentMediaItem?.metadata
            if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
                saveEpisodePosition(currentMetadata.id, player.currentPosition)
                previousEpisodePosition = player.currentPosition
            }
        }

        if (playWhenReady) {
            setupLoudnessEnhancer()
        }
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            scheduleCrossfade()
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                val focusGranted = requestAudioFocus()
                if (focusGranted) {
                    openAudioEffectSession()
                }
            } else {
                closeAudioEffectSession()
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }

        // Widget and Discord RPC updates
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            updateWidgetUI(player.isPlaying)
            if (player.isPlaying) {
                startWidgetUpdates()
            } else {
                stopWidgetUpdates()
            }
            if (!player.isPlaying &&
                !events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                )
            ) {
                scope.launch {
                    discordRpc?.close()
                }
            }
        }

        // Update Discord RPC when media item changes or playback starts
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
            ) && player.isPlaying
        ) {
            val mediaId = player.currentMetadata?.id
            if (mediaId != null) {
                scope.launch {
                    // Fetch song from database to get full info
                    database.song(mediaId).first()?.let { song ->
                        updateDiscordRPC(song)
                    }
                }
            }
        }

        // Scrobbling
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
        ) {
            scrobbleManager?.onPlayerStateChanged(
                player.isPlaying,
                player.currentMetadata,
                duration = player.duration,
            )
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            if (player.mediaItemCount == 0) return

            val queue = currentQueue ?: return
            val currentPositionMs = player.currentPosition
            // Prefer full playlist so shuffle includes all tracks (e.g. 227), not just from current onwards.
            scope.launch(SilentHandler) {
                val fullStatus = withContext(Dispatchers.IO) {
                    queue.getFullStatus()
                        ?.filterExplicit(dataStore.get(HideExplicitKey, false))
                        ?.filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
                if (fullStatus != null && fullStatus.items.isNotEmpty()) {
                    val currentId = player.currentMetadata?.id
                    val startIndex = if (currentId != null) {
                        fullStatus.items.indexOfFirst { it.mediaId == currentId }.takeIf { it >= 0 }
                            ?: fullStatus.mediaItemIndex
                    } else {
                        fullStatus.mediaItemIndex
                    }.coerceIn(0, fullStatus.items.size - 1)
                    player.setMediaItems(fullStatus.items, startIndex, currentPositionMs.coerceAtLeast(0L))
                    player.prepare()
                    if (player.playbackState != Player.STATE_IDLE) {
                        player.playWhenReady = true
                    }
                    originalQueueSize = fullStatus.items.size
                    val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                    applyShuffleOrder(startIndex, fullStatus.items.size, shufflePlaylistFirst)
                    return@launch
                }
                if (queue.hasNextPage()) {
                    withContext(Dispatchers.IO) { queue.shuffleRemainingTracks() }
                    while (queue.hasNextPage() && player.mediaItemCount < SHUFFLE_PRELOAD_MAX_ITEMS) {
                        val moreItems = withContext(Dispatchers.IO) {
                            queue.nextPage()
                                .filterExplicit(dataStore.get(HideExplicitKey, false))
                                .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                        }
                        if (moreItems.isEmpty()) break
                        player.addMediaItems(moreItems)
                    }
                    originalQueueSize = player.mediaItemCount
                    val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                    applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                } else {
                    val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                    applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                }
            }
        }

        // Save shuffle mode to preferences
        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            scope.launch {
                dataStore.edit { settings ->
                    settings[ShuffleModeKey] = shuffleModeEnabled
                }
            }
        }

        // Save state when shuffle mode changes
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        // Save state when repeat mode changes
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
    }

    /**
     * Applies a new shuffle order to the player, maintaining the current item's position.
     * If `shufflePlaylistFirst` is true, it attempts to shuffle original items separately from added items.
     */
    private fun applyShuffleOrder(
        currentIndex: Int,
        totalCount: Int,
        shufflePlaylistFirst: Boolean,
    ) {
        if (totalCount == 0) return

        if (shufflePlaylistFirst && originalQueueSize > 0 && originalQueueSize < totalCount) {
            // Shuffle original items and added items separately
            val originalIndices = (0 until originalQueueSize).filter { it != currentIndex }.toMutableList()
            val addedIndices = (originalQueueSize until totalCount).filter { it != currentIndex }.toMutableList()

            originalIndices.shuffle()
            addedIndices.shuffle()

            val shuffledIndices = IntArray(totalCount)
            var pos = 0
            shuffledIndices[pos++] = currentIndex

            if (currentIndex < originalQueueSize) {
                originalIndices.forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            } else {
                (0 until originalQueueSize).shuffled().forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        } else {
            val shuffledIndices = IntArray(totalCount) { it }
            shuffledIndices.shuffle()
            // Ensure current item is first in the shuffle order
            val currentItemIndexInShuffled = shuffledIndices.indexOf(currentIndex)
            if (currentItemIndexInShuffled != -1) { // Should always be true if totalCount > 0
                val temp = shuffledIndices[0]
                shuffledIndices[0] = shuffledIndices[currentItemIndexInShuffled]
                shuffledIndices[currentItemIndexInShuffled] = temp
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        if (playbackParameters.speed != lastPlaybackSpeed) {
            lastPlaybackSpeed = playbackParameters.speed
            discordUpdateJob?.cancel()

            // update scheduling thingy
            discordUpdateJob =
                scope.launch {
                    delay(1000)
                    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                        currentSong.value?.let { song ->
                            updateDiscordRPC(song)
                        }
                    }
                }
        }
    }

    /**
     * Extracts the HTTP response code from an error's cause chain.
     * Returns null if no HTTP response code is found.
     */
    private fun getHttpResponseCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }

    /**
     * Checks if the error is caused by an expired/forbidden URL (HTTP 403).
     * This typically happens when a YouTube stream URL expires.
     */
    private fun isExpiredUrlError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 403
    }

    /**
     * Checks if the error is a Range Not Satisfiable error (HTTP 416).
     * This happens when cached data doesn't match the actual stream size.
     */
    private fun isRangeNotSatisfiableError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 416
    }

    /**
     * Checks if the error is a "page needs to be reloaded" error.
     * This is a YouTube-specific error that requires refreshing the stream.
     */
    private fun isPageReloadError(error: PlaybackException): Boolean {
        val errorMessage = error.message?.lowercase() ?: ""
        val causeMessage = error.cause?.message?.lowercase() ?: ""
        val innerCauseMessage =
            error.cause
                ?.cause
                ?.message
                ?.lowercase() ?: ""

        val reloadKeywords =
            listOf(
                "page needs to be reloaded",
                "pagina deve essere ricaricata",
                "la pagina deve essere ricaricata",
                "page must be reloaded",
                "reload",
                "ricaricata",
            )

        return reloadKeywords.any { keyword ->
            errorMessage.contains(keyword) ||
                causeMessage.contains(keyword) ||
                innerCauseMessage.contains(keyword)
        }
    }

    /**
     * Transient YouTube responses where the player response is missing critical
     * data (expire time, format, stream URL, or empty player response). Treated
     * like an expired URL: refresh caches and retry rather than crashing.
     */
    private fun isMissingStreamDataError(error: PlaybackException): Boolean {
        val keywords =
            listOf(
                "missing stream expire time",
                "could not find format",
                "could not find stream url",
                "bad stream player response",
            )
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message?.lowercase()
            if (message != null && keywords.any { message.contains(it) }) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * Transient MediaCodec decoder failures (e.g. opus decoder dropping a frame).
     * These are not actionable for the user and recover by re-initializing the
     * audio renderer.
     */
    private fun isMediaCodecError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
        ) {
            return true
        }
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is android.media.MediaCodec.CodecException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Detects a corrupt-container source error that no amount of retrying will fix
     * (e.g. MatroskaExtractor's recurring `ArrayIndexOutOfBoundsException` on a
     * partial WebM stream). The only sensible response is to skip the track —
     * see github.com/FrancescoGrazioso/Meld/issues/94. Suppresses the crash
     * report since this is an upstream Media3 bug, not our own.
     */
    private fun isMalformedContainerError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val stack = cause.stackTrace
            for (frame in stack) {
                val cls = frame.className
                val inExtractor = cls.contains("androidx.media3.extractor.mkv") ||
                    cls.contains("androidx.media3.extractor.mp4") ||
                    cls.contains("androidx.media3.extractor.ogg") ||
                    cls.contains("androidx.media3.extractor.DefaultExtractorInput")
                if (inExtractor) {
                    if (cause is ArrayIndexOutOfBoundsException ||
                        cause is IndexOutOfBoundsException ||
                        cause is NumberFormatException ||
                        cause is java.io.EOFException ||
                        cause is IllegalStateException
                    ) {
                        return true
                    }
                    // Media3 ParserException for malformed EBML/MKV headers (issue #142:
                    // "Invalid integer size: 78" from DefaultEbmlReader on a truncated
                    // stream). Match by class name to avoid an extra direct dependency
                    // on androidx.media3.common from here.
                    if (cause.javaClass.name == "androidx.media3.common.ParserException") {
                        return true
                    }
                }
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * Detects an audio-sink buffer mismatch that throws IllegalArgumentException from
     * DefaultAudioSink.handleBuffer (issue #138). Happens on a format change mid-stream;
     * a track skip / re-prepare is the only fix.
     */
    private fun isAudioSinkBufferError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val stack = cause.stackTrace
            for (frame in stack) {
                if (frame.className.contains("androidx.media3.exoplayer.audio.DefaultAudioSink") &&
                    cause is IllegalArgumentException
                ) {
                    return true
                }
            }
            cause = cause.cause
        }
        return false
    }

    private fun isNetworkRelatedError(error: PlaybackException): Boolean {
        // Don't treat specific errors as network errors - they need special handling
        if (isExpiredUrlError(error) || isRangeNotSatisfiableError(error) || isPageReloadError(error)) {
            return false
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.cause is java.net.ConnectException ||
            error.cause is java.net.UnknownHostException ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    /**
     * Checks if the error is caused by AudioTrack write or initialization failures.
     * These errors indicate the audio renderer is in a corrupted/invalid state.
     */
    private fun isAudioRendererError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        // Safety check : ensuring player is still initialized
        if (!playerInitialized.value) {
            Timber.tag(TAG).e(error, "Player error occurred but player not initialized")
            return
        }

        val mediaId = player.currentMediaItem?.mediaId
        Timber
            .tag(TAG)
            .w(error, "Player error occurred for $mediaId: errorCode=${error.errorCode}, message=${error.message}")

        /*
         * error.message is always "Source error" and the code is often a re-classification, so
         * neither identifies the failure. The cause chain does: it carries the HTTP status, the
         * DataSourceException reason the resolver chose, and the original exception text.
         */
        val fx = Fix403.nextId("err")
        Fix403.e(
            fx, "player.error",
            Fix403.kv(
                "mediaId" to mediaId,
                "errorCode" to error.errorCode,
                "errorCodeName" to error.errorCodeName,
                "message" to error.message,
                "position" to Fix403.trap(fx, "player.position") { player.currentPosition },
                "duration" to Fix403.trap(fx, "player.duration") { player.duration },
                "bufferedPosition" to Fix403.trap(fx, "player.buffered") { player.bufferedPosition },
            ),
        )
        Fix403.fail(fx, "player.error.cause", error)
        (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let { http ->
            Fix403.e(
                fx, "player.error.http",
                Fix403.kv(
                    "responseCode" to http.responseCode,
                    "responseMessage" to http.responseMessage,
                    "uri" to http.dataSpec.uri.host,
                    "position" to http.dataSpec.position,
                    "length" to http.dataSpec.length,
                    // A Range-less (position=0,length=UNSET) or open-ended request is answered
                    // 403 by googlevideo on art tracks — see the urlCache.hit note in the resolver.
                    "rangeShape" to when {
                        http.dataSpec.position == 0L && http.dataSpec.length == -1L -> "NONE"
                        http.dataSpec.length == -1L -> "OPEN_ENDED"
                        else -> "BOUNDED"
                    },
                    "requestHeaders" to http.headerFields.keys.joinToString(","),
                ),
            )
        }

        // Transient YouTube CDN / decoder errors are auto-recovered; skip crash reporting.
        val isRecoverableYouTubeError = isRangeNotSatisfiableError(error) ||
            isExpiredUrlError(error) ||
            isPageReloadError(error) ||
            isMissingStreamDataError(error) ||
            isMediaCodecError(error) ||
            isMalformedContainerError(error) ||
            isAudioSinkBufferError(error)
        if (!isRecoverableYouTubeError) {
            reportException(error)
        }

        // Check if this song has failed too many times
        if (mediaId != null && hasExceededRetryLimit(mediaId)) {
            Timber.tag(TAG).w("Song $mediaId has exceeded retry limit, skipping")
            markSongAsFailed(mediaId)
            handleFinalFailure()
            return
        }

        // Aggressive cache clearing for all playback errors
        if (mediaId != null) {
            performAggressiveCacheClear(mediaId)
        }

        // Handle specific error types with strict strategies
        when {
            isAudioRendererError(error) -> {
                Timber.tag(TAG).d("AudioTrack error detected (${error.errorCode}), performing safe recovery")
                handleAudioRendererError(mediaId)
                return
            }

            isRangeNotSatisfiableError(error) -> {
                Timber.tag(TAG).d("Range Not Satisfiable (416) detected, performing strict recovery")
                handleRangeNotSatisfiableError(mediaId)
                return
            }

            isPageReloadError(error) -> {
                Timber.tag(TAG).d("Page reload error detected, performing strict recovery")
                handlePageReloadError(mediaId)
                return
            }

            isExpiredUrlError(error) -> {
                Timber.tag(TAG).d("Expired URL (403) detected, refreshing stream URL")
                handleExpiredUrlError(mediaId)
                return
            }

            isMissingStreamDataError(error) -> {
                Timber.tag(TAG).d("Missing stream data from YouTube, refreshing stream URL")
                handleExpiredUrlError(mediaId)
                return
            }

            isMediaCodecError(error) -> {
                Timber.tag(TAG).d("MediaCodec decoder error detected, performing renderer recovery")
                handleAudioRendererError(mediaId)
                return
            }

            isMalformedContainerError(error) -> {
                Timber.tag(TAG).w("Malformed container detected (upstream Media3 bug), auto-skipping $mediaId")
                if (mediaId != null) markSongAsFailed(mediaId)
                skipOnError()
                return
            }

            isAudioSinkBufferError(error) -> {
                Timber.tag(TAG).w("DefaultAudioSink buffer mismatch detected, performing renderer recovery for $mediaId")
                handleAudioRendererError(mediaId)
                return
            }

            !isNetworkConnected.value || isNetworkRelatedError(error) -> {
                Timber.tag(TAG).d("Network-related error detected, waiting for connection")
                waitOnNetworkError()
                return
            }
        }

        // For IO_UNSPECIFIED and IO_BAD_HTTP_STATUS, try recovery first
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        ) {
            Timber.tag(TAG).d("IO error detected (${error.errorCode}), attempting recovery")
            handleGenericIOError(mediaId)
            return
        }

        // Final fallback
        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            Timber.tag(TAG).d("Auto-skipping to next track due to unrecoverable error")
            skipOnError()
        } else {
            Timber.tag(TAG).d("Stopping playback due to unrecoverable error")
            stopOnError()
        }
    }

    /**
     * Performs aggressive cache clearing for a media item.
     * Clears both player cache and download cache, plus URL cache.
     */
    private fun performAggressiveCacheClear(mediaId: String) {
        Timber.tag(TAG).d("Performing aggressive cache clear for $mediaId")

        // Clear URL cache
        songUrlCache.remove(mediaId)
        songUrlCache.remove(RESCUE_CACHE_PREFIX + mediaId)

        // The audio provider serving a fallback / rescued track just failed: the next attempt skips it
        // and switches to another provider that has the track.
        fallbackServing[mediaId]?.let { serving ->
            AudioDiagnostics.warn("playback of $mediaId via ${serving.provider} (${serving.trackId}) failed — switching upload/source")
            markFallbackFailed(mediaId, trackId = serving.trackId)
            runCatching { playerCache.removeResource(RESCUE_CACHE_PREFIX + mediaId) }
        }

        // Clear player cache
        try {
            playerCache.removeResource(mediaId)
            Timber.tag(TAG).d("Cleared player cache for $mediaId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear player cache for $mediaId")
        }

        // Clear decryption caches
        try {
            YTPlayerUtils.forceRefreshForVideo(mediaId)
            Timber.tag(TAG).d("Cleared decryption caches for $mediaId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear decryption caches for $mediaId")
        }
    }

    /**
     * Checks if a song has exceeded the retry limit.
     */
    private fun hasExceededRetryLimit(mediaId: String): Boolean {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        return currentRetries >= MAX_RETRY_PER_SONG
    }

    /**
     * Increments the retry count for a song.
     */
    private fun incrementRetryCount(mediaId: String) {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        currentMediaIdRetryCount[mediaId] = currentRetries + 1
        Timber.tag(TAG).d("Retry count for $mediaId: ${currentRetries + 1}/$MAX_RETRY_PER_SONG")
    }

    /**
     * Resets the retry count for a song (called on successful playback).
     */
    private fun resetRetryCount(mediaId: String) {
        currentMediaIdRetryCount.remove(mediaId)
        recentlyFailedSongs.remove(mediaId)
    }

    /**
     * Marks a song as failed to prevent further retry attempts.
     */
    private fun markSongAsFailed(mediaId: String) {
        recentlyFailedSongs.add(mediaId)
        currentMediaIdRetryCount.remove(mediaId)

        // Schedule cleanup of failed songs list after 5 minutes
        failedSongsClearJob?.cancel()
        failedSongsClearJob =
            scope.launch {
                delay(5 * 60 * 1000L) // 5 minutes
                recentlyFailedSongs.clear()
                Timber.tag(TAG).d("Cleared recently failed songs list")
            }
    }

    /**
     * Handles AudioTrack errors (write failed, init failed) with safe recovery.
     * These errors indicate the audio renderer is corrupted and needs careful reset.
     */
    private fun handleAudioRendererError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                try {
                    // Pause playback immediately to stop the renderer
                    player.pause()
                    Timber.tag(TAG).d("Paused playback due to AudioTrack error")

                    // Wait longer for audio renderer to settle before retry
                    // This prevents the renderer from continuing to fail in a loop
                    delay(RETRY_DELAY_MS * 3) // 3 seconds instead of 1 second

                    // Check if player is still initialized before attempting recovery
                    if (!playerInitialized.value) {
                        Timber.tag(TAG).w("Player no longer initialized, aborting AudioTrack recovery")
                        return@launch
                    }

                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex != C.INDEX_UNSET) {
                        // Seek to current position to force a clean audio renderer reinit
                        val currentPosition = player.currentPosition
                        player.seekTo(currentIndex, currentPosition)
                        player.prepare()

                        Timber.tag(TAG).d("Retrying playback for $mediaId after AudioTrack error")

                        // Resume playback if it wasn't paused by user
                        if (wasPlayingBeforeAudioFocusLoss) {
                            delay(500) // Brief delay to allow renderer to be ready
                            if (hasAudioFocus && playerInitialized.value) {
                                if (castConnectionHandler?.isCasting?.value != true) {
                                    player.play()
                                }
                            }
                        }
                    } else {
                        Timber.tag(TAG).w("Invalid media item index during AudioTrack recovery")
                        handleFinalFailure()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during AudioTrack error recovery")
                    handleFinalFailure()
                }
            }
    }

    /**
     * Handles Range Not Satisfiable (416) errors with strict recovery.
     * This error occurs when cached data doesn't match the actual stream size.
     */
    private fun handleRangeNotSatisfiableError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                performAggressiveCacheClear(mediaId)
                delay(RETRY_DELAY_MS)

                val currentIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition
                if (currentIndex == C.INDEX_UNSET) {
                    handleFinalFailure()
                    return@launch
                }
                // Resume from the same position — the resolver will fetch a fresh URL.
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after 416 error at position $currentPosition")
            }
    }

    /**
     * Handles "page needs to be reloaded" errors with strict recovery.
     * This requires clearing decryption caches and getting fresh stream URLs.
     */
    private fun handlePageReloadError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                Timber.tag(TAG).d("Handling page reload error for $mediaId")

                // Clear all caches including decryption caches
                performAggressiveCacheClear(mediaId)

                // Additional delay for page reload errors as they may be rate-limited
                delay(RETRY_DELAY_MS * 2)

                // Re-prepare the player
                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after page reload error")
            }
    }

    /**
     * Handles expired URL (403) errors by clearing caches and retrying.
     */
    private fun handleExpiredUrlError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        // Clear the cached URL
        songUrlCache.remove(mediaId)
        Timber.tag(TAG).d("Cleared cached URL for $mediaId")

        // Clear decryption caches
        try {
            YTPlayerUtils.forceRefreshForVideo(mediaId)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear decryption caches")
        }

        // A 403 can also mean the cipher produced a wrong-but-non-throwing signature from a
        // stale/wrong player config — invisible to the cipher's own exception-retry. Ask it to
        // re-fetch its config (rate-limited); if that corrects the table, PlayerConfigStore's
        // configEpoch advances and the cipher rebuilds its WebView on the next decipher, so the
        // retry below re-resolves the URL with the corrected recipe — no app restart needed.
        scope.launch {
            try {
                if (CipherDeobfuscator.onStreamRejected()) {
                    Timber.tag(TAG).d("Player config changed after stream rejection for $mediaId")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "onStreamRejected failed for $mediaId")
            }
        }

        retryJob?.cancel()
        retryJob =
            scope.launch {
                delay(RETRY_DELAY_MS)

                // Seek to current position to force URL re-resolution
                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after 403 error")
            }
    }

    /**
     * Handles generic IO errors with recovery attempt.
     */
    private fun handleGenericIOError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                performAggressiveCacheClear(mediaId)
                delay(RETRY_DELAY_MS)

                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after generic IO error")
            }
    }

    /**
     * Handles final failure when all recovery attempts have been exhausted.
     */
    private fun handleFinalFailure() {
        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            Timber.tag(TAG).d("All recovery attempts exhausted, auto-skipping to next track")
            skipOnError()
        } else {
            Timber.tag(TAG).d("All recovery attempts exhausted, stopping playback")
            stopOnError()
        }
    }

    override fun onDeviceVolumeChanged(
        volume: Int,
        muted: Boolean,
    ) {
        super.onDeviceVolumeChanged(volume, muted)
        val pauseOnMute = dataStore.get(PauseOnMute, false)

        if ((volume == 0 || muted) && pauseOnMute) {
            if (player.isPlaying) {
                wasPlayingBeforeVolumeMute = true
                isPausedByVolumeMute = true
                player.pause()
            }
        } else if (volume > 0 && !muted) {
            // Turning the volume back up never starts the music by itself: the user presses play.
            wasPlayingBeforeVolumeMute = false
            isPausedByVolumeMute = false
        }
    }

    /**
     * Pre-caches the next N tracks in the queue for offline playback.
     * Respects user preferences for track count and WiFi-only restriction.
     */
    private var warmUpJob: Job? = null
    private val warmUpSourceFactory by lazy { createDataSourceFactory() }

    /**
     * Gets the start of the next track ready while the current one plays, so it starts at once —
     * also when the connection is weak by then (the metro). It goes through the player's own data
     * source (same source lookup, same cache), and only once the current track has enough buffered
     * that it never competes with it for bandwidth. On Wi-Fi more of it is fetched.
     */
    private fun warmUpNextTrack() {
        warmUpJob?.cancel()
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val next = player.getMediaItemAt(nextIndex)
        val mediaId = next.mediaId
        if (mediaId.isBlank() || mediaId.startsWith("local:")) return
        val uri = next.localConfiguration?.uri ?: mediaId.toUri()
        val key = next.localConfiguration?.customCacheKey ?: mediaId
        warmUpJob = scope.launch(SilentHandler) {
            // Wait for the current track to be safely ahead (or fully loaded).
            val deadline = System.currentTimeMillis() + WARM_UP_MAX_WAIT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                val ahead = player.bufferedPosition - player.currentPosition
                if (player.bufferedPercentage >= 100 || ahead >= WARM_UP_AHEAD_MS) break
                delay(2_000)
            }
            if (!isActive || player.currentMediaItemIndex + 1 != nextIndex && player.nextMediaItemIndex != nextIndex) return@launch
            val bytes = if (connectivityManager.isActiveNetworkMetered) WARM_UP_BYTES_METERED else WARM_UP_BYTES_UNMETERED
            withContext(Dispatchers.IO) {
                val source = warmUpSourceFactory.createDataSource()
                runCatching {
                    source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(0).setLength(bytes).build())
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (isActive && total < bytes) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        total += read
                    }
                    Timber.tag(PRECACHE_TAG).d("[WARMUP] %s: %d bytes ready", mediaId, total)
                }.onFailure { Timber.tag(PRECACHE_TAG).d("[WARMUP] %s failed: %s", mediaId, it.message) }
                runCatching { source.close() }
            }
        }
    }

    private fun triggerPreCache() {
        preCacheJob?.cancel()

        val preCacheCount = dataStore.get(PreCacheTracksKey, 0)
        if (preCacheCount <= 0) {
            Timber.tag(PRECACHE_TAG).d("[PRECACHE] Skipped: pre-cache count is $preCacheCount (disabled)")
            return
        }
        if (!dataStore.get(EnableSongCacheKey, true)) {
            Timber.tag(PRECACHE_TAG).d("[PRECACHE] Skipped: song cache is disabled")
            return
        }

        val wifiOnly = dataStore.get(PreCacheOnlyWifiKey, true)
        val isMetered = connectivityManager.isActiveNetworkMetered
        Timber.tag(PRECACHE_TAG).d("[PRECACHE] Network: metered=$isMetered, wifiOnly=$wifiOnly")
        if (wifiOnly && isMetered) {
            Timber.tag(PRECACHE_TAG).d("[PRECACHE] Skipped: on metered network and WiFi-only is enabled")
            return
        }

        val currentIndex = player.currentMediaItemIndex
        val itemCount = player.mediaItemCount
        Timber.tag(PRECACHE_TAG).d("[PRECACHE] Queue: currentIndex=$currentIndex, totalItems=$itemCount, preCacheCount=$preCacheCount")
        if (currentIndex >= itemCount - 1) {
            Timber.tag(PRECACHE_TAG).d("[PRECACHE] Skipped: at end of queue")
            return
        }

        val tracksToCache = mutableListOf<Pair<String, String>>() // mediaId to title
        val endIndex = minOf(currentIndex + preCacheCount, itemCount - 1)

        for (i in (currentIndex + 1)..endIndex) {
            val mediaItem = player.getMediaItemAt(i)
            val mediaId = mediaItem.mediaMetadata.extras?.getString("mediaId")
                ?: mediaItem.mediaId
            val title = mediaItem.mediaMetadata.title?.toString() ?: "unknown"
            if (mediaId.startsWith("local:")) {
                Timber.tag(PRECACHE_TAG).d("[PRECACHE] Skip local track [$i]: $title ($mediaId)")
                continue
            }
            // Pre-cache fetches YouTube streams only; fallback-provider tracks resolve at play time.
            if (FallbackIds.isFallbackId(mediaId) || fallbackServing.containsKey(mediaId)) {
                Timber.tag(PRECACHE_TAG).d("[PRECACHE] Skip fallback-provider track [$i]: $title ($mediaId)")
                continue
            }
            // Skip if already fully cached in download or player cache
            val inDownload = downloadCache.isCached(mediaId, 0, CHUNK_LENGTH)
            val inPlayer = playerCache.isCached(mediaId, 0, CHUNK_LENGTH)
            if (inDownload || inPlayer) {
                Timber.tag(PRECACHE_TAG).d("[PRECACHE] Already cached [$i]: $title ($mediaId) download=$inDownload player=$inPlayer")
                continue
            }
            Timber.tag(PRECACHE_TAG).d("[PRECACHE] Will cache [$i]: $title ($mediaId)")
            tracksToCache.add(mediaId to title)
        }

        if (tracksToCache.isEmpty()) {
            Timber.tag(PRECACHE_TAG).d("[PRECACHE] Nothing to cache — all upcoming tracks already cached")
            return
        }

        Timber.tag(PRECACHE_TAG).i("[PRECACHE] Starting pre-cache job for ${tracksToCache.size} tracks")

        preCacheJob = scope.launch(Dispatchers.IO + SilentHandler) {
            for ((index, pair) in tracksToCache.withIndex()) {
                val (mediaId, title) = pair
                if (!isActive) {
                    Timber.tag(PRECACHE_TAG).d("[PRECACHE] Job cancelled, stopping at track $index")
                    break
                }
                // Re-check network before each track
                if (wifiOnly && connectivityManager.isActiveNetworkMetered) {
                    Timber.tag(PRECACHE_TAG).d("[PRECACHE] Network became metered, stopping at track $index")
                    break
                }

                try {
                    Timber.tag(PRECACHE_TAG).d("[PRECACHE] [$index/${tracksToCache.size}] Resolving stream for: $title ($mediaId)")

                    // Check if URL is already cached
                    val cachedUrl = songUrlCache[mediaId]
                        ?.takeIf { it.second > System.currentTimeMillis() }

                    val streamUrl: String
                    val contentLength: Long

                    if (cachedUrl != null) {
                        streamUrl = cachedUrl.first
                        contentLength = database.format(mediaId).first()?.contentLength ?: C.LENGTH_UNSET.toLong()
                        Timber.tag(PRECACHE_TAG).d("[PRECACHE] Using cached URL for $mediaId, contentLength=$contentLength")
                    } else {
                        Timber.tag(PRECACHE_TAG).d("[PRECACHE] Fetching fresh stream URL for: $title ($mediaId)")
                        val playbackData = YTPlayerUtils.playerResponseForPlayback(
                            mediaId,
                            audioQuality = audioQuality,
                            connectivityManager = connectivityManager,
                        ).getOrNull()
                        if (playbackData == null) {
                            Timber.tag(PRECACHE_TAG).w("[PRECACHE] Failed to get playback data for: $title ($mediaId)")
                            continue
                        }

                        streamUrl = playbackData.streamUrl
                        contentLength = playbackData.format.contentLength ?: C.LENGTH_UNSET.toLong()

                        songUrlCache[mediaId] =
                            streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
                        Timber.tag(PRECACHE_TAG).d("[PRECACHE] Got stream URL for $mediaId: contentLength=$contentLength, expires in ${playbackData.streamExpiresInSeconds}s")
                    }

                    // Build a CacheDataSource that writes into playerCache
                    val cacheDataSource = CacheDataSource(
                        playerCache,
                        DefaultDataSource.Factory(
                            this@MusicService,
                            OkHttpDataSource.Factory(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    .proxyAuthenticator { _, response ->
                                        YouTube.proxyAuth?.let { auth ->
                                            response.request
                                                .newBuilder()
                                                .header("Proxy-Authorization", auth)
                                                .build()
                                        } ?: response.request
                                    }.build(),
                            ),
                        ).createDataSource(),
                    )

                    val dataSpec = DataSpec.Builder()
                        .setUri(streamUrl.toUri())
                        .setKey(mediaId)
                        .setLength(if (contentLength > 0) contentLength else C.LENGTH_UNSET.toLong())
                        .build()

                    val startTime = System.currentTimeMillis()
                    Timber.tag(PRECACHE_TAG).d("[PRECACHE] Starting download for: $title ($mediaId), length=${if (contentLength > 0) "${contentLength / 1024}KB" else "unknown"}")

                    // CacheWriter downloads the entire stream into playerCache
                    val cacheWriter = CacheWriter(
                        cacheDataSource,
                        dataSpec,
                        null, // temporary buffer
                        null, // no progress listener
                    )
                    // Cancel the CacheWriter when coroutine is cancelled
                    coroutineContext[Job]?.invokeOnCompletion { cacheWriter.cancel() }
                    cacheWriter.cache()

                    val elapsed = System.currentTimeMillis() - startTime
                    val cachedBytes = tryOrNull { playerCache.getCachedBytes(mediaId, 0, contentLength) } ?: 0
                    Timber.tag(PRECACHE_TAG).i("[PRECACHE] ✓ Cached: $title ($mediaId) in ${elapsed}ms, cached=${cachedBytes / 1024}KB")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.tag(PRECACHE_TAG).e(e, "[PRECACHE] ✗ Failed to pre-cache: $title ($mediaId)")
                }
            }
            Timber.tag(PRECACHE_TAG).i("[PRECACHE] Pre-cache job completed")
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            // meldhls:// streams (HLS-only providers such as SoundCloud) are served
                            // as one progressive stream; every other uri goes straight to OkHttp.
                            OkHttpClient
                                .Builder()
                                .proxy(YouTube.proxy)
                                .proxyAuthenticator { _, response ->
                                    YouTube.proxyAuth?.let { auth ->
                                        response.request
                                            .newBuilder()
                                            .header("Proxy-Authorization", auth)
                                            .build()
                                    } ?: response.request
                                }.build()
                                .let { client ->
                                    HlsConcatDataSource.Factory(OkHttpDataSource.Factory(client), client)
                                },
                        ),
                    ),
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    // Flag to prevent queue saving during silence skip operations
    private var isSilenceSkipping = false

    private fun handleLongSilenceDetected() {
        if (!instantSilenceSkipEnabled.value) return
        if (silenceSkipJob?.isActive == true) return

        silenceSkipJob =
            scope.launch {
                // Debounce so short fades or transitions do not trigger a jump.
                delay(200)
                performInstantSilenceSkip()
            }
    }

    private suspend fun performInstantSilenceSkip() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (duration <= INSTANT_SILENCE_SKIP_STEP_MS) return

        isSilenceSkipping = true
        try {
            var hops = 0
            val silenceProcessor = playerSilenceProcessors[player] ?: return
            while (coroutineContext.isActive && instantSilenceSkipEnabled.value && silenceProcessor.isCurrentlySilent()) {
                val current = player.currentPosition
                val target = (current + INSTANT_SILENCE_SKIP_STEP_MS).coerceAtMost(duration - 500)

                if (target <= current) break

                // Reset silence tracking before seeking to prevent immediate re-trigger
                silenceProcessor.resetTracking()
                player.seekTo(target)
                hops++

                if (hops >= 80 || target >= duration - 500) break

                delay(INSTANT_SILENCE_SKIP_SETTLE_MS)
            }
            if (hops > 0) {
                Timber.tag(TAG).d("Silence skip: jumped $hops times")
            }
        } finally {
            isSilenceSkipping = false
        }
    }

    private fun updateDiscordRPC(
        song: Song,
        showFeedback: Boolean = false,
    ) {
        val useDetails = dataStore.get(DiscordUseDetailsKey, false)
        val advancedMode = dataStore.get(DiscordAdvancedModeKey, false)

        val status = if (advancedMode) dataStore.get(DiscordStatusKey, "online") else "online"
        val b1Text = if (advancedMode) dataStore.get(DiscordButton1TextKey, "") else ""
        val b1Visible = if (advancedMode) dataStore.get(DiscordButton1VisibleKey, true) else true
        val b1Url = if (advancedMode) dataStore.get(DiscordButton1UrlKey, "") else ""
        val b2Text = if (advancedMode) dataStore.get(DiscordButton2TextKey, "") else ""
        val b2Visible = if (advancedMode) dataStore.get(DiscordButton2VisibleKey, true) else true
        val b2Url = if (advancedMode) dataStore.get(DiscordButton2UrlKey, "") else ""
        val activityType = if (advancedMode) dataStore.get(DiscordActivityTypeKey, "listening") else "listening"
        val activityName = if (advancedMode) dataStore.get(DiscordActivityNameKey, "") else ""

        discordUpdateJob?.cancel()
        discordUpdateJob =
            scope.launch {
                discordRpc
                    ?.updateSong(
                        song,
                        player.currentPosition,
                        player.playbackParameters.speed,
                        useDetails,
                        status,
                        b1Text,
                        b1Visible,
                        b1Url,
                        b2Text,
                        b2Visible,
                        b2Url,
                        activityType,
                        activityName,
                    )?.onFailure {
                        // Rate limited or error
                        if (showFeedback) {
                            Handler(Looper.getMainLooper()).post {
                                Toast
                                    .makeText(
                                        this@MusicService,
                                        "Discord RPC update failed: ${it.message}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    }
            }
    }

    private fun applyQobuzEndpointOverrides() {
        QobuzAudioProvider.configureEndpoints(
            squid = dataStore.get(QobuzSquidEndpointKey, ""),
            kenny = dataStore.get(QobuzKennyEndpointKey, ""),
            trypt = dataStore.get(QobuzTryptEndpointKey, ""),
            jumo = dataStore.get(QobuzJumoEndpointKey, ""),
        )
    }

    private fun qobuzCacheKey(mediaId: String, qualityCode: Int) =
        "qobuz:$qualityCode:$mediaId"

    private fun stripQobuzCacheKeyPrefix(key: String): String {
        if (key.startsWith(RESCUE_CACHE_PREFIX)) return key.removePrefix(RESCUE_CACHE_PREFIX)
        if (!key.startsWith("qobuz:")) return key
        val parts = key.split(":", limit = 3)
        return if (parts.size == 3) parts[2] else key
    }

    /** What currently serves a fallback / rescued media id, so a failure can switch upload or provider. */
    private data class FallbackServing(val provider: AudioProviderId, val trackId: String)

    private val fallbackServing = java.util.concurrent.ConcurrentHashMap<String, FallbackServing>()

    /** Providers / uploads that failed for a media id recently, skipped by the next resolution. */
    private data class FallbackFailures(
        val providers: Set<AudioProviderId> = emptySet(),
        val tracks: Set<String> = emptySet(),
        val at: Long = System.currentTimeMillis(),
    )

    private val fallbackFailures = java.util.concurrent.ConcurrentHashMap<String, FallbackFailures>()

    private fun currentFailures(mediaId: String): FallbackFailures =
        fallbackFailures[mediaId]?.takeIf { System.currentTimeMillis() - it.at < FALLBACK_FAILED_TTL_MS } ?: FallbackFailures()

    private fun markFallbackFailed(mediaId: String, provider: AudioProviderId? = null, trackId: String? = null) {
        val previous = currentFailures(mediaId)
        fallbackFailures[mediaId] = FallbackFailures(
            providers = previous.providers + listOfNotNull(provider),
            tracks = previous.tracks + listOfNotNull(trackId),
        )
        fallbackServing.remove(mediaId)
    }

    /** Network-level failures mean "YouTube unreachable"; anything else is about this particular video. */
    private fun isNetworkFailure(error: Throwable?): Boolean {
        var e = error
        while (e != null) {
            if (e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.io.InterruptedIOException || e is javax.net.ssl.SSLException
            ) {
                return true
            }
            e = e.cause
        }
        return false
    }

    /**
     * Resolves audio for [mediaId] through the audio fallback engine.
     *
     * - Fallback ids (`mfb:`): the cache key stays the media id itself.
     * - [youtubeRescue] for a regular YouTube id: the failed video is excluded (or all of YouTube, when
     *   [youtubeError] is a network failure or YouTube is bypassed) and the rescued audio is cached under
     *   a separate key so it never mixes with (partial) YouTube bytes of the same id.
     *
     * Returns null when no enabled provider can serve the track.
     */
    private fun resolveFallbackDataSpec(
        dataSpec: DataSpec,
        mediaId: String,
        bypassCache: Boolean,
        youtubeRescue: Boolean,
        youtubeError: Throwable? = null,
        depth: Int = 0,
    ): DataSpec? {
        val cacheKey = if (youtubeRescue) RESCUE_CACHE_PREFIX + mediaId else mediaId
        val usePlayerCache = dataStore.get(EnableSongCacheKey, true)
        if (!bypassCache && (
                downloadCache.isCached(cacheKey, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1) ||
                    (usePlayerCache && playerCache.isCached(cacheKey, dataSpec.position, CHUNK_LENGTH))
                )
        ) {
            return dataSpec.buildUpon().setKey(cacheKey).build()
        }
        songUrlCache[cacheKey]?.takeIf { it.second > System.currentTimeMillis() }?.let { (url, _) ->
            val spec = dataSpec.buildUpon().setUri(url.toUri()).setKey(cacheKey).build()
            return if (fallbackServing[mediaId]?.provider == AudioProviderId.YOUTUBE) spec.subrange(0, CHUNK_LENGTH) else spec
        }
        if (depth > 4) return null

        val dbSong = runBlocking(Dispatchers.IO) { database.getSongById(mediaId) }
        val query = AudioFallbackEngine.queryFor(mediaId, dbSong)
        if (youtubeRescue && query == null) {
            AudioDiagnostics.warn("YouTube failed for $mediaId and it has no catalog metadata to search elsewhere")
            if (youtubeError != null) {
                if (isNetworkFailure(youtubeError)) AudioDiagnostics.recordYouTubeUnreachable(mediaId)
                else AudioDiagnostics.recordYouTubeRestricted(mediaId)
            }
            AudioDiagnostics.recordFailure(mediaId, AudioDiagnostics.FailureKind.NO_METADATA)
            return null
        }
        if (youtubeRescue && depth == 0 && (youtubeError != null || !fallbackServing.containsKey(mediaId))) {
            if (youtubeError != null && !isNetworkFailure(youtubeError)) {
                AudioDiagnostics.warn("YouTube can't play $mediaId (${youtubeError.message?.take(160)}) — trying another upload/source")
                AudioDiagnostics.recordYouTubeRestricted(mediaId)
                markFallbackFailed(mediaId, trackId = mediaId)
                AudioFallbackEngine.markYouTubeVideoUnplayable(mediaId, query?.catalogId)
            } else {
                if (youtubeError != null) AudioDiagnostics.recordYouTubeUnreachable(mediaId)
                markFallbackFailed(mediaId, provider = AudioProviderId.YOUTUBE)
            }
        }
        val failures = currentFailures(mediaId)

        val plan = runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeout(FALLBACK_RESOLVE_TIMEOUT_MS) {
                    AudioFallbackEngine.streamPlan(mediaId, dbSong, failures.providers, failures.tracks)
                }
            }
        }.onFailure { AudioDiagnostics.warn("audio resolve for $mediaId failed: ${it.message}") }
            .getOrNull() ?: return null

        return when (plan) {
            is AudioFallbackEngine.StreamPlan.Direct -> {
                // A recommended track is not let in below RecommendedTracks.MIN_KBPS: another
                // source first, otherwise it is skipped.
                if (RecommendedTracks.tooLow(mediaId, plan.stream.bitrate)) {
                    AudioDiagnostics.info(
                        "recommended $mediaId: ${plan.provider} is only ${plan.stream.bitrate / 1000} kbps — trying another source",
                    )
                    markFallbackFailed(mediaId, provider = plan.provider)
                    resolveFallbackDataSpec(dataSpec, mediaId, bypassCache, youtubeRescue, youtubeError, depth + 1)
                        ?.let { return it }
                    scope.launch { dropRecommendation(mediaId) }
                }
                fallbackServing[mediaId] = FallbackServing(plan.provider, plan.trackId)
                com.metrolist.music.resolver.NowServing.set(mediaId, plan.provider)
                val stream = plan.stream
                val streamFormat = FormatEntity(
                    id = mediaId,
                    itag = -1,
                    mimeType = stream.mimeType,
                    codecs = stream.codecs,
                    bitrate = stream.bitrate,
                    sampleRate = stream.sampleRate,
                    contentLength = stream.contentLength ?: 0L,
                    loudnessDb = null,
                    perceptualLoudnessDb = null,
                    playbackUrl = null,
                )
                database.query { upsert(streamFormat) }
                // A stream whose service doesn't state its bitrate: the real one, once measured.
                com.metrolist.music.playback.datasource.HlsConcatDataSource.playlistUrlOf(stream.uri)?.let { key ->
                    com.metrolist.music.playback.datasource.MeasuredAudio.whenMeasured(key) { measured ->
                        // A measurement of a version that was swapped out meanwhile (VK 63 kbps still
                        // finishing after Bandcamp was picked) must not overwrite the new one's quality.
                        if (fallbackServing[mediaId]?.trackId != plan.trackId) return@whenMeasured
                        if (RecommendedTracks.tooLow(mediaId, measured.bitrate)) {
                            scope.launch { rejectLowRecommendation(mediaId, plan.provider) }
                        }
                        database.query {
                            upsert(
                                streamFormat.copy(
                                    bitrate = measured.bitrate,
                                    sampleRate = measured.sampleRate ?: streamFormat.sampleRate,
                                ),
                            )
                        }
                    }
                }
                songUrlCache[cacheKey] = stream.uri to (stream.expiresAtMs - 60_000L)
                AudioDiagnostics.info("playing $mediaId via ${plan.provider} (${plan.trackId})")
                dataSpec.buildUpon().setUri(stream.uri.toUri()).setKey(cacheKey).build()
            }

            is AudioFallbackEngine.StreamPlan.YouTube -> {
                val playback = runBlocking(Dispatchers.IO) {
                    runCatching {
                        withTimeout(30_000L) {
                            YTPlayerUtils.playerResponseForPlayback(
                                plan.videoId,
                                audioQuality = audioQuality,
                                connectivityManager = connectivityManager,
                            ).getOrThrow()
                        }
                    }
                }
                val data = playback.getOrElse { error ->
                    if (isNetworkFailure(error)) {
                        AudioDiagnostics.warn("YouTube unreachable for ${plan.videoId}: ${error.message?.take(160)}")
                        AudioDiagnostics.recordYouTubeUnreachable(mediaId)
                        markFallbackFailed(mediaId, provider = AudioProviderId.YOUTUBE)
                    } else {
                        AudioDiagnostics.warn("YouTube video ${plan.videoId} can't play: ${error.message?.take(160)}")
                        AudioDiagnostics.recordYouTubeRestricted(mediaId)
                        markFallbackFailed(mediaId, trackId = plan.videoId)
                        AudioFallbackEngine.markYouTubeVideoUnplayable(plan.videoId, query?.catalogId)
                    }
                    return resolveFallbackDataSpec(dataSpec, mediaId, bypassCache, youtubeRescue, depth = depth + 1)
                }
                fallbackServing[mediaId] = FallbackServing(AudioProviderId.YOUTUBE, plan.videoId)
                com.metrolist.music.resolver.NowServing.set(mediaId, AudioProviderId.YOUTUBE)
                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = data.format.itag,
                            mimeType = data.format.mimeType.split(";")[0],
                            codecs = data.format.mimeType.substringAfter("codecs=", "").removeSurrounding("\""),
                            bitrate = data.format.bitrate,
                            sampleRate = data.format.audioSampleRate,
                            contentLength = data.format.contentLength ?: 0L,
                            loudnessDb = data.audioConfig?.loudnessDb,
                            perceptualLoudnessDb = data.audioConfig?.perceptualLoudnessDb,
                            playbackUrl = null,
                        ),
                    )
                }
                songUrlCache[cacheKey] =
                    data.streamUrl to System.currentTimeMillis() + (data.streamExpiresInSeconds * 1000L)
                AudioDiagnostics.info("playing $mediaId via YOUTUBE (${plan.videoId})")
                dataSpec.buildUpon().setUri(data.streamUrl.toUri()).setKey(cacheKey).build()
                    .subrange(0, CHUNK_LENGTH)
            }
        }
    }

    /**
     * Circuit breaker for networks where YouTube is blocked (e.g. without a VPN): after a few
     * network-level YouTube failures in a short window, catalog tracks go straight to the other
     * providers for a while instead of paying YouTube's timeout on every track.
     */
    /**
     * The error to raise for a track no source could serve. With no working internet (the metro:
     * a network is up but carries nothing) that is not the track's fault: every "failure" recorded
     * on the way is forgotten, and the player is told it is a connection problem, so it waits for
     * the connection and tries again instead of giving the track up.
     */
    private fun offlineOr(mediaId: String, otherwise: DataSourceException?): DataSourceException? {
        val online = runCatching { runBlocking(Dispatchers.IO) { InternetProbe.isOnline() } }.getOrDefault(true)
        if (online) return otherwise
        forgetFailuresForOutage(mediaId)
        return DataSourceException(
            getString(R.string.error_no_internet),
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
    }

    /** Failures seen while offline say nothing about the track or the sources: try them all again. */
    private fun forgetFailuresForOutage(mediaId: String?) {
        mediaId?.let { fallbackFailures.remove(it) }
        youtubeUnavailableUntilMs = 0L
        recentYouTubeRescues.clear()
    }

    @Volatile
    private var youtubeUnavailableUntilMs = 0L
    private val recentYouTubeRescues = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    private fun noteYouTubeRescue(error: Throwable) {
        if (!isNetworkFailure(error)) return
        val now = System.currentTimeMillis()
        recentYouTubeRescues.addLast(now)
        while ((recentYouTubeRescues.peekFirst() ?: now) < now - YOUTUBE_RESCUE_WINDOW_MS) {
            recentYouTubeRescues.pollFirst()
        }
        if (recentYouTubeRescues.size >= YOUTUBE_RESCUES_TO_TRIP) {
            youtubeUnavailableUntilMs = now + YOUTUBE_BYPASS_MS
            recentYouTubeRescues.clear()
            AudioDiagnostics.warn("YouTube looks unreachable — other sources first for ${YOUTUBE_BYPASS_MS / 60_000} min")
        }
    }

    private fun buildQobuzQuery(
        mediaId: String,
        spotifyTrack: SpotifyTrack?,
        dbSong: Song?,
        quality: QobuzAudioQuality,
    ): QobuzAudioProvider.Query? {
        // Prefer Spotify metadata (cleaner titles + ISRC). Fall back to DB Song
        // (title/artist/album/duration from the YT match). If both missing, skip.
        val title = spotifyTrack?.name
            ?: dbSong?.song?.title
            ?: return null
        val artists = spotifyTrack?.artists?.map { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: dbSong?.artists?.map { it.name }?.takeIf { it.isNotEmpty() }
            ?: emptyList()
        if (artists.isEmpty()) return null
        val album = spotifyTrack?.album?.name
            ?: dbSong?.song?.albumName
            ?: dbSong?.album?.title
        val durationMs = spotifyTrack?.durationMs?.takeIf { it > 0 }?.toLong()
            ?: dbSong?.song?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)
        val isrc = spotifyTrack?.isrc?.takeIf { it.isNotBlank() }
            ?: dbSong?.song?.isrc?.takeIf { it.isNotBlank() }

        val backendPref = dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.MONOKENNY)
        val country = dataStore.get(QobuzCountryKey, "US")
            .trim()
            .uppercase()
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"
        val resolverBackend = when (backendPref) {
            QobuzBackend.MONOKENNY -> QobuzAudioProvider.ResolverBackend.MONOKENNY
            QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
            QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
            QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
        }
        return QobuzAudioProvider.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            isrc = isrc,
            durationMs = durationMs,
            countryCode = country,
            backend = resolverBackend,
            qualityCode = QobuzAudioProvider.qualityCodeFor(quality),
        )
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = stripQobuzCacheKeyPrefix(dataSpec.key ?: error("No media id"))

            // Handle local audio files — resolve to content URI and bypass YouTube fetch
            if (mediaId.startsWith("local:")) {
                val localPath = runBlocking(Dispatchers.IO) {
                    database.getSongById(mediaId)?.song?.localPath
                }
                if (localPath != null) {
                    return@Factory dataSpec.withUri(localPath.toUri())
                }
                // fallback: URI already set to localPath in MediaItem
                return@Factory dataSpec
            }


            // Check if we need to bypass cache for quality change
            val shouldBypassCache = bypassCacheForQualityChange.contains(mediaId)

            // A fallback id ("mfb:" / legacy "qbzfb:") is a catalog track whose audio comes from a
            // non-YouTube provider (Qobuz / VK / SoundCloud). It is not a video id: resolve it only
            // through the audio fallback engine, which also switches provider when one fails.
            if (FallbackIds.isFallbackId(mediaId)) {
                return@Factory resolveFallbackDataSpec(dataSpec, mediaId, shouldBypassCache, youtubeRescue = false)
                    ?: throw offlineOr(mediaId, null) ?: DataSourceException(
                        getString(R.string.error_no_audio_source),
                        null,
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    )
            }

            // A YouTube id that an earlier open already had to rescue from another provider (YouTube
            // unreachable / removed): keep serving the rescue instead of re-trying YouTube per chunk.
            // The same applies when the user switched YouTube off entirely.
            if (fallbackServing.containsKey(mediaId) || !ResolverPreferences.youtubeEnabled ||
                System.currentTimeMillis() < youtubeUnavailableUntilMs
            ) {
                resolveFallbackDataSpec(dataSpec, mediaId, shouldBypassCache, youtubeRescue = true)
                    ?.let { return@Factory it }
            }

            // Qobuz lossless attempt: when the toggle is on, try Qobuz. Uses Spotify metadata (with
            // ISRC) when available — registered by SpotifyYouTubeMapper for catalog tracks —
            // otherwise falls back to DB title/artist/album for YT-native tracks. Silently falls
            // through to the YouTube path on any failure.
            // Qobuz is retired (its community servers are gone): never attempted any more.
            val qobuzEnabled = false
            if (qobuzEnabled) {
                val qobuzQualityEnum = dataStore.get(QobuzAudioQualityKey)
                    .toEnum(QobuzAudioQuality.CD_QUALITY)
                val qualityCode = QobuzAudioProvider.qualityCodeFor(qobuzQualityEnum)
                val qobuzKey = qobuzCacheKey(mediaId, qualityCode)
                val usePlayerCache = dataStore.get(EnableSongCacheKey, true)

                if (!shouldBypassCache &&
                    (downloadCache.isCached(qobuzKey, dataSpec.position,
                        if (dataSpec.length >= 0) dataSpec.length else 1) ||
                        (usePlayerCache && playerCache.isCached(qobuzKey, dataSpec.position, CHUNK_LENGTH)))
                ) {
                    return@Factory dataSpec.buildUpon().setKey(qobuzKey).build()
                }

                val spotifyTrack = SpotifyMetadataRegistry.get(mediaId)
                // Always load the DB Song so we can pick up a persisted ISRC and
                // a persisted Qobuz match for this mediaId.
                val dbSong = runBlocking(Dispatchers.IO) { database.getSongById(mediaId) }
                val qobuzQuery = buildQobuzQuery(mediaId, spotifyTrack, dbSong, qobuzQualityEnum)

                Timber.tag("Qobuz").d(
                    "attempt ▶ %s | spotifyMeta=%b dbSong=%b query=%b quality=%s",
                    mediaId, spotifyTrack != null, dbSong != null, qobuzQuery != null, qobuzQualityEnum.name,
                )
                if (qobuzQuery == null) {
                    Timber.tag("Qobuz").d(
                        "skip %s: no query built (missing title/artist metadata)", mediaId,
                    )
                }

                if (qobuzQuery != null) {
                    // Manual override wins over auto-match: when the user has
                    // pinned a Qobuz track ID for this mediaId, swap the query's
                    // mediaId for "qobuz:track:<id>" so resolve() takes the
                    // direct-ID short-circuit and skips search entirely.
                    val manualOverride = runBlocking(Dispatchers.IO) {
                        QobuzMatchOverrides
                            .decode(dataStore.get(QobuzMatchOverridesKey, ""))[mediaId]
                    }
                    // Prime the in-memory match cache from the persisted match — this
                    // skips the Qobuz search step entirely on repeat plays.
                    val savedMatch = runBlocking(Dispatchers.IO) {
                        database.getQobuzMatch(mediaId)
                    }
                    // Negative cache short-circuit: skip the Qobuz cascade entirely
                    // for tracks we've recently failed to match. Without this every
                    // play of a non-Qobuz track burns the full search budget on the
                    // loader thread before YouTube is attempted. Skipped when the
                    // user has manually pinned a match or we have a saved match
                    // (those failures are likely transient backend errors, not
                    // catalog misses).
                    val negativeMissDeadline = qobuzMissUntilMs[mediaId]
                    val skipQobuzForMiss = manualOverride == null && savedMatch == null &&
                        negativeMissDeadline != null && negativeMissDeadline > System.currentTimeMillis()
                    if (skipQobuzForMiss) {
                        val remainingMin = ((negativeMissDeadline ?: 0L) -
                            System.currentTimeMillis()) / 60_000
                        Timber.tag("MusicService").d(
                            "Skipping Qobuz cascade for $mediaId (negative cache valid for ${remainingMin}m)"
                        )
                    }
                    // If saved tier says track is CD-only (hires=false), downgrade
                    // requested quality to CD code 6. Otherwise asking for code 27
                    // (Hi-Res) returns preview and wastes the ladder cascade.
                    var effectiveQuery = qobuzQuery
                    val overrideHiresCdGuard = manualOverride?.let { !it.hires } ?: false
                    if ((savedMatch != null && !savedMatch.hires || overrideHiresCdGuard) &&
                        qobuzQuery.qualityCode > 6) {
                        effectiveQuery = effectiveQuery.copy(qualityCode = 6)
                    }
                    if (manualOverride != null) {
                        effectiveQuery = effectiveQuery.copy(mediaId = manualOverride.providerMediaId())
                    }
                    if (savedMatch != null && manualOverride == null) {
                        QobuzAudioProvider.primeKnownTrack(
                            query = effectiveQuery,
                            trackId = savedMatch.qobuzTrackId,
                            hires = savedMatch.hires,
                            bitDepth = savedMatch.bitDepth,
                            samplingRateKhz = savedMatch.samplingRateKhz,
                            isrc = effectiveQuery.isrc,
                        )
                    }

                    // Tighter primary timeout (was 15s). 10s is well above the
                    // observed P95 for a real Qobuz match and halves the perceived
                    // "loading" hang for the common "track is not on Qobuz" case.
                    var qobuzResolved = if (skipQobuzForMiss) {
                        null
                    } else {
                        runCatching {
                            runBlocking(Dispatchers.IO) {
                                withTimeout(10_000L) {
                                    QobuzAudioProvider.resolve(effectiveQuery)
                                }
                            }
                        }.onFailure { e ->
                            val reason = if (e is TimeoutCancellationException) {
                                "timed out after 10s"
                            } else {
                                e.message ?: e.javaClass.simpleName
                            }
                            Timber.tag("Qobuz").d(
                                "primary ✘ %s via %s: %s", mediaId, effectiveQuery.backend.name, reason,
                            )
                        }.getOrNull()
                    }

                    // Cross-backend fallback: cycle through every other backend
                    // before giving up to YouTube. Order: primary → others in enum order.
                    // Only attempt alt backends when we know the track exists on
                    // Qobuz (saved match or manual override) — otherwise the alt
                    // backends are just additional mirrors of the same catalog and
                    // burning ~30s on misses delays the YouTube fallback for no win.
                    val knownOnQobuz = manualOverride != null || savedMatch != null
                    if (qobuzResolved == null && knownOnQobuz && !skipQobuzForMiss) {
                        val altBackends = QobuzAudioProvider.ResolverBackend.entries
                            .filter { it != effectiveQuery.backend }
                            .take(2) // cap the cascade
                        Timber.tag("Qobuz").d(
                            "alt ▶ %s: primary failed but track known on Qobuz, trying %s",
                            mediaId, altBackends.joinToString { it.name },
                        )
                        for (altBackend in altBackends) {
                            val altQuery = effectiveQuery.copy(backend = altBackend)
                            qobuzResolved = runCatching {
                                runBlocking(Dispatchers.IO) {
                                    withTimeout(5_000L) {
                                        QobuzAudioProvider.resolve(altQuery)
                                    }
                                }
                            }.onFailure { e ->
                                val reason = if (e is TimeoutCancellationException) {
                                    "timed out after 5s"
                                } else {
                                    e.message ?: e.javaClass.simpleName
                                }
                                Timber.tag("Qobuz").d(
                                    "alt ✘ %s via %s: %s", mediaId, altBackend.name, reason,
                                )
                            }.getOrNull()
                            if (qobuzResolved != null) break
                        }
                    }

                    // Persist the miss so the next play of this track skips the
                    // cascade entirely. Don't pollute the negative cache when we
                    // already have a known match (those failures are transient).
                    if (qobuzResolved == null && !knownOnQobuz) {
                        qobuzMissUntilMs[mediaId] = System.currentTimeMillis() + QOBUZ_MISS_TTL_MS
                        Timber.tag("Qobuz").d(
                            "negative-cache set for %s (%dm)", mediaId, QOBUZ_MISS_TTL_MS / 60_000,
                        )
                    }

                    if (qobuzResolved != null) {
                        Timber.tag("Qobuz").i(
                            "resolved ✔ %s → %s (%dbps, trackId=%s)",
                            mediaId, qobuzResolved.label, qobuzResolved.bitrate, qobuzResolved.trackId,
                        )
                        // Persist the match + any newly-discovered ISRC so the next
                        // play of this track is a deterministic, search-free hit.
                        val resolvedIsrc = qobuzResolved.isrc?.takeIf { it.isNotBlank() }
                            ?: spotifyTrack?.isrc?.takeIf { it.isNotBlank() }
                        val previousIsrc = dbSong?.song?.isrc?.takeIf { it.isNotBlank() }
                        scope.launch(Dispatchers.IO) {
                            database.query {
                                upsertQobuzMatch(
                                    QobuzMatchEntity(
                                        youtubeId = mediaId,
                                        qobuzTrackId = qobuzResolved.trackId,
                                        hires = qobuzResolved.hires,
                                        bitDepth = qobuzResolved.bitDepth,
                                        samplingRateKhz = qobuzResolved.samplingRateKhz,
                                    ),
                                )
                                if (resolvedIsrc != null && resolvedIsrc != previousIsrc) {
                                    setSongIsrc(mediaId, resolvedIsrc)
                                }
                            }
                        }
                        return@Factory dataSpec
                            .buildUpon()
                            .setUri(qobuzResolved.mediaUri.toUri())
                            .setKey(qobuzKey)
                            .build()
                    }
                }

                // Reached only when Qobuz produced no playable stream — every
                // return@Factory above is on the success path.
                Timber.tag("Qobuz").d("fallback → YouTube for %s (Qobuz did not resolve)", mediaId)
            }

            if (!shouldBypassCache) {
                val usePlayerCache = dataStore.get(EnableSongCacheKey, true)
                if (downloadCache.isCached(
                        mediaId,
                        dataSpec.position,
                        if (dataSpec.length >= 0) dataSpec.length else 1,
                    ) ||
                    (usePlayerCache && playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH))
                ) {
                    scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                    return@Factory dataSpec
                }

                songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                    scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                    Fix403.i(
                        Fix403.nextId("cache"), "urlCache.hit",
                        Fix403.kv(
                            "mediaId" to mediaId,
                            "expiresInMs" to (it.second - System.currentTimeMillis()),
                            "position" to dataSpec.position,
                            "length" to dataSpec.length,
                        ),
                    )
                    /*
                     * The .subrange() is NOT optional and must match the fresh-resolution path
                     * below. ExoPlayer opens a track with position=0 / length=LENGTH_UNSET, and
                     * media3's HttpUtil.buildRangeRequestHeader returns null for exactly that
                     * pair — so without a subrange the media GET carries no Range header at all.
                     * googlevideo answers a Range-less request with 403 on every YouTube Music
                     * art track (`- Topic` uploads, i.e. nearly everything played here) while
                     * serving the ranged request with 206.
                     *
                     * That asymmetry made playback fail only for songs whose URL was already in
                     * songUrlCache — first play resolved fresh and worked, replaying or skipping
                     * back to it took this branch and 403'd.
                     */
                    return@Factory dataSpec.withUri(it.first.toUri()).subrange(0, CHUNK_LENGTH)
                }
            } else {
                Timber.tag("MusicService").i("BYPASSING CACHE for $mediaId due to quality change")
            }

            Timber.tag("MusicService").i("FETCHING STREAM: $mediaId | quality=$audioQuality")
            val playbackData =
                runBlocking(Dispatchers.IO) {
                    // Hard cap on stream resolution so a stuck YouTube/PoToken/NewPipe
                    // call can't keep a song "loading" forever. ExoPlayer surfaces the
                    // PlaybackException to the user as a skip-able error.
                    try {
                        withTimeout(30_000L) {
                            YTPlayerUtils.playerResponseForPlayback(
                                mediaId,
                                audioQuality = audioQuality,
                                connectivityManager = connectivityManager,
                            )
                        }
                    } catch (e: TimeoutCancellationException) {
                        Result.failure(java.net.SocketTimeoutException("Stream resolution timed out"))
                    }
                }.getOrElse { throwable ->
                    // YouTube could not serve this track (blocked network, removed/region-locked
                    // video, …). For a catalog track, rescue it from another enabled audio provider
                    // before surfacing the error.
                    if (throwable !is kotlinx.coroutines.CancellationException) {
                        noteYouTubeRescue(throwable)
                        resolveFallbackDataSpec(dataSpec, mediaId, shouldBypassCache, youtubeRescue = true, youtubeError = throwable)
                            ?.let { return@Factory it }
                        if (isNetworkFailure(throwable) || throwable is java.net.SocketTimeoutException) {
                            offlineOr(mediaId, null)?.let { throw it }
                        }
                    }
                    /*
                     * Everything thrown from here travels through Media3's Loader, and Loader
                     * only preserves an error code when the exception is a DataSourceException
                     * (ExoPlayerImplInternal catches it specifically and propagates `reason`).
                     *
                     * PlaybackException extends Exception, NOT IOException, so throwing one here
                     * used to be caught by `Loader.LoadTask`'s generic `catch (Exception)`, wrapped
                     * in `Loader.UnexpectedLoaderException`, and re-classified as
                     * ERROR_CODE_IO_UNSPECIFIED (2000) — silently discarding the code chosen below.
                     * The wrapper also made DefaultLoadErrorHandlingPolicy return C.TIME_UNSET,
                     * disabling ExoPlayer's own 3x retry, so the user saw the failure immediately.
                     *
                     * Worse, the message was overwritten with a generic "Unknown error", which is
                     * what PlaybackError reads (`error.cause?.cause?.message`). Propagating the
                     * real message instead is what lets the UI's restricted/age/login detection
                     * see YouTube's actual reason.
                     */
                    when (throwable) {
                        is PlaybackException -> {
                            throw DataSourceException(
                                throwable.message,
                                throwable,
                                throwable.errorCode,
                            )
                        }

                        is java.net.ConnectException, is java.net.UnknownHostException -> {
                            throw DataSourceException(
                                getString(R.string.error_no_internet),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            )
                        }

                        is java.net.SocketTimeoutException -> {
                            throw DataSourceException(
                                getString(R.string.error_timeout),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            )
                        }

                        else -> {
                            throw DataSourceException(
                                throwable.message ?: getString(R.string.error_unknown),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                            )
                        }
                    }
                }

            val nonNullPlayback =
                requireNotNull(playbackData) {
                    getString(R.string.error_unknown)
                }
            run {
                val format = nonNullPlayback.format
                val loudnessDb = nonNullPlayback.audioConfig?.loudnessDb
                val perceptualLoudnessDb = nonNullPlayback.audioConfig?.perceptualLoudnessDb

                Timber
                    .tag(TAG)
                    .d("Storing format for $mediaId with loudnessDb: $loudnessDb, perceptualLoudnessDb: $perceptualLoudnessDb")
                if (loudnessDb == null && perceptualLoudnessDb == null) {
                    Timber.tag(TAG).w("No loudness data available from YouTube for video: $mediaId")
                }

                database.query {
                    upsert(
                        FormatEntity(
                            id = mediaId,
                            itag = format.itag,
                            mimeType = format.mimeType.split(";")[0],
                            codecs = format.mimeType.substringAfter("codecs=", "").removeSurrounding("\""),
                            bitrate = format.bitrate,
                            sampleRate = format.audioSampleRate,
                            contentLength = format.contentLength ?: 0L,
                            loudnessDb = loudnessDb,
                            perceptualLoudnessDb = perceptualLoudnessDb,
                            playbackUrl = nonNullPlayback.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                        ),
                    )
                }
                scope.launch(Dispatchers.IO) { recoverSong(mediaId, nonNullPlayback) }

                // Clear bypass flag now that we've fetched fresh stream
                if (bypassCacheForQualityChange.remove(mediaId)) {
                    Timber.tag("MusicService").d("Cleared bypass cache flag for $mediaId after fresh fetch")
                }

                val streamUrl = nonNullPlayback.streamUrl

                songUrlCache[mediaId] =
                    streamUrl to System.currentTimeMillis() + (nonNullPlayback.streamExpiresInSeconds * 1000L)
                return@Factory dataSpec.withUri(streamUrl.toUri()).subrange(0, CHUNK_LENGTH)
            }
        }
    }

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            // Constant-bitrate seeking lets fallback streams without a seek table (SoundCloud AAC
            // transmuxed to ADTS, plain MP3) show a duration and seek.
            DefaultExtractorsFactory()
                .setAdtsExtractorFlags(androidx.media3.extractor.ts.AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
                .setMp3ExtractorFlags(androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
        )

    private fun createRenderersFactory(
        eqProcessor: CustomEqualizerAudioProcessor,
        silenceProcessor: SilenceDetectorAudioProcessor,
    ) = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ) = DefaultAudioSink
            .Builder(this@MusicService)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    // 2. Inject processor into audio pipeline
                    arrayOf(
                        eqProcessor,
                        silenceProcessor,
                        com.metrolist.music.playback.audio.BalanceAudioProcessor(),
                    ),
                    SilenceSkippingAudioProcessor(2_000_000, 20_000, 256),
                    SonicAudioProcessor(),
                ),
            ).build()
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val historyDurationMs = dataStore[HistoryDuration]?.times(1000f) ?: 30000f

        if (playbackStats.totalPlayTimeMs >= historyDurationMs &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }
        }

        if (playbackStats.totalPlayTimeMs >= historyDurationMs) {
            CoroutineScope(Dispatchers.IO).launch {
                val playbackUrl =
                    database.format(mediaItem.mediaId).first()?.playbackUrl
                        ?: YTPlayerUtils
                            .playerResponseForMetadata(mediaItem.mediaId, null)
                            .getOrNull()
                            ?.playbackTracking
                            ?.videostatsPlaybackUrl
                            ?.baseUrl
                playbackUrl?.let {
                    YouTube
                        .registerPlayback(null, playbackUrl)
                        .onFailure {
                            reportException(it)
                        }
                }
            }
        }
    }

    private data class QueueSnapshot(
        val queue: PersistQueue,
        val automix: PersistQueue,
        val state: PersistPlayerState,
    )

    private fun buildQueueSnapshot(): QueueSnapshot? {
        if (player.mediaItemCount == 0) {
            Timber.tag(TAG).d("Skipping queue save - no media items")
            return null
        }
        return try {
            QueueSnapshot(
                queue = currentQueue.toPersistQueue(
                    title = queueTitle,
                    items = player.mediaItems.mapNotNull { it.metadata },
                    mediaItemIndex = player.currentMediaItemIndex,
                    position = player.currentPosition,
                ),
                automix = PersistQueue(
                    title = "automix",
                    items = automixItems.value.mapNotNull { it.metadata },
                    mediaItemIndex = 0,
                    position = 0,
                ),
                state = PersistPlayerState(
                    playWhenReady = player.playWhenReady,
                    repeatMode = player.repeatMode,
                    shuffleModeEnabled = player.shuffleModeEnabled,
                    volume = playerVolume.value,
                    currentPosition = player.currentPosition,
                    currentMediaItemIndex = player.currentMediaItemIndex,
                    playbackState = player.playbackState,
                )
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to snapshot queue")
            reportException(e)
            null
        }
    }

    private fun writeQueueSnapshot(snapshot: QueueSnapshot) {
        runCatching {
            filesDir.resolve(PERSISTENT_QUEUE_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos -> oos.writeObject(snapshot.queue) }
            }
        }.onFailure {
            Timber.tag(TAG).e(it, "Failed to save queue")
            reportException(it)
        }
        runCatching {
            filesDir.resolve(PERSISTENT_AUTOMIX_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos -> oos.writeObject(snapshot.automix) }
            }
        }.onFailure {
            Timber.tag(TAG).e(it, "Failed to save automix")
            reportException(it)
        }
        runCatching {
            filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos -> oos.writeObject(snapshot.state) }
            }
        }.onFailure {
            Timber.tag(TAG).e(it, "Failed to save player state")
            reportException(it)
        }
    }

    // Capture player state on the caller thread (Media3 player is Main-bound), then
    // hand the snapshot to a background coroutine so the ObjectOutputStream writes
    // don't block the main loop and trigger an ANR (issue #130).
    private fun saveQueueToDisk() {
        val snapshot = buildQueueSnapshot() ?: return
        scope.launch(Dispatchers.IO) { writeQueueSnapshot(snapshot) }
    }

    // Blocking variant used by onDestroy where scope is about to be cancelled and we
    // must guarantee the snapshot reaches disk before the service is torn down.
    private fun saveQueueToDiskBlocking() {
        val snapshot = buildQueueSnapshot() ?: return
        runBlocking(Dispatchers.IO) { writeQueueSnapshot(snapshot) }
    }

    override fun onDestroy() {
        isRunning = false
        preCacheJob?.cancel()

        // Save episode position before destroying
        val currentMetadata = player.currentMediaItem?.metadata
        if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
            runBlocking(Dispatchers.IO) {
                database.updatePlaybackPosition(currentMetadata.id, player.currentPosition)
            }
        }

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        castConnectionHandler?.release()
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDiskBlocking()
        }
        if (discordRpc?.isRpcRunning() == true) {
            discordRpc?.closeRPC()
        }
        discordRpc = null
        connectivityObserver.unregister()
        abandonAudioFocus()
        releaseLoudnessEnhancer()
        mediaLibrarySessionCallback.release()
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        playerSilenceProcessors.remove(player)
        // Note: equalizerService audio processors are cleared in equalizerService.release() if needed,
        // or we can't easily reference the specific processor created in createExoPlayer here without storing it.
        // But since we are destroying the service, it's fine.
        player.release()
        discordUpdateJob?.cancel()
        sponsorBlockJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // When the app is swiped away from recents, a foreground media service keeps the
        // process (and playback) alive, so relying on MainActivity.onDestroy is unreliable.
        // Android guarantees this callback, so honor "Stop music on task clear" here: halt
        // playback immediately and tear the service down (onDestroy persists queue/state).
        if (playerInitialized.value && dataStore.get(StopMusicOnTaskClearKey, false)) {
            player.stop()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_ALARM_TRIGGER -> {
                handleAlarmTrigger(intent)
            }

            MusicWidgetReceiver.ACTION_PLAY_PAUSE -> {
                if (playerInitialized.value) {
                    if (player.isPlaying) player.pause() else player.play()
                    updateWidgetUI(player.isPlaying)
                }
            }

            MusicWidgetReceiver.ACTION_LIKE -> {
                if (playerInitialized.value) toggleLike()
            }

            MusicWidgetReceiver.ACTION_NEXT -> {
                if (playerInitialized.value) {
                    player.seekToNext()
                    updateWidgetUI(player.isPlaying)
                }
            }

            MusicWidgetReceiver.ACTION_PREVIOUS -> {
                if (playerInitialized.value) {
                    player.seekToPrevious()
                    updateWidgetUI(player.isPlaying)
                }
            }

            MusicWidgetReceiver.ACTION_UPDATE_WIDGET -> {
                if (playerInitialized.value) updateWidgetUI(player.isPlaying)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleAlarmTrigger(intent: Intent) {
        scope.launch(Dispatchers.IO) {
            try {
                MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to reschedule alarms after trigger")
            }
        }
        val playlistId = intent.getStringExtra(EXTRA_ALARM_PLAYLIST_ID).orEmpty()
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (playlistId.isBlank()) {
            if (alarmId.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val alarms = MusicAlarmStore.load(this@MusicService)
                        val updated =
                            alarms.map { alarm ->
                                if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                            }
                        MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Failed to disable alarm with invalid playlist")
                    }
                }
            }
            return
        }
        val randomSong = intent.getBooleanExtra(EXTRA_ALARM_RANDOM_SONG, false)
        scope.launch {
            try {
                val playlistSongs =
                    withContext(Dispatchers.IO) {
                        database.playlistSongs(playlistId).first()
                    }
                if (playlistSongs.isEmpty()) {
                    if (alarmId.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            val alarms = MusicAlarmStore.load(this@MusicService)
                            val updated =
                                alarms.map { alarm ->
                                    if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                                }
                            MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                        }
                    }
                    return@launch
                }
                val items = playlistSongs.map { it.song.toMediaItem() }
                val playlistName =
                    withContext(Dispatchers.IO) {
                        database
                            .playlist(playlistId)
                            .first()
                            ?.playlist
                            ?.name
                    }
                withContext(Dispatchers.IO) {
                    MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
                }

                val alarmItems =
                    if (randomSong) {
                        val firstIndex = Random.nextInt(items.size)
                        buildList(items.size) {
                            add(items[firstIndex])
                            items.forEachIndexed { index, item ->
                                if (index != firstIndex) add(item)
                            }
                        }
                    } else {
                        items
                    }

                player.stop()
                player.clearMediaItems()
                playQueue(
                    ListQueue(
                        title = playlistName,
                        items = alarmItems,
                        startIndex = 0,
                        position = 0L,
                    ),
                    playWhenReady = true,
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to start alarm playback")
            }
        }
    }

    /**
     * Updates all app widgets with current playback state
     */
    private data class WidgetUiState(
        val title: String,
        val artist: String,
        val artworkUri: String?,
        val isPlaying: Boolean,
        val isLiked: Boolean,
        val duration: Long,
        val currentPosition: Long,
        val mediaId: String? = null,
    )

    /**
     * Requests a widget render for the current player state.
     *
     * The Media3 player is Main-bound, so its state is sampled on the caller thread and
     * the actual rendering (bitmap work plus AppWidgetManager binder traffic) happens on
     * [widgetRenderRequests]' consumer, off the main thread. The channel is CONFLATED, so
     * a render slower than the request rate collapses to "latest state wins" instead of
     * queueing. Previously each tick did a fire-and-forget `scope.launch` on
     * Dispatchers.Main, which piled up without bound and saturated the main looper.
     */
    private fun updateWidgetUI(isPlaying: Boolean) {
        val songData = currentSong.value
        val song = songData?.song
        widgetRenderRequests.trySend(
            WidgetUiState(
                title = song?.title ?: getString(R.string.no_song_playing),
                artist = songData?.artists?.joinToString(", ") { it.name } ?: getString(R.string.tap_to_open),
                artworkUri = song?.thumbnailUrl,
                isPlaying = isPlaying,
                isLiked = isCurrentFavorite(),
                duration = if (player.duration != C.TIME_UNSET) player.duration else 0,
                currentPosition = player.currentPosition,
                mediaId = player.currentMediaItem?.mediaId,
            )
        )
    }

    private val widgetRenderRequests = Channel<WidgetUiState>(Channel.CONFLATED)

    private fun startWidgetRenderer() {
        scope.launch(Dispatchers.Default) {
            for (state in widgetRenderRequests) {
                try {
                    widgetManager.updateWidgets(
                        title = state.title,
                        artist = state.artist,
                        artworkUri = state.artworkUri,
                        isPlaying = state.isPlaying,
                        isLiked = state.isLiked,
                        duration = state.duration,
                        currentPosition = state.currentPosition,
                        mediaId = state.mediaId,
                    )
                } catch (e: Exception) {
                    // Widget not added to home screen or other error
                }
            }
        }
    }

    private var widgetUpdateJob: Job? = null

    private fun startWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob =
            scope.launch {
                while (isActive) {
                    if (player.isPlaying) {
                        updateWidgetUI(true)
                    }
                    delay(WIDGET_REFRESH_MS)
                }
            }
    }

    private fun stopWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = null
    }

    /**
     * Widget refresh cadence while playing. The widget shows a coarse progress bar, so
     * 1s granularity is indistinguishable from the previous 200ms — which cost five
     * AppWidgetManager binder round-trips per second (plus a full bitmap re-render)
     * even when no widget was on the home screen.
     */
    private val WIDGET_REFRESH_MS = 1_000L

    private fun shareSong() {
        val songData = currentSong.value
        val songId = songData?.song?.id ?: return

        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=$songId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivity(
            Intent.createChooser(shareIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /**
     * Returns the persisted manual Qobuz override for [mediaId], or null when
     * the auto-matcher is in charge.
     */
    suspend fun getQobuzMatchOverride(mediaId: String): QobuzMatchOverride? =
        withContext(Dispatchers.IO) {
            QobuzMatchOverrides.decode(dataStore.get(QobuzMatchOverridesKey, ""))[mediaId]
        }

    /**
     * Stores or clears a manual Qobuz track override for [mediaId]. Passing
     * `null` clears it. After the write, every cached form of the stream for
     * this mediaId is invalidated and — if it's the currently playing track —
     * playback restarts at the same position so the new override takes
     * effect immediately.
     */
    fun setQobuzMatchOverride(
        mediaId: String,
        override: QobuzMatchOverride?,
    ) {
        if (mediaId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val current = QobuzMatchOverrides.decode(prefs[QobuzMatchOverridesKey])
                if (override == null) {
                    current.remove(mediaId)
                } else {
                    current[mediaId] = override
                }
                prefs[QobuzMatchOverridesKey] = QobuzMatchOverrides.encode(current)
            }
            QobuzAudioProvider.invalidate(mediaId)
            qobuzMissUntilMs.remove(mediaId)
            songUrlCache.remove(mediaId)
            try {
                playerCache.removeResource(mediaId)
                downloadCache.removeResource(mediaId)
            } catch (e: Exception) {
                Timber.tag("MusicService").e(e, "Failed to clear cache on Qobuz override for $mediaId")
            }
            bypassCacheForQualityChange.add(mediaId)
            withContext(Dispatchers.Main) {
                if (player.currentMediaItem?.mediaId == mediaId) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val wasPlaying = player.playWhenReady
                    val idx = player.currentMediaItemIndex
                    player.stop()
                    player.seekTo(idx, pos)
                    player.prepare()
                    if (wasPlaying) player.play()
                }
            }
        }
    }

    /**
     * A recommended track turned out below [RecommendedTracks.MIN_KBPS] once its audio was measured
     * (VK doesn't state its bitrate): another source is tried, and without one the track goes.
     */
    private suspend fun rejectLowRecommendation(mediaId: String, provider: AudioProviderId) {
        AudioDiagnostics.info("recommended $mediaId: $provider measured below ${RecommendedTracks.MIN_KBPS} kbps")
        markFallbackFailed(mediaId, provider = provider)
        songUrlCache.remove(mediaId)
        val failures = currentFailures(mediaId)
        val alternative = withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(FALLBACK_RESOLVE_TIMEOUT_MS) {
                    AudioFallbackEngine.streamPlan(mediaId, database.getSongById(mediaId), failures.providers, failures.tracks)
                }
            }.getOrNull()
        }
        val good = alternative != null && !(alternative is AudioFallbackEngine.StreamPlan.Direct &&
            RecommendedTracks.tooLow(mediaId, alternative.stream.bitrate))
        if (!good) {
            dropRecommendation(mediaId)
            return
        }
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId } ?: return
        val isCurrent = index == player.currentMediaItemIndex
        val position = player.currentPosition
        if (isCurrent) player.stop()
        withContext(Dispatchers.IO) { runCatching { playerCache.removeResource(mediaId) } }
        if (isCurrent && player.currentMediaItemIndex == index) {
            player.seekTo(index, position)
            player.prepare()
            player.play()
        }
    }

    /** Takes a too-quiet recommended track out of the queue (skipping it first when it plays). */
    private fun dropRecommendation(mediaId: String) {
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId } ?: return
        if (index == player.currentMediaItemIndex) {
            if (!player.hasNextMediaItem()) return
            player.seekToNextMediaItem()
        }
        AudioDiagnostics.info("recommended $mediaId skipped: no source at ${RecommendedTracks.MIN_KBPS}+ kbps")
        player.removeMediaItem(index)
    }

    /** Each enabled source's best match for [mediaId], for the "choose audio source" dialog. */
    suspend fun audioSourceCandidates(mediaId: String): List<com.metrolist.music.resolver.ProviderMatch> =
        withContext(Dispatchers.IO) {
            AudioFallbackEngine.candidatesFor(mediaId, database.getSongByIdBlocking(mediaId))
        }

    suspend fun currentAudioSource(mediaId: String): com.metrolist.music.resolver.ProviderMatch? =
        withContext(Dispatchers.IO) {
            AudioFallbackEngine.currentChoice(mediaId, database.getSongByIdBlocking(mediaId))
        }

    /**
     * Pins the recording that plays for [mediaId] (null = automatic again). Every cached form of the
     * stream is dropped and, if it's the current track, playback restarts at the same position.
     */
    fun setAudioSource(
        mediaId: String,
        match: com.metrolist.music.resolver.ProviderMatch?,
        /** The downloaded audio is replaced too (the caller downloads the new version). */
        dropDownloaded: Boolean = false,
    ) {
        if (mediaId.isBlank()) return
        scope.launch {
            // The player lets go of the old audio first: cache spans it still holds can't be removed,
            // and a restart then played the same old bytes — "switched to VK" but YouTube went on.
            val isCurrent = player.currentMediaItem?.mediaId == mediaId
            val pos = player.currentPosition.coerceAtLeast(0L)
            val wasPlaying = player.playWhenReady
            val idx = player.currentMediaItemIndex
            if (isCurrent) player.stop()
            withContext(Dispatchers.IO) {
                AudioFallbackEngine.setManualChoice(mediaId, database.getSongByIdBlocking(mediaId), match)
                songUrlCache.remove(mediaId)
                songUrlCache.remove(RESCUE_CACHE_PREFIX + mediaId)
                fallbackServing.remove(mediaId)
                fallbackFailures.remove(mediaId)
                runCatching { playerCache.removeResource(mediaId) }
                runCatching { playerCache.removeResource(RESCUE_CACHE_PREFIX + mediaId) }
                // Otherwise the downloaded old version keeps playing until it is downloaded again.
                if (dropDownloaded) runCatching { downloadCache.removeResource(mediaId) }
            }
            if (isCurrent && player.currentMediaItemIndex == idx) {
                player.seekTo(idx, pos)
                player.prepare()
                if (wasPlaying) player.play()
            }
        }
    }

    /**
     * Synchronous candidate search for the manual override UI. Runs the same
     * search pipeline as [QobuzAudioProvider.resolve] but returns lightweight
     * metadata for every candidate found across all backends.
     */
    suspend fun searchQobuzCandidates(
        mediaId: String,
        title: String,
        artists: List<String>,
        album: String?,
        isrc: String?,
        durationMs: Long?,
    ): List<QobuzAudioProvider.CandidateMetadata> = withContext(Dispatchers.IO) {
        val backendPref = dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.MONOKENNY)
        val country = dataStore.get(QobuzCountryKey, "US")
            .trim()
            .uppercase()
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"
        val resolverBackend = when (backendPref) {
            QobuzBackend.MONOKENNY -> QobuzAudioProvider.ResolverBackend.MONOKENNY
            QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
            QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
            QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
        }
        val qualityEnum = dataStore.get(QobuzAudioQualityKey).toEnum(QobuzAudioQuality.CD_QUALITY)
        val query = QobuzAudioProvider.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            isrc = isrc,
            durationMs = durationMs,
            countryCode = country,
            backend = resolverBackend,
            qualityCode = QobuzAudioProvider.qualityCodeFor(qualityEnum),
        )
        runCatching { QobuzAudioProvider.searchCandidates(query) }.getOrDefault(emptyList())
    }

    /**
     * Get the stream URL for a given media ID.
     * This is used for Google Cast to send the audio URL to Chromecast.
     */
    suspend fun getStreamUrl(mediaId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val playbackData =
                    YTPlayerUtils
                        .playerResponseForPlayback(
                            videoId = mediaId,
                            audioQuality = audioQuality,
                            connectivityManager = connectivityManager,
                        ).getOrNull()
                playbackData?.streamUrl
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to get stream URL for Cast")
                null
            }
        }

    /**
     * Initialize Google Cast support
     */
    private fun initializeCast() {
        if (dataStore.get(com.metrolist.music.constants.EnableGoogleCastKey, true)) {
            try {
                castConnectionHandler = CastConnectionHandler(this, scope, this)
                castConnectionHandler?.initialize()
                timber.log.Timber.d("Google Cast initialized")
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to initialize Google Cast")
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            scheduleCrossfade()
        }
    }

    /**
     * Restarts the SponsorBlock segment-skipper for the currently-playing track.
     * Cancels any previous job, then (if enabled) launches a coroutine that
     * fetches segments and polls playback position to seek past them. Failures
     * are silent — SponsorBlock should never disrupt playback.
     */
    private fun startSponsorBlockForCurrentTrack() {
        sponsorBlockJob?.cancel()
        sponsorBlockJob = null

        val enabled = dataStore.get(SponsorBlockEnabledKey, false)
        if (!enabled) return

        val rawCategories = dataStore.get(SponsorBlockCategoriesKey, SPONSORBLOCK_DEFAULT_CATEGORIES)
        val categories = rawCategories.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (categories.isEmpty()) return

        val mediaId = player.currentMediaItem?.mediaId ?: return
        // Episodes (podcasts) are not on YouTube proper and aren't on SponsorBlock.
        if (player.currentMediaItem?.metadata?.isEpisode == true) return

        val showToast = dataStore.get(SponsorBlockShowToastKey, false)

        sponsorBlockJob = scope.launch {
            val segments = withContext(Dispatchers.IO) {
                runCatching { SponsorBlockManager.fetchSegments(mediaId, categories) }
                    .getOrDefault(emptyList())
            }
            if (segments.isEmpty()) return@launch
            // Verify track hasn't changed during the fetch.
            if (player.currentMediaItem?.mediaId != mediaId) return@launch

            while (isActive && player.currentMediaItem?.mediaId == mediaId) {
                val pos = player.currentPosition
                segments.forEach { segment ->
                    if (pos in segment.startMs..(segment.endMs - 200)) {
                        Timber.tag(TAG).d(
                            "SponsorBlock: skipping ${segment.category} ${segment.startMs}..${segment.endMs}ms"
                        )
                        // Hop straight past the segment. seekTo is idempotent
                        // so re-entering the segment (manual rewind) re-triggers.
                        player.seekTo(segment.endMs)
                        if (showToast) {
                            Toast.makeText(
                                this@MusicService,
                                getString(R.string.sponsorblock_segment_skipped, segment.category),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                delay(250)
            }
        }
    }

    private fun scheduleCrossfade() {
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        if (!crossfadeEnabled || player.duration == C.TIME_UNSET || player.duration <= crossfadeDuration) return
        if (crossfadeGapless && isNextItemGapless()) return
        if (!player.hasNextMediaItem() && player.repeatMode != REPEAT_MODE_ONE) return

        val triggerTime = player.duration - crossfadeDuration.toLong()
        val delayMs = triggerTime - player.currentPosition
        if (delayMs <= 0) return

        val targetMediaId = player.currentMediaItem?.mediaId

        crossfadeTriggerJob =
            scope.launch {
                delay(delayMs)
                if (isActive && player.isPlaying && player.currentMediaItem?.mediaId == targetMediaId && !sleepTimer.pauseWhenSongEnd) {
                    startCrossfade()
                }
            }
    }

    private fun isNextItemGapless(): Boolean {
        val current = player.currentMediaItem?.mediaMetadata ?: return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val next = player.getMediaItemAt(nextIndex).mediaMetadata
        return current.albumTitle != null && current.albumTitle == next.albumTitle
    }

    private fun startCrossfade() {
        if (isCrossfading) return

        // Preserve player state before creating the secondary player
        val savedRepeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)
        val savedShuffleEnabled = dataStore.get(ShuffleModeKey, false)

        // For repeat-one, crossfade back into the same track
        val targetIndex =
            if (savedRepeatMode == REPEAT_MODE_ONE) {
                player.currentMediaItemIndex
            } else {
                player.nextMediaItemIndex
            }
        if (targetIndex == C.INDEX_UNSET) return

        secondaryPlayer = createExoPlayer()
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)

        val itemCount = player.mediaItemCount
        val items = mutableListOf<MediaItem>()
        // Copy entire queue history + future
        for (i in 0 until itemCount) {
            items.add(player.getMediaItemAt(i))
        }

        secPlayer.setMediaItems(items)
        // Seek to target track (next track, or current track for repeat-one)
        secPlayer.seekTo(targetIndex, 0)
        secPlayer.volume = 0f

        // Copy repeat and shuffle state to the new player
        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = savedShuffleEnabled

        secPlayer.prepare()
        secPlayer.playWhenReady = true

        performCrossfadeSwap()

        // Rebuild shuffle order on the new primary player if shuffle was active
        if (savedShuffleEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
    }

    private fun performCrossfadeSwap() {
        isCrossfading = true
        val nextPlayer = secondaryPlayer ?: return
        val currentPlayer = player

        fadingPlayer = currentPlayer
        player = nextPlayer
        _playerFlow.value = player
        secondaryPlayer = null

        fadingPlayer?.removeListener(this)
        fadingPlayer?.removeListener(sleepTimer)

        // Add listener to sync play/pause state
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isCrossfading && fadingPlayer != null) {
                        if (isPlaying) {
                            fadingPlayer?.play()
                        } else {
                            fadingPlayer?.pause()
                        }
                    } else {
                        player.removeListener(this)
                    }
                }
            },
        )

        nextPlayer.removeListener(secondaryPlayerListener)
        nextPlayer.addListener(this)
        nextPlayer.addListener(sleepTimer)

        sleepTimer.player = player

        try {
            (mediaSession as MediaSession).player = player
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to swap player in MediaSession")
        }

        crossfadeJob =
            scope.launch {
                val duration = crossfadeDuration.toLong()
                val steps = 20
                val stepTime = duration / steps
                val startVolume =
                    try {
                        fadingPlayer?.volume ?: 1f
                    } catch (e: Exception) {
                        1f
                    }

                for (i in 0..steps) {
                    if (!isActive) break
                    // Pause volume ramp if player is paused
                    while (!player.isPlaying && isActive) {
                        delay(100)
                    }

                    val progress = i / steps.toFloat()
                    val fadeIn = 1.0f - (1.0f - progress) * (1.0f - progress)
                    val fadeOut = (1.0f - progress) * (1.0f - progress)

                    try {
                        player.volume = startVolume * fadeIn
                        fadingPlayer?.volume = startVolume * fadeOut
                    } catch (e: Exception) {
                        break
                    }

                    delay(stepTime)
                }

                try {
                    fadingPlayer?.volume = 0f
                    player.volume = startVolume
                    cleanupCrossfade()
                } catch (e: Exception) {
                }
            }
    }

    private fun cleanupCrossfade() {
        fadingPlayer?.stop()
        fadingPlayer?.clearMediaItems()
        fadingPlayer?.release()
        fadingPlayer = null
        isCrossfading = false
        applyEffectiveVolume()
        sleepTimer.notifySongTransition()
    }

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.metrolist.music.action.ALARM_TRIGGER"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_PLAYLIST_ID = "extra_alarm_playlist_id"
        const val EXTRA_ALARM_RANDOM_SONG = "extra_alarm_random_song"

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val YOUTUBE_PLAYLIST = "youtube_playlist"
        const val SPOTIFY_PLAYLIST = "spotify_playlist"
        const val SEARCH = "search"
        const val SHUFFLE_ACTION = "__shuffle__"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L

        /** Cache-key prefix for audio rescued from another provider for a regular YouTube id. */
        const val RESCUE_CACHE_PREFIX = "fbrescue:"
        private const val FALLBACK_FAILED_TTL_MS = 10 * 60 * 1000L
        // Generous: a Soulseek last-resort download has to finish before the track can start.
        private const val FALLBACK_RESOLVE_TIMEOUT_MS = 210_000L
        private const val YOUTUBE_RESCUE_WINDOW_MS = 3 * 60 * 1000L
        private const val YOUTUBE_RESCUES_TO_TRIP = 2
        private const val YOUTUBE_BYPASS_MS = 5 * 60 * 1000L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
        const val MAX_RETRY_COUNT = 10

        private const val WARM_UP_AHEAD_MS = 45_000L
        private const val WARM_UP_MAX_WAIT_MS = 90_000L
        private const val WARM_UP_BYTES_METERED = 1_500_000L
        private const val WARM_UP_BYTES_UNMETERED = 4_000_000L

        /** How long to wait before each "is the internet back?" check while a track waits for it. */
        private val NETWORK_RECHECK_DELAYS_MS = longArrayOf(1_500, 2_500, 4_000, 5_000)

        // Constants for audio normalization
        private const val MAX_GAIN_MB = 300 // Maximum gain in millibels (3 dB)
        private const val MIN_GAIN_MB = -1500 // Minimum gain in millibels (-15 dB)

        private const val TAG = "MusicService"
        private const val PRECACHE_TAG = "MeldPreCache"

        @Volatile
        var isRunning = false
            private set
    }
}
