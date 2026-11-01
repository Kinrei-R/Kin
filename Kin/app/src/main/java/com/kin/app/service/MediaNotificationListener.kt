package com.kin.app.service

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kin.app.data.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observes media notifications from any player and publishes track changes to Firestore.
 * Updates are debounced and de-duplicated so only genuine track changes are sent.
 */
class MediaNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository by lazy { FirebaseRepository.get() }

    private var pendingJob: Job? = null
    private var pendingSignature: String? = null
    private var lastTrackKey: String? = null
    private var lastPlaying: Boolean? = null
    private var activePackage: String? = null

    private class TrackUpdate(
        val packageName: String,
        val title: String,
        val artist: String,
        val playing: Boolean
    ) {
        val trackKey get() = "$packageName|$title|$artist"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications?.forEach(::handlePosted) }
            .onFailure { Log.w(TAG, "Initial scan failed", it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handlePosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || !isMediaNotification(sbn)) return
        if (sbn.packageName != activePackage) return
        // Player dismissed its notification: mark idle after the debounce window unless a new track arrives.
        lastTrackKey = null
        lastPlaying = null
        pendingJob?.cancel()
        pendingSignature = "idle"
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                repository.setPlaying(false)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to mark idle", t)
            } finally {
                activePackage = null
                pendingSignature = null
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Parsing

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return n.category == Notification.CATEGORY_TRANSPORT ||
            (n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true)
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return
        if (!isMediaNotification(sbn)) return

        val extras = sbn.notification.extras ?: return

        val controller = extras.mediaToken()?.let { runCatching { MediaController(this, it) }.getOrNull() }
        val metadata = controller?.metadata

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""

        val playing = when (controller?.playbackState?.state) {
            null -> true // No session info: a visible transport notification implies active playback.
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
            else -> false
        }

        schedule(TrackUpdate(pkg, title, artist, playing), sbn)
    }

    // ------------------------------------------------------------------ Debounce

    private fun schedule(update: TrackUpdate, sbn: StatusBarNotification) {
        val signature = "${update.trackKey}|${update.playing}"
        // Ignore progress-style re-posts that carry no change.
        if (update.trackKey == lastTrackKey && update.playing == lastPlaying) return
        if (signature == pendingSignature) return

        val sourceApp = resolveAppLabel(sbn)
        pendingJob?.cancel()
        pendingSignature = signature
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                if (update.trackKey == lastTrackKey) {
                    repository.setPlaying(update.playing)
                } else {
                    repository.publishListeningState(
                        title = update.title,
                        artist = update.artist,
                        sourceApp = sourceApp,
                        sourcePackage = update.packageName,
                        playing = update.playing
                    )
                }
                lastTrackKey = update.trackKey
                lastPlaying = update.playing
                activePackage = update.packageName
            } catch (t: Throwable) {
                // Leave last* untouched so the next post retries.
                Log.w(TAG, "Failed to publish listening state", t)
            } finally {
                if (pendingSignature == signature) pendingSignature = null
            }
        }
    }

    private fun resolveAppLabel(sbn: StatusBarNotification): String {
        val pkg = sbn.packageName
        KNOWN_APPS[pkg]?.let { return it }
        val fromExtras = (sbn.notification.extras?.rawValue("android.appInfo") as? ApplicationInfo)
            ?.let { runCatching { it.loadLabel(packageManager).toString() }.getOrNull() }
        if (!fromExtras.isNullOrBlank()) return fromExtras
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    // ------------------------------------------------------------------ Bundle helpers

    @Suppress("DEPRECATION")
    private fun Bundle.rawValue(key: String): Any? = get(key)

    @Suppress("DEPRECATION")
    private fun Bundle.mediaToken(): MediaSession.Token? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }

    companion object {
        private const val TAG = "KinListener"
        private const val DEBOUNCE_MS = 1_500L
        private val IGNORED_PACKAGES = setOf("com.android.systemui", "android")
        private val KNOWN_APPS = mapOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.google.android.youtube" to "YouTube",
            "com.apple.android.music" to "Apple Music",
            "com.soundcloud.android" to "SoundCloud",
            "deezer.android.app" to "Deezer",
            "com.amazon.mp3" to "Amazon Music",
            "com.aspiro.tidal" to "TIDAL"
        )
    }
}
