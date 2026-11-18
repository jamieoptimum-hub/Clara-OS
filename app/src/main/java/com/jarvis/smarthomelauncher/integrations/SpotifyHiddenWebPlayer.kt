package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// HIDDEN SPOTIFY PREMIUM WEBVIEW & WEB PLAYBACK ENGINE (HARDWARE WIDEVINE DRM)
// - Runs a full 1280x800 desktop-viewport WebView offscreen so open.spotify.com
//   boots the exact same Desktop Web Playback SDK engine as a PC browser.
// - Pre-extracts `_spharmony_device_id` / `hobs_...` and pre-activates the local
//   receiver on startup so pressing Play starts audio immediately (< 300ms).
// - Automatically refreshes expired tokens on 401, activates the WebView player
//   on 404, and triggers `onPlaybackFallbackNeeded` for instant ExoPlayer fallback
//   if the tablet's WebView lacks Widevine L1/L3 audio output.
// - Blocks video/* codecs so 1080p Spotify Canvas videos never spin up decoders.
// - Shields Chromium's AudioFocusDelegate from transient mic/system audio focus losses.
// ---------------------------------------------------------------------------
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "StaticFieldLeak")
object SpotifyHiddenWebPlayer {
    private const val TAG = "SpotifyHiddenWeb"
    private const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val DESKTOP_VIEWPORT_WIDTH = 1280
    private const val DESKTOP_VIEWPORT_HEIGHT = 800

    @Volatile
    private var hiddenWebView: WebView? = null
    @Volatile
    private var isPageLoaded: Boolean = false
    @Volatile
    private var isWebPlaybackAuthorized: Boolean = false
    @Volatile
    var activeWebPlayerDeviceId: String = ""
        private set

    private var onTrackEndedCallback: (() -> Unit)? = null
    private var onPlaybackFallbackNeededCallback: (() -> Unit)? = null
    private var lastTrackEndTimestampMs: Long = 0L
    @Volatile
    private var lastPlayCommandTimestampMs: Long = 0L
    private var volumeDebounceJob: Job? = null
    private var idleSuspendJob: Job? = null

    fun setOnPlaybackFallbackNeededListener(listener: (() -> Unit)?) {
        onPlaybackFallbackNeededCallback = listener
    }

    /**
     * Safe audio focus & WebView media resume helper (replaces fragile hidden-API reflection on
     * `ServiceEventHandlerDelegate` and `Handler.mCallback`).
     */
    fun installChromiumAudioFocusShield(@Suppress("UNUSED_PARAMETER") context: Context) {
        val wv = hiddenWebView ?: return
        if (isWebPlaybackAuthorized && JarvisLiveBridge.isPlayingFlow.value) {
            wv.post {
                if (!isWebPlaybackAuthorized || !JarvisLiveBridge.isPlayingFlow.value) return@post
                runCatching {
                    wv.onResume()
                    wv.resumeTimers()
                    wv.evaluateJavascript(
                        "if (typeof window.__claraResumeAllMedia === 'function') window.__claraResumeAllMedia();",
                        null
                    )
                }
            }
        }
    }

    private fun scheduleOrCancelIdleSuspension(isPlaying: Boolean) {
        idleSuspendJob?.cancel()
        val wv = hiddenWebView ?: return
        wv.onResume()
        wv.resumeTimers()
        if (isPlaying) {
            wv.evaluateJavascript(
                "if (typeof window.__claraStartStatePoller === 'function') window.__claraStartStatePoller();",
                null
            )
        }
    }

    private class HiddenBridge {
        @JavascriptInterface
        fun onDeviceReady(deviceId: String) {
            if (deviceId.isNotBlank()) {
                activeWebPlayerDeviceId = deviceId.trim()
                Log.d(TAG, "Spotify Desktop Web Player Ready -> device_id=$activeWebPlayerDeviceId")
            }
        }

        @JavascriptInterface
        fun onTokenCaptured(bearerToken: String) {
            val clean = bearerToken.removePrefix("Bearer ").removePrefix("bearer ").trim()
            val ctx = ClaraInAppMusicPlayer.currentAppContext() ?: return
            if (clean.length > 25) {
                NativeMusicCatalogClient.updateCachedSpotifyBearerToken(ctx, clean)
            }
        }

        @JavascriptInterface
        fun onPlaybackFallbackNeeded(reason: String) {
            Log.w(TAG, "Spotify WebView requested instant stream fallback: $reason")
            CoroutineScope(Dispatchers.Main).launch {
                onPlaybackFallbackNeededCallback?.invoke()
            }
        }

        @JavascriptInterface
        fun onPlaybackState(jsonState: String) {
            // Never allow stale hidden WebView DOM poller events to overwrite ExoPlayer's active track title or state
            if (!isWebPlaybackAuthorized || ClaraInAppMusicPlayer.isLocalOrCastEngineActivelyPlaying()) {
                return
            }
            try {
                val obj = JSONObject(jsonState)
                val isPlaying = obj.optBoolean("isPlaying", false)
                val posMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L)
                val durMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L)
                val title = obj.optString("title", "").trim()
                val artist = obj.optString("artist", "").trim()
                val artUrl = obj.optString("artworkUrl", "").trim()
                val trackEnded = obj.optBoolean("trackEnded", false)

                val inTransitionGraceWindow =
                    System.currentTimeMillis() - lastPlayCommandTimestampMs < 3200L

                // During the 3.2s grace window right after selecting a new track, ignore stale
                // paused/title states from the previous song still sitting in the WebView DOM.
                if (inTransitionGraceWindow && !isPlaying) {
                    return
                }

                JarvisLiveBridge.isPlayingFlow.value = isPlaying
                if (durMs > 1000L && (!inTransitionGraceWindow || posMs < 5000L)) {
                    JarvisLiveBridge.trackDurationMsFlow.value = durMs
                    JarvisLiveBridge.trackPositionMsFlow.value = posMs.coerceAtMost(durMs)
                } else if (posMs > 0L && !inTransitionGraceWindow) {
                    val existingDur = JarvisLiveBridge.trackDurationMsFlow.value.coerceAtLeast(posMs)
                    JarvisLiveBridge.trackPositionMsFlow.value = posMs.coerceAtMost(existingDur)
                }
                if (!inTransitionGraceWindow && title.isNotBlank()) {
                    JarvisLiveBridge.activeTrackFlow.value =
                        if (artist.isNotBlank()) "$title - $artist" else title
                }
                if (!inTransitionGraceWindow && artUrl.startsWith("http")) {
                    ClaraInAppMusicPlayer.activeArtworkUrlFlow.value = artUrl
                }
                if (trackEnded && !inTransitionGraceWindow && System.currentTimeMillis() - lastTrackEndTimestampMs > 4000L) {
                    lastTrackEndTimestampMs = System.currentTimeMillis()
                    onTrackEndedCallback?.invoke()
                }
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            Log.d(TAG, "[JS] $msg")
        }
    }

    private val hiddenPlayerBootstrapJs = """
        (function() {
            if (window.__claraHiddenPlayerInstalled) return;
            window.__claraHiddenPlayerInstalled = true;
            window.__claraSpotifyToken = window.__claraSpotifyToken || '';
            window.__claraTokenCapturedAt = window.__claraTokenCapturedAt || 0;
            window.__claraClientToken = window.__claraClientToken || '';
            window.__claraSdkDeviceId = window.__claraSdkDeviceId || '';
            window.__claraConnectionId = window.__claraConnectionId || '';
            window.__claraDevicePreActivated = Boolean(window.__claraDevicePreActivated);
            window.__claraMediaElements = window.__claraMediaElements || [];
            window.__claraActiveAudioEl = window.__claraActiveAudioEl || null;
            window.__claraAudioContexts = window.__claraAudioContexts || [];
            window.__claraDesiredVolume = (typeof window.__claraDesiredVolume === 'number') ? window.__claraDesiredVolume : 1.0;
            window.__claraVoiceDuckFactor = (typeof window.__claraVoiceDuckFactor === 'number') ? window.__claraVoiceDuckFactor : 1.0;
            window.__claraPlayRequestId = window.__claraPlayRequestId || 0;
            window.__claraShouldBePlaying = Boolean(window.__claraShouldBePlaying);
            window.__claraAllowPause = false;
            window.__claraLastAutoResumeMs = 0;

            const log = function(m) {
                try { window.ClaraHiddenBridge.onLog(String(m)); } catch (e) {}
            };

            const notifyFallbackNeeded = function(reason) {
                try { window.ClaraHiddenBridge.onPlaybackFallbackNeeded(String(reason || 'fallback')); } catch (e) {}
            };

            // Force desktop screen metrics so Spotify Web Player never falls back to mobile web UI
            try {
                Object.defineProperty(window, 'innerWidth', { get: () => 1280, configurable: true });
                Object.defineProperty(window, 'innerHeight', { get: () => 800, configurable: true });
                Object.defineProperty(document.documentElement, 'clientWidth', { get: () => 1280, configurable: true });
                Object.defineProperty(document.documentElement, 'clientHeight', { get: () => 800, configurable: true });
                Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
            } catch (e) {}

            // Pre-extract Spotify Harmony / Web Playback SDK device ID from localStorage if already stored
            const tryReadLocalStorageDeviceId = function() {
                try {
                    const storedId = localStorage.getItem('_spharmony_device_id')
                        || localStorage.getItem('device_id')
                        || '';
                    const clean = String(storedId).replace(/"/g, '').trim();
                    if (clean.length >= 20 && !window.__claraSdkDeviceId) {
                        window.__claraSdkDeviceId = clean;
                        try { window.ClaraHiddenBridge.onDeviceReady(clean); } catch (e) {}
                    }
                } catch (e) {}
            };
            tryReadLocalStorageDeviceId();

            const effectiveVolume = function() {
                const base = (typeof window.__claraDesiredVolume === 'number') ? window.__claraDesiredVolume : 1.0;
                const duck = (typeof window.__claraVoiceDuckFactor === 'number') ? window.__claraVoiceDuckFactor : 1.0;
                return Math.max(0.0, Math.min(1.0, base * duck));
            };

            // 1. Block all video codecs so c2.qti.avc.decoder (1080x1920 Canvas video) never spins up,
            // while allowing all audio codecs (mp4a, opus, vorbis, flac, audio/mp4, audio/webm).
            if (window.MediaSource && typeof window.MediaSource.isTypeSupported === 'function') {
                const origIsTypeSupported = window.MediaSource.isTypeSupported.bind(window.MediaSource);
                window.MediaSource.isTypeSupported = function(mimeType) {
                    const m = String(mimeType || '').toLowerCase();
                    if (m.startsWith('video/') || m.includes('avc1') || m.includes('vp8') || m.includes('vp9') || m.includes('av01') || m.includes('hev1') || m.includes('hvc1')) {
                        return false;
                    }
                    return origIsTypeSupported(mimeType);
                };
            }
            if (window.HTMLMediaElement && window.HTMLMediaElement.prototype) {
                const origCanPlayType = window.HTMLMediaElement.prototype.canPlayType;
                window.HTMLMediaElement.prototype.canPlayType = function(mimeType) {
                    const m = String(mimeType || '').toLowerCase();
                    if (m.startsWith('video/') || m.includes('avc1') || m.includes('vp8') || m.includes('vp9') || m.includes('av01') || m.includes('hev1') || m.includes('hvc1')) {
                        return '';
                    }
                    return origCanPlayType.apply(this, arguments);
                };
            }

            // Force audio-only SW_SECURE_CRYPTO on Widevine EME so Android WebView decodes audio in software
            if (navigator.requestMediaKeySystemAccess) {
                const origReqMksa = navigator.requestMediaKeySystemAccess.bind(navigator);
                navigator.requestMediaKeySystemAccess = function(keySystem, supportedConfigurations) {
                    try {
                        if (String(keySystem || '').includes('widevine') && Array.isArray(supportedConfigurations)) {
                            const audioOnlyConfigs = supportedConfigurations.map(cfg => {
                                const copy = Object.assign({}, cfg);
                                delete copy.videoCapabilities;
                                if (Array.isArray(copy.audioCapabilities)) {
                                    copy.audioCapabilities = copy.audioCapabilities.map(ac => Object.assign({}, ac, {
                                        robustness: 'SW_SECURE_CRYPTO'
                                    }));
                                }
                                return copy;
                            });
                            return origReqMksa(keySystem, audioOnlyConfigs).catch(() => origReqMksa(keySystem, supportedConfigurations));
                        }
                    } catch (e) {}
                    return origReqMksa(keySystem, supportedConfigurations);
                };
            }

            // Track Web Audio API AudioContext instances so we can resume them on play
            window.__claraAudioContexts = window.__claraAudioContexts || [];
            ['AudioContext', 'webkitAudioContext'].forEach(function(ctxName) {
                const OrigCtx = window[ctxName];
                if (typeof OrigCtx === 'function' && !OrigCtx.__claraWrapped) {
                    const WrappedCtx = function() {
                        const instance = new OrigCtx(...arguments);
                        window.__claraAudioContexts.push(instance);
                        return instance;
                    };
                    WrappedCtx.prototype = OrigCtx.prototype;
                    WrappedCtx.__claraWrapped = true;
                    window[ctxName] = WrappedCtx;
                }
            });

            const origPlay = window.HTMLMediaElement && window.HTMLMediaElement.prototype
                ? window.HTMLMediaElement.prototype.play
                : null;
            const origPause = window.HTMLMediaElement && window.HTMLMediaElement.prototype
                ? window.HTMLMediaElement.prototype.pause
                : null;

            const isVisualCanvasElement = function(el) {
                if (!el) return true;
                const src = String(el.currentSrc || el.src || '').toLowerCase();
                if (src.includes('canvaz') || src.includes('.mp4')) return true;
                if (el.loop === true) return true;
                if ((el.videoWidth && el.videoWidth > 0) || (el.videoHeight && el.videoHeight > 0)) return true;
                if (el.closest && el.closest('[data-testid="canvas"]')) return true;
                return false;
            };

            const registerMediaEl = function(el) {
                if (!el || window.__claraMediaElements.includes(el)) return;
                if (isVisualCanvasElement(el)) {
                    try {
                        el.muted = true;
                        if (origPause) origPause.call(el);
                        el.removeAttribute('src');
                    } catch (e) {}
                    return;
                }
                try {
                    el.autoplay = true;
                    el.muted = false;
                    el.volume = effectiveVolume();
                    el.setAttribute('playsinline', 'true');
                } catch (e) {}
                el.addEventListener('pause', function() {
                    if (isVisualCanvasElement(el)) return;
                    const dur = el.duration || 0;
                    const pos = el.currentTime || 0;
                    const nearEnd = dur > 10 && pos >= (dur - 1.5);
                    if (window.__claraShouldBePlaying && !window.__claraAllowPause && !el.ended && !nearEnd) {
                        const now = Date.now();
                        if (now - window.__claraLastAutoResumeMs > 180) {
                            window.__claraLastAutoResumeMs = now;
                        }
                        if (origPlay) {
                            origPlay.call(el).catch(() => {});
                            setTimeout(function() {
                                if (window.__claraShouldBePlaying && !window.__claraAllowPause && el.paused && !el.ended) {
                                    origPlay.call(el).catch(() => {});
                                }
                            }, 60);
                        }
                    }
                });
                el.addEventListener('ended', function() {
                    if (isVisualCanvasElement(el)) return;
                    const dur = el.duration || 0;
                    if (dur < 30) return;
                    try {
                        window.ClaraHiddenBridge.onPlaybackState(JSON.stringify({
                            isPlaying: false,
                            positionMs: 0,
                            durationMs: Math.round(dur * 1000),
                            title: '',
                            artist: '',
                            artworkUrl: '',
                            trackEnded: true
                        }));
                    } catch (e) {}
                });
                window.__claraMediaElements.push(el);
                window.__claraActiveAudioEl = el;
            };

            const origCreateElement = document.createElement.bind(document);
            document.createElement = function(tagName, options) {
                const el = origCreateElement(tagName, options);
                if (tagName) {
                    const lower = String(tagName).toLowerCase();
                    if (lower === 'video' || lower === 'audio') {
                        registerMediaEl(el);
                    }
                }
                return el;
            };

            window.__claraPauseAllMedia = function() {
                window.__claraAllowPause = true;
                try {
                    const pauseEl = function(el) {
                        if (!el) return;
                        try {
                            el.muted = true;
                            if (origPause) origPause.call(el);
                            else el.pause();
                        } catch (e) {}
                    };
                    (window.__claraMediaElements || []).forEach(pauseEl);
                    document.querySelectorAll('audio, video').forEach(pauseEl);
                    (window.__claraAudioContexts || []).forEach(function(ctx) {
                        try { if (ctx && ctx.state === 'running' && typeof ctx.suspend === 'function') ctx.suspend(); } catch (e) {}
                    });
                } catch (e) {}
            };

            window.__claraKillAndPurgeAllMedia = function() {
                window.__claraAllowPause = true;
                try {
                    const killEl = function(el) {
                        if (!el) return;
                        try {
                            el.muted = true;
                            if (origPause) origPause.call(el);
                            else el.pause();
                            el.removeAttribute('src');
                            el.load();
                        } catch (e) {}
                    };
                    (window.__claraMediaElements || []).forEach(killEl);
                    document.querySelectorAll('audio, video').forEach(killEl);
                    window.__claraMediaElements = [];
                    window.__claraActiveAudioEl = null;
                } catch (e) {}
            };

            if (window.HTMLMediaElement && window.HTMLMediaElement.prototype && origPlay && origPause) {
                window.HTMLMediaElement.prototype.play = function() {
                    if (isVisualCanvasElement(this)) {
                        try { this.muted = true; } catch (e) {}
                        return Promise.resolve();
                    }
                    if (window.__claraHardSilenced || !window.__claraShouldBePlaying) {
                        try {
                            this.muted = true;
                            origPause.call(this);
                        } catch (e) {}
                        return Promise.resolve();
                    }
                    registerMediaEl(this);
                    window.__claraActiveAudioEl = this;
                    try {
                        this.muted = false;
                        this.volume = effectiveVolume();
                    } catch (e) {}
                    return origPlay.apply(this, arguments);
                };

                window.HTMLMediaElement.prototype.pause = function() {
                    if (!isVisualCanvasElement(this) &&
                        window.__claraShouldBePlaying &&
                        !window.__claraAllowPause &&
                        this === window.__claraActiveAudioEl &&
                        !this.ended &&
                        (this.currentTime || 0) > 0.2 &&
                        ((this.duration || 0) === 0 || (this.currentTime || 0) < (this.duration - 1.5))
                    ) {
                        return;
                    }
                    return origPause.apply(this, arguments);
                };
            }

            try {
                if (navigator.mediaSession && typeof navigator.mediaSession.setActionHandler === 'function') {
                    const origSetActionHandler = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
                    navigator.mediaSession.setActionHandler = function(action, handler) {
                        if (action === 'pause') {
                            return origSetActionHandler('pause', function() {
                                if (window.__claraShouldBePlaying && !window.__claraAllowPause) {
                                    window.__claraResumeAllMedia();
                                    return;
                                }
                                if (typeof handler === 'function') handler.apply(this, arguments);
                            });
                        }
                        return origSetActionHandler(action, handler);
                    };
                }
            } catch (e) {}

            const OrigAudioCtx = window.AudioContext || window.webkitAudioContext;
            if (OrigAudioCtx) {
                const WrappedAudioCtx = function() {
                    const ctx = new OrigAudioCtx(...arguments);
                    window.__claraAudioContexts.push(ctx);
                    return ctx;
                };
                WrappedAudioCtx.prototype = OrigAudioCtx.prototype;
                try {
                    window.AudioContext = WrappedAudioCtx;
                    window.webkitAudioContext = WrappedAudioCtx;
                } catch (e) {}
            }

            window.__claraResumeAllMedia = function() {
                if (window.__claraHardSilenced || !window.__claraShouldBePlaying) return;
                try {
                    (window.__claraAudioContexts || []).forEach(ctx => {
                        if (ctx && ctx.state === 'suspended' && typeof ctx.resume === 'function') {
                            ctx.resume().catch(() => {});
                        }
                    });
                } catch (e) {}
                try {
                    const targetVol = effectiveVolume();
                    document.querySelectorAll('audio').forEach(a => registerMediaEl(a));
                    const activeEl = window.__claraActiveAudioEl;
                    if (activeEl && !isVisualCanvasElement(activeEl)) {
                        activeEl.muted = false;
                        activeEl.volume = targetVol;
                        if (activeEl.paused && activeEl.readyState >= 1 && origPlay) {
                            origPlay.call(activeEl).catch(() => {});
                        }
                    } else {
                        (window.__claraMediaElements || []).forEach(el => {
                            if (el && !isVisualCanvasElement(el)) {
                                el.muted = false;
                                el.volume = targetVol;
                                if (el.paused && el.readyState >= 1 && origPlay) {
                                    origPlay.call(el).catch(() => {});
                                }
                            }
                        });
                    }
                } catch (e) {}
            };

            const origFetch = window.fetch;

            const refreshWebPlayerToken = async function() {
                try {
                    const r = await origFetch('https://open.spotify.com/get_access_token?reason=transport&productType=web_player', {
                        credentials: 'include'
                    });
                    if (r.ok) {
                        const j = await r.json();
                        if (j && j.accessToken && j.accessToken.length > 25) {
                            window.__claraSpotifyToken = 'Bearer ' + j.accessToken;
                            window.__claraTokenCapturedAt = Date.now();
                            try { window.ClaraHiddenBridge.onTokenCaptured(window.__claraSpotifyToken); } catch (e) {}
                            return window.__claraSpotifyToken;
                        }
                    }
                } catch (e) {}
                return '';
            };

            // Do not call PUT https://api.spotify.com/v1/me/player on boot so we never trigger HTTP 429 rate limits
            window.__claraPreActivateLocalDevice = async function(deviceId) {
                window.__claraDevicePreActivated = true;
            };

            // 3. Intercept fetch() to capture Bearer token, client-token, spclient origin & device_id
            window.fetch = async function(...args) {
                try {
                    const req = args[0];
                    const init = args[1] || {};
                    const reqUrl = (typeof req === 'string') ? req : (req && req.url ? req.url : '');
                    const headers = init.headers || (req && req.headers) || {};
                    let auth = '';
                    let cTok = '';
                    let connId = '';
                    if (typeof headers.get === 'function') {
                        auth = headers.get('Authorization') || headers.get('authorization') || '';
                        cTok = headers.get('client-token') || headers.get('Client-Token') || '';
                        connId = headers.get('x-spotify-connection-id') || headers.get('X-Spotify-Connection-Id') || '';
                    } else if (typeof headers === 'object') {
                        auth = headers['Authorization'] || headers['authorization'] || '';
                        cTok = headers['client-token'] || headers['Client-Token'] || '';
                        connId = headers['x-spotify-connection-id'] || headers['X-Spotify-Connection-Id'] || '';
                    }
                    if (auth && auth.toLowerCase().startsWith('bearer ') && auth.length > 35) {
                        window.__claraSpotifyToken = auth;
                        window.__claraTokenCapturedAt = Date.now();
                        try { window.ClaraHiddenBridge.onTokenCaptured(auth); } catch (e) {}
                    }
                    if (cTok && cTok.length > 20) {
                        window.__claraClientToken = cTok;
                    }
                    if (connId && connId.length > 10) {
                        window.__claraConnectionId = connId;
                    }
                    if (reqUrl.includes('/connect-state/v1/devices/hobs_')) {
                        try {
                            window.__claraSpclientOrigin = new URL(reqUrl).origin;
                        } catch (e) {}
                        const m = reqUrl.match(/(hobs_([A-Za-z0-9_]+))/);
                        if (m && m[2]) {
                            window.__claraHobsDeviceId = m[1];
                            const storedHarmony = String(localStorage.getItem('_spharmony_device_id') || '').replace(/"/g, '').trim();
                            const cleanId = (storedHarmony.length >= 32) ? storedHarmony : m[2];
                            if (window.__claraSdkDeviceId !== cleanId) {
                                window.__claraSdkDeviceId = cleanId;
                                window.__claraDevicePreActivated = true;
                                log('Captured Spotify device_id=' + cleanId + ' hobs=' + m[1] + ' spclient=' + (window.__claraSpclientOrigin || ''));
                                try { window.ClaraHiddenBridge.onDeviceReady(cleanId); } catch (e) {}
                            }
                        }
                    }
                } catch (e) {}
                return origFetch.apply(this, args);
            };

            window.__claraGetFastToken = function(fallbackToken) {
                if (window.__claraSpotifyToken && window.__claraSpotifyToken.length > 35) {
                    return window.__claraSpotifyToken;
                }
                if (fallbackToken && fallbackToken.length > 30) {
                    window.__claraSpotifyToken = fallbackToken.startsWith('Bearer ')
                        ? fallbackToken
                        : ('Bearer ' + fallbackToken);
                    return window.__claraSpotifyToken;
                }
                return '';
            };

            // 4. Instant Play Queue Execution with 401/404 Recovery & ExoPlayer Stream Fallback
            window.__claraPlayQueue = async function(urisJson, startIndex, preferredTargetName, fallbackBearer) {
                const myReqId = ++window.__claraPlayRequestId;
                const sleep = ms => new Promise(r => setTimeout(r, ms));
                window.__claraShouldBePlaying = true;
                window.__claraAllowPause = true;
                setTimeout(() => { window.__claraAllowPause = false; }, 220);

                tryReadLocalStorageDeviceId();

                let uris = [];
                try { uris = JSON.parse(urisJson); } catch (e) {}
                if (!Array.isArray(uris) || uris.length === 0) {
                    notifyFallbackNeeded('empty_uris');
                    return;
                }

                const clampedIdx = Math.max(0, Math.min(startIndex || 0, uris.length - 1));
                const isTabletTarget = !preferredTargetName || preferredTargetName === 'Tablet Speaker';
                let tok = window.__claraGetFastToken(fallbackBearer);
                if (!tok) {
                    tok = await refreshWebPlayerToken();
                }

                const buildHeaders = function(authTok) {
                    const h = {
                        'Authorization': authTok,
                        'Content-Type': 'application/json'
                    };
                    if (window.__claraClientToken) {
                        h['client-token'] = window.__claraClientToken;
                    }
                    return h;
                };

                // Immediately kill & unload any previous track's <audio> elements so the old song never plays for 1s
                window.__claraKillAndPurgeAllMedia();
                window.__claraHardSilenced = false;
                window.__claraShouldBePlaying = true;
                (window.__claraAudioContexts || []).forEach(function(ctx) {
                    try { if (ctx && ctx.state === 'suspended') ctx.resume(); } catch (e) {}
                });

                if (!tok) {
                    tok = await refreshWebPlayerToken();
                }
                if (!tok) {
                    log('[PlayQueue] No Spotify bearer token available yet');
                    return;
                }

                // If Clara passed a search query (e.g. "spotify:search:Title Artist"), resolve it to a spotify:track: URI inside the WebView
                let activeUri = String(uris[clampedIdx] || '');
                if (!activeUri.startsWith('spotify:track:')) {
                    const rawQuery = activeUri.replace(/^spotify:search:/i, '').trim();
                    if (rawQuery) {
                        try {
                            let searchResp = await origFetch(
                                'https://api.spotify.com/v1/search?type=track&limit=1&q=' + encodeURIComponent(rawQuery),
                                { headers: buildHeaders(tok) }
                            );
                            if (searchResp.status === 401) {
                                const refreshed = await refreshWebPlayerToken();
                                if (refreshed) {
                                    tok = refreshed;
                                    searchResp = await origFetch(
                                        'https://api.spotify.com/v1/search?type=track&limit=1&q=' + encodeURIComponent(rawQuery),
                                        { headers: buildHeaders(tok) }
                                    );
                                }
                            }
                            if (searchResp.ok) {
                                const searchJson = await searchResp.json();
                                const firstTrack = searchJson && searchJson.tracks && searchJson.tracks.items && searchJson.tracks.items[0];
                                if (firstTrack && firstTrack.uri) {
                                    uris[clampedIdx] = firstTrack.uri;
                                    activeUri = firstTrack.uri;
                                    log('[PlayQueue] Resolved search "' + rawQuery + '" -> ' + activeUri);
                                }
                            } else {
                                log('[PlayQueue] Search status=' + searchResp.status + ' for "' + rawQuery + '"');
                            }
                        } catch (e) {
                            log('[PlayQueue] Search error: ' + e);
                        }
                    }
                }

                const cleanUris = uris.filter(u => String(u || '').startsWith('spotify:track:'));
                if (cleanUris.length === 0) {
                    log('[PlayQueue] No valid spotify:track: URIs to play from: ' + JSON.stringify(uris));
                    return;
                }
                const finalOffsetIdx = Math.max(0, cleanUris.indexOf(activeUri));

                let targetDeviceId = isTabletTarget
                    ? String(window.__claraSdkDeviceId || '').replace(/^hobs_/, '')
                    : '';

                const resolveDeviceFromApi = async function() {
                    try {
                        let devResp = await origFetch('https://api.spotify.com/v1/me/player/devices', {
                            headers: buildHeaders(tok)
                        });
                        if (devResp.status === 401) {
                            const refreshed = await refreshWebPlayerToken();
                            if (refreshed) {
                                tok = refreshed;
                                devResp = await origFetch('https://api.spotify.com/v1/me/player/devices', {
                                    headers: buildHeaders(tok)
                                });
                            }
                        }
                        if (devResp.ok) {
                            const devJson = await devResp.json();
                            const devs = (devJson && devJson.devices) || [];
                            log('[PlayQueue] Available Spotify Connect devices: ' + devs.map(d => d.name + ':' + d.id).join(', '));
                            if (isTabletTarget) {
                                const webDev = devs.find(d => d && d.name && (
                                    d.name === 'Clara Smart Home' ||
                                    d.name.toLowerCase().includes('web player') ||
                                    d.name.toLowerCase().includes('chrome')
                                ));
                                if (webDev && webDev.id) {
                                    window.__claraSdkDeviceId = webDev.id;
                                    return webDev.id;
                                }
                            } else {
                                const lowerTarget = preferredTargetName.toLowerCase();
                                const matchedSpeaker = devs.find(d => d && d.name && (
                                    d.name.toLowerCase() === lowerTarget ||
                                    d.name.toLowerCase().includes(lowerTarget) ||
                                    lowerTarget.includes(d.name.toLowerCase())
                                ));
                                if (matchedSpeaker && matchedSpeaker.id) {
                                    return matchedSpeaker.id;
                                }
                            }
                        }
                    } catch (e) {}
                    return '';
                };

                for (let attempt = 0; attempt < 5 && !targetDeviceId; attempt++) {
                    if (myReqId !== window.__claraPlayRequestId) return;
                    tryReadLocalStorageDeviceId();
                    if (isTabletTarget && window.__claraSdkDeviceId) {
                        targetDeviceId = String(window.__claraSdkDeviceId).replace(/^hobs_/, '');
                        break;
                    }
                    targetDeviceId = await resolveDeviceFromApi();
                    if (!targetDeviceId) {
                        await sleep(500);
                    }
                }

                if (myReqId !== window.__claraPlayRequestId) return;

                const bodyObj = {
                    uris: cleanUris.slice(0, 50),
                    offset: { position: finalOffsetIdx },
                    position_ms: 0
                };

                const chosenUri = cleanUris[finalOffsetIdx];
                const trackId = chosenUri.replace('spotify:track:', '').trim();
                const storedHarmony = String(localStorage.getItem('_spharmony_device_id') || '').replace(/"/g, '').trim();
                const localCleanId = storedHarmony || String(window.__claraSdkDeviceId || targetDeviceId || '').replace(/^hobs_/, '');
                const localHobsId = window.__claraHobsDeviceId || (localCleanId ? ('hobs_' + localCleanId.slice(0, 35)) : '');
                const effectiveTargetId = targetDeviceId || localCleanId;
                const spclientOrigin = window.__claraSpclientOrigin || 'https://gae2-spclient.spotify.com';

                log('[PlayQueue] Dispatching play uri=' + chosenUri + ' targetDeviceId=' + effectiveTargetId + ' hobs=' + localHobsId + ' target=' + preferredTargetName);

                let commandSucceeded = false;

                // 1. For Tablet Speaker: trigger Spotify Web Player's instant pathname autoplay immediately (0ms delay)
                if (isTabletTarget && trackId) {
                    try {
                        window.history.pushState({}, '', '/track/' + trackId);
                        window.dispatchEvent(new PopStateEvent('popstate'));
                        commandSucceeded = true;
                    } catch (e) {}
                    setTimeout(function() {
                        if (myReqId === window.__claraPlayRequestId && window.__claraShouldBePlaying) {
                            window.__claraAllowPause = false;
                        }
                    }, 350);
                } else {
                    window.__claraAllowPause = false;
                    // Cast speaker path: use Web API when not rate-limited
                    const hitRateLimit429 = Boolean(window.__claraRateLimitedUntil && Date.now() < window.__claraRateLimitedUntil);
                    if (!hitRateLimit429) {
                        try {
                            let playEndpoint = effectiveTargetId
                                ? ('https://api.spotify.com/v1/me/player/play?device_id=' + encodeURIComponent(effectiveTargetId))
                                : 'https://api.spotify.com/v1/me/player/play';

                            let playResp = await origFetch(playEndpoint, {
                                method: 'PUT',
                                headers: buildHeaders(tok),
                                body: JSON.stringify(bodyObj)
                            });
                            log('[PlayQueue] PUT /play response status=' + playResp.status);
                            if (playResp.status === 429) {
                                window.__claraRateLimitedUntil = Date.now() + 90000;
                            } else if (playResp.ok || playResp.status === 204 || playResp.status === 202) {
                                commandSucceeded = true;
                            }
                        } catch (e) {
                            log('[PlayQueue] Direct PUT /play exception: ' + e);
                        }
                    }
                }

                (window.__claraAudioContexts || []).forEach(function(ctx) {
                    try { if (ctx && ctx.state === 'suspended') ctx.resume(); } catch (e) {}
                });

                if (isTabletTarget) {
                    setTimeout(function() {
                        if (myReqId !== window.__claraPlayRequestId || !window.__claraShouldBePlaying) return;
                        const audioEls = (window.__claraMediaElements || []).filter(el => !isVisualCanvasElement(el));
                        const anyPlaying = audioEls.some(el => !el.paused && !el.ended && el.currentTime > 0.05);
                        if (!anyPlaying) {
                            const actionPlayBtn = document.querySelector('[data-testid="action-bar-row"] [data-testid="play-button"]')
                                || document.querySelector('main [data-testid="play-button"]')
                                || document.querySelector('[data-testid="control-button-playpause"]');
                            if (actionPlayBtn) {
                                const aria = (actionPlayBtn.getAttribute('aria-label') || '').toLowerCase();
                                if (!aria.includes('pause')) {
                                    log('[PlayQueue] Clicking in-page play button aria="' + aria + '"');
                                    actionPlayBtn.click();
                                }
                            }
                            window.__claraResumeAllMedia();
                        }
                    }, 500);

                    // Give Widevine DRM (createSession + updateSession) 2400ms before checking fallback
                    const fallbackCheckDelayMs = 2400;
                    setTimeout(function() {
                        if (myReqId !== window.__claraPlayRequestId || !window.__claraShouldBePlaying) return;
                        (window.__claraAudioContexts || []).forEach(function(ctx) {
                            try { if (ctx && ctx.state === 'suspended') ctx.resume(); } catch (e) {}
                        });
                        window.__claraResumeAllMedia();
                        const audioEls = (window.__claraMediaElements || []).filter(el => !isVisualCanvasElement(el));
                        const anyPlaying = audioEls.some(el => !el.paused && !el.ended && (el.currentTime > 0.02 || el.readyState >= 3));
                        log('[PlayQueue] Audio check (' + fallbackCheckDelayMs + 'ms): count=' + audioEls.length + ' anyPlaying=' + anyPlaying);
                        if (!anyPlaying) {
                            notifyFallbackNeeded('webview_silent_fallback');
                        }
                    }, fallbackCheckDelayMs);
                } else if (!commandSucceeded) {
                    notifyFallbackNeeded('cast_command_failed');
                }
            };

            // 5. Local DOM + MediaElement Poller (0 network requests)
            const parseTimeTextToMs = function(str) {
                if (!str) return 0;
                const clean = String(str).trim().replace('-', '');
                const parts = clean.split(':').map(Number);
                if (parts.length === 2 && !isNaN(parts[0]) && !isNaN(parts[1])) {
                    return ((parts[0] * 60) + parts[1]) * 1000;
                }
                if (parts.length === 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
                    return ((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000;
                }
                return 0;
            };

            window.__claraStopStatePoller = function() {
                if (window.__claraStatePoller) {
                    clearInterval(window.__claraStatePoller);
                    window.__claraStatePoller = null;
                }
            };

            window.__claraStartStatePoller = function() {
                if (window.__claraStatePoller) return;
                window.__claraStatePoller = setInterval(function() {
                    try {
                        tryReadLocalStorageDeviceId();

                        document.querySelectorAll('video').forEach(v => {
                            if (isVisualCanvasElement(v) && !v.paused && origPause) {
                                v.muted = true;
                                origPause.call(v);
                            }
                        });

                        const audioEls = (window.__claraMediaElements || []).filter(el => !isVisualCanvasElement(el));
                        const playingEl = audioEls.find(el => !el.paused && !el.ended && (el.currentTime > 0 || el.readyState >= 2));
                        const anyEl = playingEl || window.__claraActiveAudioEl || audioEls.find(el => el.duration > 0 || el.currentTime > 0);

                        if (window.__claraShouldBePlaying && !window.__claraAllowPause && anyEl && anyEl.paused && !anyEl.ended && (anyEl.currentTime || 0) > 0.2) {
                            if (origPlay) origPlay.call(anyEl).catch(() => {});
                        }

                        const posEl = document.querySelector('[data-testid="playback-position"]');
                        const durEl = document.querySelector('[data-testid="playback-duration"]');
                        const playBtn = document.querySelector('[data-testid="control-button-playpause"]');

                        const domPosMs = posEl ? parseTimeTextToMs(posEl.textContent) : 0;
                        const domDurMs = durEl ? parseTimeTextToMs(durEl.textContent) : 0;
                        const btnAria = playBtn ? (playBtn.getAttribute('aria-label') || '').toLowerCase() : '';

                        let isPlaying = false;
                        if (!window.__claraShouldBePlaying || window.__claraHardSilenced) {
                            isPlaying = false;
                        } else if (playingEl) {
                            isPlaying = true;
                        }

                        let posMs = 0;
                        if (anyEl && anyEl.currentTime > 0) {
                            posMs = Math.round(anyEl.currentTime * 1000);
                        } else if (domPosMs > 0) {
                            posMs = domPosMs;
                        }

                        let durMs = 0;
                        if (anyEl && anyEl.duration && isFinite(anyEl.duration) && anyEl.duration > 10) {
                            durMs = Math.round(anyEl.duration * 1000);
                        } else if (domDurMs > 0) {
                            durMs = domDurMs;
                        }

                        const titleEl = document.querySelector('[data-testid="context-item-info-title"]');
                        const subEl = document.querySelector('[data-testid="context-item-info-subtitles"]');
                        const artEl = document.querySelector('[data-testid="cover-art-image"]');
                        const msMeta = (navigator.mediaSession && navigator.mediaSession.metadata) || null;

                        const title = (titleEl && titleEl.textContent ? titleEl.textContent.trim() : '') ||
                            (msMeta && msMeta.title ? msMeta.title : '');
                        const artist = (subEl && subEl.textContent ? subEl.textContent.trim() : '') ||
                            (msMeta && msMeta.artist ? msMeta.artist : '');
                        const artUrl = (artEl && artEl.src ? artEl.src : '') ||
                            (msMeta && Array.isArray(msMeta.artwork) && msMeta.artwork.length > 0
                                ? msMeta.artwork[msMeta.artwork.length - 1].src
                                : '');

                        if (isPlaying || posMs > 0 || durMs > 0 || title) {
                            window.ClaraHiddenBridge.onPlaybackState(JSON.stringify({
                                isPlaying: isPlaying,
                                positionMs: posMs,
                                durationMs: durMs,
                                title: title,
                                artist: artist,
                                artworkUrl: artUrl,
                                trackEnded: false
                            }));
                        }
                    } catch (e) {}
                }, 350);
            };
            window.__claraStartStatePoller();
        })();
    """.trimIndent()

    private fun unlockWebViewAudioGesture(wv: WebView) {
        try {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
            val up = MotionEvent.obtain(now, now + 20L, MotionEvent.ACTION_UP, 100f, 100f, 0)
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        } catch (_: Exception) {
        }
    }

    fun ensureInitialized(context: Context, onTrackEnded: (() -> Unit)? = null) {
        if (onTrackEnded != null) {
            onTrackEndedCallback = onTrackEnded
        }
        installChromiumAudioFocusShield(context)
        val existing = hiddenWebView
        if (existing != null) {
            if (context is Activity) {
                attachToActivityDecorViewIfNeeded(context, existing)
            }
            return
        }
        // Wait until MainActivity provides an Activity context so Widevine DRM and internal speaker audio attach to the window
        if (context !is Activity) {
            return
        }

        val appCtx = context.applicationContext
        val wv = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(DESKTOP_VIEWPORT_WIDTH, DESKTOP_VIEWPORT_HEIGHT)
            translationX = 0f
            translationY = 0f
            z = -100f
            alpha = 0.01f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            val savedCookie = NativeMusicCatalogClient.getSavedSpotifyCookie(appCtx)
            if (savedCookie.isNotBlank()) {
                savedCookie.split(";").forEach { pair ->
                    val cleanPair = pair.trim()
                    if (cleanPair.isNotBlank()) {
                        cm.setCookie("https://open.spotify.com", cleanPair)
                        cm.setCookie("https://spotify.com", cleanPair)
                    }
                }
                cm.flush()
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = DESKTOP_CHROME_UA
            }

            // Grant hardware Widevine DRM (RESOURCE_PROTECTED_MEDIA_ID)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    val req = request ?: return
                    try {
                        req.grant(req.resources)
                    } catch (_: Exception) {
                    }
                }
            }

            addJavascriptInterface(HiddenBridge(), "ClaraHiddenBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(hiddenPlayerBootstrapJs, null)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString().orEmpty()
                    val lowerPath = request?.url?.path?.lowercase().orEmpty()
                    val lowerUrl = reqUrl.lowercase()
                    if (lowerUrl.contains("canvaz.scdn.co") ||
                        lowerUrl.contains("video-fa.scdn.co") ||
                        lowerPath.endsWith(".mp4")
                    ) {
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    if (reqUrl.contains("/connect-state/v1/devices/hobs_")) {
                        val match = Regex("hobs_([A-Za-z0-9_]+)").find(reqUrl)?.groupValues?.getOrNull(1)
                        if (!match.isNullOrBlank() && activeWebPlayerDeviceId != match) {
                            activeWebPlayerDeviceId = match
                            Log.d(TAG, "Intercepted Spotify clean device_id=$activeWebPlayerDeviceId")
                        }
                    }
                    val auth = request?.requestHeaders?.entries
                        ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                        ?.value ?: ""
                    if (auth.startsWith("Bearer ", ignoreCase = true) && auth.length > 35) {
                        NativeMusicCatalogClient.updateCachedSpotifyBearerToken(
                            appCtx,
                            auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    if (view != null) {
                        unlockWebViewAudioGesture(view)
                        view.evaluateJavascript(hiddenPlayerBootstrapJs, null)
                    }
                }
            }

            onResume()
            resumeTimers()
            loadUrl("https://open.spotify.com/")
        }

        hiddenWebView = wv
        attachToActivityDecorViewIfNeeded(context, wv)
    }

    private fun attachToActivityDecorViewIfNeeded(activity: Activity, wv: WebView) {
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            if (wv.parent == null) {
                decor.addView(wv, 0, FrameLayout.LayoutParams(DESKTOP_VIEWPORT_WIDTH, DESKTOP_VIEWPORT_HEIGHT))
            }
            wv.onResume()
            wv.resumeTimers()
        } catch (_: Exception) {
        }
    }

    fun reloadSessionWithCookies(context: Context) {
        val cm = CookieManager.getInstance()
        val savedCookie = NativeMusicCatalogClient.getSavedSpotifyCookie(context)
        if (savedCookie.isNotBlank()) {
            savedCookie.split(";").forEach { pair ->
                val cleanPair = pair.trim()
                if (cleanPair.isNotBlank()) {
                    cm.setCookie("https://open.spotify.com", cleanPair)
                    cm.setCookie("https://spotify.com", cleanPair)
                }
            }
            cm.flush()
        }
        ensureInitialized(context)
        hiddenWebView?.let { wv ->
            wv.onResume()
            wv.resumeTimers()
            wv.loadUrl("https://open.spotify.com/")
        }
    }

    fun playSpotifyUriQueue(
        context: Context,
        trackUris: List<String>,
        startIndex: Int,
        preferredTargetName: String,
        fallbackBearerToken: String
    ) {
        if (trackUris.isEmpty()) return
        isWebPlaybackAuthorized = true
        lastPlayCommandTimestampMs = System.currentTimeMillis()
        ensureInitialized(context)
        scheduleOrCancelIdleSuspension(isPlaying = true)
        val wv = hiddenWebView ?: return
        unlockWebViewAudioGesture(wv)

        val urisArr = JSONArray()
        trackUris.forEach { urisArr.put(it) }
        val safeUrisJson = urisArr.toString().replace("\\", "\\\\").replace("'", "\\'")
        val safeTarget = preferredTargetName.replace("'", "\\'")
        val safeBearer = fallbackBearerToken.replace("'", "")
        val knownDevice = activeWebPlayerDeviceId.replace("'", "")

        wv.post {
            if (!isWebPlaybackAuthorized) return@post
            runCatching {
                wv.evaluateJavascript(
                    """
                    (async function() {
                        if (!window.__claraHiddenPlayerInstalled) {
                            $hiddenPlayerBootstrapJs
                        }
                        if (typeof window.__claraStartStatePoller === 'function') {
                            window.__claraStartStatePoller();
                        }
                        if ('$knownDevice' && !window.__claraSdkDeviceId) {
                            window.__claraSdkDeviceId = '$knownDevice';
                        }
                        if (typeof window.__claraPlayQueue === 'function') {
                            await window.__claraPlayQueue('$safeUrisJson', $startIndex, '$safeTarget', '$safeBearer');
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
    }

    fun skipNext(fallbackBearerToken: String = "") {
        isWebPlaybackAuthorized = true
        val wv = hiddenWebView ?: return
        ClaraInAppMusicPlayer.currentAppContext()?.let { installChromiumAudioFocusShield(it) }
        unlockWebViewAudioGesture(wv)
        val safeBearer = fallbackBearerToken.replace("'", "")
        wv.evaluateJavascript(
            """
            (async function() {
                window.__claraShouldBePlaying = true;
                window.__claraAllowPause = false;
                const nextBtn = document.querySelector('[data-testid="control-button-skip-forward"]');
                if (nextBtn) {
                    nextBtn.click();
                    if (typeof window.__claraResumeAllMedia === 'function') {
                        window.__claraResumeAllMedia();
                    }
                    return;
                }
                const tok = window.__claraGetFastToken ? window.__claraGetFastToken('$safeBearer') : '$safeBearer';
                if (tok) {
                    await fetch('https://api.spotify.com/v1/me/player/next', {
                        method: 'POST',
                        headers: { 'Authorization': tok.startsWith('Bearer ') ? tok : ('Bearer ' + tok) }
                    });
                }
            })();
            """.trimIndent(),
            null
        )
    }

    fun skipPrevious(fallbackBearerToken: String = "") {
        isWebPlaybackAuthorized = true
        val wv = hiddenWebView ?: return
        ClaraInAppMusicPlayer.currentAppContext()?.let { installChromiumAudioFocusShield(it) }
        unlockWebViewAudioGesture(wv)
        val safeBearer = fallbackBearerToken.replace("'", "")
        wv.evaluateJavascript(
            """
            (async function() {
                window.__claraShouldBePlaying = true;
                window.__claraAllowPause = false;
                const prevBtn = document.querySelector('[data-testid="control-button-skip-back"]');
                if (prevBtn) {
                    prevBtn.click();
                    if (typeof window.__claraResumeAllMedia === 'function') {
                        window.__claraResumeAllMedia();
                    }
                    return;
                }
                const tok = window.__claraGetFastToken ? window.__claraGetFastToken('$safeBearer') : '$safeBearer';
                if (tok) {
                    await fetch('https://api.spotify.com/v1/me/player/previous', {
                        method: 'POST',
                        headers: { 'Authorization': tok.startsWith('Bearer ') ? tok : ('Bearer ' + tok) }
                    });
                }
            })();
            """.trimIndent(),
            null
        )
    }

    fun togglePlayPause(shouldPlay: Boolean, @Suppress("UNUSED_PARAMETER") fallbackBearerToken: String) {
        isWebPlaybackAuthorized = shouldPlay
        val wv = hiddenWebView ?: return
        scheduleOrCancelIdleSuspension(isPlaying = shouldPlay)
        if (shouldPlay) {
            unlockWebViewAudioGesture(wv)
        }
        wv.post {
            runCatching {
                wv.evaluateJavascript(
                    """
                    (function() {
                        window.__claraShouldBePlaying = $shouldPlay;
                        window.__claraAllowPause = !$shouldPlay;
                        window.__claraHardSilenced = !$shouldPlay;
                        if (!$shouldPlay) {
                            if (typeof window.__claraPauseAllMedia === 'function') {
                                window.__claraPauseAllMedia();
                            }
                            const playBtn = document.querySelector('[data-testid="control-button-playpause"]');
                            if (playBtn) {
                                const aria = (playBtn.getAttribute('aria-label') || '').toLowerCase();
                                if (aria.includes('pause')) {
                                    playBtn.click();
                                }
                            }
                        } else {
                            if (typeof window.__claraResumeAllMedia === 'function') {
                                window.__claraResumeAllMedia();
                            }
                            const playBtn = document.querySelector('[data-testid="control-button-playpause"]');
                            if (playBtn) {
                                const aria = (playBtn.getAttribute('aria-label') || '').toLowerCase();
                                if (aria.includes('play')) {
                                    playBtn.click();
                                }
                            }
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
    }

    fun pausePlayback() {
        hardStopAndSilence()
    }

    fun hardStopAndSilence() {
        isWebPlaybackAuthorized = false
        scheduleOrCancelIdleSuspension(isPlaying = false)
        val wv = hiddenWebView ?: return
        wv.post {
            if (isWebPlaybackAuthorized) return@post
            runCatching {
                wv.evaluateJavascript(
                    """
                    (function() {
                        window.__claraHardSilenced = true;
                        window.__claraShouldBePlaying = false;
                        window.__claraAllowPause = true;
                        window.__claraPlayRequestId = (window.__claraPlayRequestId || 0) + 1;
                        if (typeof window.__claraStopStatePoller === 'function') {
                            window.__claraStopStatePoller();
                        }
                        if (typeof window.__claraKillAndPurgeAllMedia === 'function') {
                            window.__claraKillAndPurgeAllMedia();
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
    }

    fun seekTo(positionMs: Long, fallbackBearerToken: String) {
        if (!isWebPlaybackAuthorized) return
        val wv = hiddenWebView ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        val seconds = clamped / 1000.0
        val safeBearer = fallbackBearerToken.replace("'", "")
        wv.evaluateJavascript(
            """
            (async function() {
                if (window.__claraActiveAudioEl && window.__claraActiveAudioEl.duration > 0) {
                    try {
                        window.__claraActiveAudioEl.currentTime = $seconds;
                        return;
                    } catch (e) {}
                }
                const tok = window.__claraGetFastToken ? window.__claraGetFastToken('$safeBearer') : '$safeBearer';
                if (tok) {
                    await fetch('https://api.spotify.com/v1/me/player/seek?position_ms=$clamped', {
                        method: 'PUT',
                        headers: { 'Authorization': tok.startsWith('Bearer ') ? tok : ('Bearer ' + tok) }
                    });
                }
            })();
            """.trimIndent(),
            null
        )
    }

    fun setVoiceDucking(isDucked: Boolean) {
        if (!isWebPlaybackAuthorized) return
        val wv = hiddenWebView ?: return
        val duckFactor = if (isDucked) 0.18f else 1.0f
        wv.evaluateJavascript(
            """
            (function() {
                window.__claraVoiceDuckFactor = $duckFactor;
                if (!window.__claraShouldBePlaying) return;
                const base = (typeof window.__claraDesiredVolume === 'number') ? window.__claraDesiredVolume : 1.0;
                const target = Math.max(0.0, Math.min(1.0, base * $duckFactor));
                (window.__claraMediaElements || []).forEach(el => {
                    try {
                        el.volume = target;
                    } catch (e) {}
                });
            })();
            """.trimIndent(),
            null
        )
    }

    fun setVolumePercent(volumePercent: Float, fallbackBearerToken: String) {
        if (!isWebPlaybackAuthorized) return
        val wv = hiddenWebView ?: return
        val clamped = volumePercent.roundToInt().coerceIn(0, 100)
        val fraction = (clamped / 100f).coerceIn(0f, 1f)
        val safeBearer = fallbackBearerToken.replace("'", "")
        wv.evaluateJavascript(
            """
            (function() {
                window.__claraDesiredVolume = $fraction;
                if (!window.__claraShouldBePlaying) return;
                const duck = (typeof window.__claraVoiceDuckFactor === 'number') ? window.__claraVoiceDuckFactor : 1.0;
                const target = Math.max(0.0, Math.min(1.0, $fraction * duck));
                (window.__claraMediaElements || []).forEach(el => {
                    try {
                        el.volume = target;
                    } catch (e) {}
                });
            })();
            """.trimIndent(),
            null
        )

        if (JarvisLiveBridge.activeCastTargetNameFlow.value != "Tablet Speaker") {
            volumeDebounceJob?.cancel()
            volumeDebounceJob = CoroutineScope(Dispatchers.Main).launch {
                delay(450L)
                wv.evaluateJavascript(
                    """
                    (async function() {
                        const tok = window.__claraGetFastToken ? window.__claraGetFastToken('$safeBearer') : '$safeBearer';
                        if (tok) {
                            await fetch('https://api.spotify.com/v1/me/player/volume?volume_percent=$clamped', {
                                method: 'PUT',
                                headers: { 'Authorization': tok.startsWith('Bearer ') ? tok : ('Bearer ' + tok) }
                            });
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
    }

    fun transferPlaybackToTarget(targetName: String, fallbackBearerToken: String) {
        val wv = hiddenWebView ?: return
        wv.onResume()
        wv.resumeTimers()
        unlockWebViewAudioGesture(wv)

        val safeTarget = targetName.replace("'", "\\'")
        val safeBearer = fallbackBearerToken.replace("'", "")
        val knownDevice = activeWebPlayerDeviceId.replace("'", "")

        wv.evaluateJavascript(
            """
            (async function() {
                const sleep = ms => new Promise(r => setTimeout(r, ms));
                const tok = window.__claraGetFastToken ? window.__claraGetFastToken('$safeBearer') : '$safeBearer';
                if (!tok) return;
                const authHeader = tok.startsWith('Bearer ') ? tok : ('Bearer ' + tok);
                let chosenId = '';
                if ('$safeTarget' === 'Tablet Speaker') {
                    chosenId = window.__claraSdkDeviceId || '$knownDevice' || '';
                }
                if (!chosenId) {
                    const r = await fetch('https://api.spotify.com/v1/me/player/devices', {
                        headers: { 'Authorization': authHeader }
                    });
                    if (r.ok) {
                        const j = await r.json();
                        const devs = (j && j.devices) || [];
                        if ('$safeTarget' === 'Tablet Speaker') {
                            const web = devs.find(d => d && d.name && (
                                d.name === 'Clara Smart Home' ||
                                d.name.toLowerCase().includes('web player') ||
                                d.name.toLowerCase().includes('chrome')
                            ));
                            chosenId = (web && web.id) || '';
                            if (chosenId) window.__claraSdkDeviceId = chosenId;
                        } else {
                            const lower = '$safeTarget'.toLowerCase();
                            const matched = devs.find(d => d && d.name && (
                                d.name.toLowerCase() === lower ||
                                d.name.toLowerCase().includes(lower) ||
                                lower.includes(d.name.toLowerCase())
                            ));
                            if (matched && matched.id) chosenId = matched.id;
                        }
                    }
                }
                if (chosenId) {
                    window.__claraShouldBePlaying = true;
                    window.__claraAllowPause = false;
                    await fetch('https://api.spotify.com/v1/me/player', {
                        method: 'PUT',
                        headers: {
                            'Authorization': authHeader,
                            'Content-Type': 'application/json'
                        },
                        body: JSON.stringify({ device_ids: [chosenId], play: true })
                    });
                    if ('$safeTarget' === 'Tablet Speaker') {
                        await sleep(180);
                        if (typeof window.__claraResumeAllMedia === 'function') {
                            window.__claraResumeAllMedia();
                        }
                    }
                }
            })();
            """.trimIndent(),
            null
        )
    }
}
