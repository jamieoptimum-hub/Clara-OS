package com.jarvis.smarthomelauncher.integrations

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// REAL PAGINATED SPOTIFY / YT MUSIC SYNC, COVER ART & CATALOG CLIENT
// - Uses clean v7 storage with full `artworkUrl`, `spotify:track:` URI, and
//   11-character YouTube `videoId` persistence
// - Searches Spotify's official `/v1/search` API when Spotify is active and
//   YouTube Music's `youtubei/v1/search` (`WEB_REMIX`) when YouTube Music is active
// - Skips the secondary search round-trip whenever a track already carries a `videoId`
// ---------------------------------------------------------------------------
object NativeMusicCatalogClient {
    private const val TAG = "NativeMusicSync"
    private const val PREFS_NAME = "clara_native_music_prefs_v7"

    private const val KEY_SPOTIFY_ACCESS_TOKEN = "spotify_access_token"
    private const val KEY_SPOTIFY_CLIENT_TOKEN = "spotify_client_token"
    private const val KEY_SPOTIFY_COOKIE = "spotify_sp_dc_cookie"
    private const val KEY_SPOTIFY_EXPIRY_MS = "spotify_expiry_ms"

    private const val KEY_YT_SIGNED_IN = "yt_web_signed_in"
    private const val KEY_YT_COOKIE = "yt_web_cookie"

    private const val KEY_SAVED_SPOTIFY_ITEMS = "saved_spotify_items_json_v7"
    private const val KEY_SAVED_YT_ITEMS = "saved_yt_items_json_v7"
    private const val KEY_LIKED_TRACKS_JSON = "saved_liked_tracks_json_v7"
    private const val KEY_PLAYLIST_TRACKS_PREFIX = "cached_real_playlist_tracks_v7_"

    private const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val artworkUrlCache = ConcurrentHashMap<String, String>()
    private val isRefreshingTokenInBackground = AtomicBoolean(false)

    private val BLOCKED_SPOTIFY_UI_TITLES = setOf(
        "liked songs",
        "your library",
        "create playlist",
        "getting started",
        "your episodes",
        "new episodes",
        "episodes",
        "home",
        "search",
        "browse",
        "playlists",
        "by you"
    )

    private val UNWANTED_VERSION_KEYWORDS = listOf(
        "acoustic",
        "karaoke",
        "tribute",
        "instrumental",
        "cover",
        "8d audio",
        "slowed",
        "nightcore",
        "ringtone",
        "backing track",
        "piano version",
        "guitar version"
    )

    private val SPOTIFY_ID_REGEX = Regex("^[A-Za-z0-9]{22}$")
    private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isValidSpotifyId(rawId: String): Boolean {
        val clean = rawId
            .removePrefix("sp_pl_")
            .removePrefix("sp_tr_")
            .removePrefix("sp_liked_")
            .removePrefix("spotify:playlist:")
            .removePrefix("spotify:track:")
            .substringBefore("?")
            .trim()
        return SPOTIFY_ID_REGEX.matches(clean)
    }

    fun extractCleanSpotifyPlaylistId(rawIdOrUri: String): String {
        val afterPrefix = rawIdOrUri
            .removePrefix("sp_pl_")
            .removePrefix("spotify:playlist:")
            .substringAfter("playlist/", rawIdOrUri.removePrefix("sp_pl_").removePrefix("spotify:playlist:"))
            .substringBefore("?")
            .substringBefore("/")
            .trim()
        return if (SPOTIFY_ID_REGEX.matches(afterPrefix)) afterPrefix else ""
    }

    fun extractSpotifyTrackUri(item: NativeMusicItem): String {
        val fromUri = item.playbackUriOrQuery
            .removePrefix("spotify:track:")
            .substringAfter("track/", item.playbackUriOrQuery.removePrefix("spotify:track:"))
            .substringBefore("?")
            .trim()
        if (SPOTIFY_ID_REGEX.matches(fromUri)) {
            return "spotify:track:$fromUri"
        }
        val fromId = item.id
            .removePrefix("sp_tr_")
            .removePrefix("sp_liked_")
            .removePrefix("spotify:track:")
            .substringBefore("_")
            .trim()
        if (SPOTIFY_ID_REGEX.matches(fromId)) {
            return "spotify:track:$fromId"
        }
        return ""
    }

    fun getSavedSpotifyCookie(context: Context): String =
        prefs(context).getString(KEY_SPOTIFY_COOKIE, "") ?: ""

    fun getSavedSpotifyClientToken(context: Context): String =
        prefs(context).getString(KEY_SPOTIFY_CLIENT_TOKEN, "") ?: ""

    fun getSavedYouTubeCookie(context: Context): String =
        prefs(context).getString(KEY_YT_COOKIE, "") ?: ""

    /**
     * Returns the cached Spotify Bearer token immediately without blocking the caller thread.
     * If the token is near expiry or missing, triggers a background cookie refresh so the
     * token stays warm without adding latency to track playback.
     */
    fun getCachedSpotifyTokenSync(context: Context): String {
        val p = prefs(context)
        val token = p.getString(KEY_SPOTIFY_ACCESS_TOKEN, "") ?: ""
        val expiry = p.getLong(KEY_SPOTIFY_EXPIRY_MS, 0L)
        val now = System.currentTimeMillis()
        if ((token.length < 25 || now >= expiry - 120_000L) &&
            isRefreshingTokenInBackground.compareAndSet(false, true)
        ) {
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    refreshSpotifyTokenFromCookie(appCtx)
                } finally {
                    isRefreshingTokenInBackground.set(false)
                }
            }
        }
        return token
    }

    fun updateCachedSpotifyBearerToken(context: Context, cleanToken: String) {
        if (cleanToken.length > 25) {
            prefs(context).edit()
                .putString(KEY_SPOTIFY_ACCESS_TOKEN, cleanToken)
                .putLong(KEY_SPOTIFY_EXPIRY_MS, System.currentTimeMillis() + 3500_000L)
                .apply()
        }
    }

    fun isSpotifySignedIn(context: Context): Boolean =
        (prefs(context).getString(KEY_SPOTIFY_ACCESS_TOKEN, "") ?: "").isNotBlank() ||
                getSavedSpotifyCookie(context).isNotBlank()

    fun isYouTubeSignedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_YT_SIGNED_IN, false)

    fun signOutProvider(context: Context, provider: MusicProvider) {
        val p = prefs(context)
        val allKeys = p.all.keys.toList()
        p.edit().apply {
            if (provider == MusicProvider.SPOTIFY) {
                remove(KEY_SPOTIFY_ACCESS_TOKEN)
                remove(KEY_SPOTIFY_CLIENT_TOKEN)
                remove(KEY_SPOTIFY_COOKIE)
                remove(KEY_SPOTIFY_EXPIRY_MS)
                remove(KEY_SAVED_SPOTIFY_ITEMS)
                remove(KEY_LIKED_TRACKS_JSON)
                allKeys.filter { it.startsWith(KEY_PLAYLIST_TRACKS_PREFIX) }.forEach { remove(it) }
            } else {
                putBoolean(KEY_YT_SIGNED_IN, false)
                remove(KEY_YT_COOKIE)
                remove(KEY_SAVED_YT_ITEMS)
            }
            apply()
        }
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) {
        }
    }

    fun saveWebCapturedSpotifySession(
        context: Context,
        bearerToken: String,
        cookieHeader: String,
        extractedPlaylistsJson: String = "",
        extractedLikedTracksJson: String = "",
        extractedPlaylistTracksMapJson: String = "",
        clientToken: String = ""
    ) {
        ClaraInAppMusicPlayer.initContextIfNeeded(context)
        val cleanToken = bearerToken.removePrefix("Bearer ").removePrefix("bearer ").trim()
        val cleanClientToken = clientToken.trim()
        Log.d(
            TAG,
            "saveWebCapturedSpotifySession: tokenLen=${cleanToken.length}, clientTokenLen=${cleanClientToken.length}, " +
                    "playlistsJsonLen=${extractedPlaylistsJson.length}, likedLen=${extractedLikedTracksJson.length}"
        )

        prefs(context).edit().apply {
            if (cleanToken.length > 20) {
                putString(KEY_SPOTIFY_ACCESS_TOKEN, cleanToken)
                putLong(KEY_SPOTIFY_EXPIRY_MS, System.currentTimeMillis() + 3500_000L)
            }
            if (cleanClientToken.length > 20) {
                putString(KEY_SPOTIFY_CLIENT_TOKEN, cleanClientToken)
            }
            if (cookieHeader.isNotBlank()) {
                putString(KEY_SPOTIFY_COOKIE, cookieHeader)
            }
            apply()
        }

        CoroutineScope(Dispatchers.Main).launch {
            SpotifyHiddenWebPlayer.reloadSessionWithCookies(context)
        }

        if (extractedLikedTracksJson.isNotBlank() && extractedLikedTracksJson != "[]") {
            try {
                val arr = JSONArray(extractedLikedTracksJson)
                val likedList = mutableListOf<NativeMusicItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val tName = o.optString("title", "").trim()
                    val tArtist = o.optString("subtitle", "").replace('\u00A0', ' ').trim()
                    val rawTrackId = o.optString("id", "sp_liked_$i").trim()
                    val cleanTrackId = rawTrackId.removePrefix("sp_liked_").removePrefix("sp_tr_").removePrefix("spotify:track:").trim()
                    val tId = if (rawTrackId.startsWith("sp_liked_")) rawTrackId else "sp_liked_$cleanTrackId"
                    val uriOrQuery = if (SPOTIFY_ID_REGEX.matches(cleanTrackId)) "spotify:track:$cleanTrackId" else "$tName $tArtist".trim()
                    val artUrl = o.optString("artworkUrl", "").trim()
                    val durMs = o.optLong("durationMs", 0L)
                    if (tName.isNotBlank()) {
                        likedList.add(
                            NativeMusicItem(
                                id = tId,
                                title = tName,
                                subtitle = tArtist,
                                provider = MusicProvider.SPOTIFY,
                                itemType = NativeMusicItemType.TRACK,
                                playbackUriOrQuery = uriOrQuery,
                                artworkUrl = artUrl,
                                durationMs = durMs,
                                isSavedInLibrary = true
                            )
                        )
                    }
                }
                if (likedList.isNotEmpty()) {
                    val dedupedLiked = likedList.distinctBy { "${it.title.lowercase()}::${it.subtitle.lowercase()}" }
                    replaceSavedLikedTracks(context, dedupedLiked)
                    saveCachedPlaylistTracks(context, "${MusicProvider.SPOTIFY.name}_liked", dedupedLiked)
                }
            } catch (_: Exception) {
            }
        }

        if (extractedPlaylistTracksMapJson.isNotBlank() && extractedPlaylistTracksMapJson != "{}") {
            try {
                val mapObj = JSONObject(extractedPlaylistTracksMapJson)
                val keys = mapObj.keys()
                while (keys.hasNext()) {
                    val plKey = keys.next()
                    val cleanPlId = extractCleanSpotifyPlaylistId(plKey)
                    val trArr = mapObj.optJSONArray(plKey) ?: continue
                    val list = mutableListOf<NativeMusicItem>()
                    for (i in 0 until trArr.length()) {
                        val o = trArr.optJSONObject(i) ?: continue
                        val tName = o.optString("title", "").trim()
                        val tArtist = o.optString("subtitle", "").replace('\u00A0', ' ').trim()
                        val rawTrId = o.optString("id", "").removePrefix("sp_tr_").removePrefix("spotify:track:").trim()
                        val uriOrQuery = if (SPOTIFY_ID_REGEX.matches(rawTrId)) "spotify:track:$rawTrId" else "$tName $tArtist".trim()
                        val artUrl = o.optString("artworkUrl", "").trim()
                        val durMs = o.optLong("durationMs", 0L)
                        if (tName.isNotBlank()) {
                            list.add(
                                NativeMusicItem(
                                    id = if (SPOTIFY_ID_REGEX.matches(rawTrId)) "sp_tr_$rawTrId" else "sp_pltr_${cleanPlId.ifBlank { plKey }}_$i",
                                    title = tName,
                                    subtitle = tArtist,
                                    provider = MusicProvider.SPOTIFY,
                                    itemType = NativeMusicItemType.TRACK,
                                    playbackUriOrQuery = uriOrQuery,
                                    artworkUrl = artUrl,
                                    durationMs = durMs,
                                    isSavedInLibrary = true
                                )
                            )
                        }
                    }
                    if (list.isNotEmpty()) {
                        val normalizedKey = if (cleanPlId.isNotBlank()) "sp_pl_$cleanPlId"
                        else if (plKey.startsWith("sp_pl_")) plKey
                        else "sp_pl_$plKey"
                        saveCachedPlaylistTracks(context, normalizedKey, list)
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (extractedPlaylistsJson.isNotBlank() && extractedPlaylistsJson != "[]") {
            try {
                val arr = JSONArray(extractedPlaylistsJson)
                val synced = mutableListOf<NativeMusicItem>()
                val likedCount = loadSavedLikedTracks(context, MusicProvider.SPOTIFY).size
                synced.add(
                    NativeMusicItem(
                        id = "${MusicProvider.SPOTIFY.name}_liked",
                        title = "Liked Songs",
                        subtitle = if (likedCount > 0) "Playlist \u2022 $likedCount saved tracks" else "Playlist \u2022 Your saved Spotify tracks",
                        provider = MusicProvider.SPOTIFY,
                        itemType = NativeMusicItemType.PLAYLIST,
                        playbackUriOrQuery = "spotify:collection:tracks",
                        artworkUrl = "https://misc.scdn.co/liked-songs/liked-songs-300.png",
                        trackCount = likedCount,
                        isSavedInLibrary = true
                    )
                )
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val rawTitle = o.optString("title", "").trim()
                    val rawSub = o.optString("subtitle", "Spotify Playlist").replace('\u00A0', ' ').trim()
                    val rawId = o.optString("id", "").trim()
                    val artUrl = o.optString("artworkUrl", "").trim()
                    val count = o.optInt("trackCount", 0)
                    val cleanPlId = extractCleanSpotifyPlaylistId(rawId)

                    if (rawTitle.isBlank() || rawTitle.lowercase() in BLOCKED_SPOTIFY_UI_TITLES) {
                        continue
                    }
                    if (cleanPlId.isBlank()) {
                        continue
                    }

                    synced.add(
                        NativeMusicItem(
                            id = "sp_pl_$cleanPlId",
                            title = rawTitle,
                            subtitle = rawSub.ifBlank { "Spotify Playlist" },
                            provider = MusicProvider.SPOTIFY,
                            itemType = NativeMusicItemType.PLAYLIST,
                            playbackUriOrQuery = "spotify:playlist:$cleanPlId",
                            artworkUrl = artUrl,
                            trackCount = count,
                            isSavedInLibrary = true
                        )
                    )
                }
                if (synced.size > 1) {
                    val deduped = synced.distinctBy { item ->
                        if (item.id.startsWith("sp_pl_")) item.id else item.title.lowercase()
                    }
                    saveLibraryList(context, MusicProvider.SPOTIFY, deduped)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun saveWebCapturedYouTubeSession(
        context: Context,
        cookieHeader: String,
        extractedPlaylistsJson: String = "",
        extractedLikedTracksJson: String = ""
    ) {
        ClaraInAppMusicPlayer.initContextIfNeeded(context)
        prefs(context).edit().apply {
            putBoolean(KEY_YT_SIGNED_IN, true)
            if (cookieHeader.isNotBlank()) {
                putString(KEY_YT_COOKIE, cookieHeader)
            }
            apply()
        }

        if (extractedLikedTracksJson.isNotBlank()) {
            try {
                val arr = JSONArray(extractedLikedTracksJson)
                val liked = mutableListOf<NativeMusicItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title", "").trim()
                    val subtitle = o.optString("subtitle", "").trim()
                    val artUrl = o.optString("artworkUrl", "").trim()
                    val rawVid = o.optString("videoId", "").trim()
                    if (title.isNotBlank()) {
                        liked.add(
                            NativeMusicItem(
                                id = if (YOUTUBE_VIDEO_ID_REGEX.matches(rawVid)) "yt_vid_$rawVid" else "yt_liked_$i",
                                title = title,
                                subtitle = subtitle,
                                provider = MusicProvider.YOUTUBE_MUSIC,
                                itemType = NativeMusicItemType.TRACK,
                                playbackUriOrQuery = if (YOUTUBE_VIDEO_ID_REGEX.matches(rawVid)) rawVid else "$title $subtitle".trim(),
                                artworkUrl = artUrl,
                                isSavedInLibrary = true
                            )
                        )
                    }
                }
                if (liked.isNotEmpty()) {
                    replaceSavedLikedTracks(context, liked)
                }
            } catch (_: Exception) {
            }
        }

        if (extractedPlaylistsJson.isNotBlank()) {
            try {
                val arr = JSONArray(extractedPlaylistsJson)
                val synced = mutableListOf<NativeMusicItem>()
                synced.add(
                    NativeMusicItem(
                        id = "${MusicProvider.YOUTUBE_MUSIC.name}_liked",
                        title = "Liked Songs",
                        subtitle = "Your liked YouTube Music tracks",
                        provider = MusicProvider.YOUTUBE_MUSIC,
                        itemType = NativeMusicItemType.PLAYLIST,
                        playbackUriOrQuery = "Liked Songs",
                        isSavedInLibrary = true
                    )
                )
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title", "").trim()
                    val subtitle = o.optString("subtitle", "YouTube Music Playlist").trim()
                    val artUrl = o.optString("artworkUrl", "").trim()
                    if (title.isNotBlank() && !title.equals("Liked Songs", true) && !title.equals("New playlist", true)) {
                        synced.add(
                            NativeMusicItem(
                                id = "yt_web_pl_$i",
                                title = title,
                                subtitle = subtitle.ifBlank { "YouTube Music Playlist" },
                                provider = MusicProvider.YOUTUBE_MUSIC,
                                itemType = NativeMusicItemType.PLAYLIST,
                                playbackUriOrQuery = title,
                                artworkUrl = artUrl,
                                isSavedInLibrary = true
                            )
                        )
                    }
                }
                if (synced.size > 1) {
                    saveLibraryList(context, MusicProvider.YOUTUBE_MUSIC, synced.distinctBy { it.title.lowercase() })
                }
            } catch (_: Exception) {
            }
        }
    }

    internal suspend fun getValidSpotifyTokenInternal(context: Context): String? =
        getValidSpotifyToken(context)

    private suspend fun getValidSpotifyToken(context: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val currentToken = p.getString(KEY_SPOTIFY_ACCESS_TOKEN, "") ?: ""
        val expiry = p.getLong(KEY_SPOTIFY_EXPIRY_MS, 0L)
        if (currentToken.length > 20 && System.currentTimeMillis() < expiry) {
            return@withContext currentToken
        }

        val refreshed = refreshSpotifyTokenFromCookie(context)
        if (!refreshed.isNullOrBlank()) {
            return@withContext refreshed
        }

        currentToken.takeIf { it.length > 20 }
    }

    private fun refreshSpotifyTokenFromCookie(context: Context): String? {
        val p = prefs(context)
        val cookie = getSavedSpotifyCookie(context)
        if (!cookie.contains("sp_dc=")) return null
        try {
            val tokenUrl = URL("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            val conn = (tokenUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", DESKTOP_CHROME_UA)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val accessToken = json.optString("accessToken", "").trim()
                val expMs = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 3500_000L)
                if (accessToken.length > 25) {
            p.edit()
                .putString(KEY_SPOTIFY_ACCESS_TOKEN, accessToken)
                .putLong(KEY_SPOTIFY_EXPIRY_MS, expMs)
                .apply()
            return accessToken
        }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun applySpotifyAuthHeaders(conn: HttpURLConnection, context: Context, bearerToken: String) {
        conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", DESKTOP_CHROME_UA)
        val clientToken = getSavedSpotifyClientToken(context)
        if (clientToken.length > 20) {
            conn.setRequestProperty("client-token", clientToken)
        }
    }

    suspend fun lookupSpotifyTrackUriBySearch(
        context: Context,
        title: String,
        artist: String
    ): String? = withContext(Dispatchers.IO) {
        val token = getValidSpotifyToken(context) ?: return@withContext null
        val q = "$title $artist".trim()
        if (q.isBlank()) return@withContext null
        try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val conn = (URL("https://api.spotify.com/v1/search?q=$encoded&type=track&limit=1").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                applySpotifyAuthHeaders(this, context, token)
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val firstItem = JSONObject(body)
                    .optJSONObject("tracks")
                    ?.optJSONArray("items")
                    ?.optJSONObject(0)
                val uri = firstItem?.optString("uri", "")?.trim() ?: ""
                if (uri.startsWith("spotify:track:")) return@withContext uri
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        null
    }

    private suspend fun searchSpotifyCatalogTracks(
        context: Context,
        query: String
    ): List<NativeMusicItem> = withContext(Dispatchers.IO) {
        val token = getValidSpotifyToken(context) ?: return@withContext emptyList()
        val list = mutableListOf<NativeMusicItem>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val conn = (URL("https://api.spotify.com/v1/search?q=$encoded&type=track&limit=26").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                applySpotifyAuthHeaders(this, context, token)
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val tr = items.optJSONObject(i) ?: continue
                    val parsed = parseSpotifyTrackJsonNode(tr, i, MusicProvider.SPOTIFY)
                    if (parsed != null) {
                        list.add(parsed)
                    }
                }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        list
    }

    fun loadLibraryAndPlaylists(
        context: Context,
        provider: MusicProvider
    ): List<NativeMusicItem> {
        ClaraInAppMusicPlayer.initContextIfNeeded(context)
        val key = if (provider == MusicProvider.SPOTIFY) KEY_SAVED_SPOTIFY_ITEMS else KEY_SAVED_YT_ITEMS
        val raw = prefs(context).getString(key, null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                val list = mutableListOf<NativeMusicItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", UUID.randomUUID().toString())
                    val title = o.optString("title", "Playlist").trim()
                    if (provider == MusicProvider.SPOTIFY) {
                        if (id.startsWith("sp_dom_pl_")) continue
                        if (title.lowercase() in BLOCKED_SPOTIFY_UI_TITLES && !id.endsWith("_liked")) continue
                    }
                    val parsedType = runCatching {
                        NativeMusicItemType.valueOf(o.optString("itemType", NativeMusicItemType.PLAYLIST.name))
                    }.getOrDefault(NativeMusicItemType.PLAYLIST)
                    val savedArt = o.optString("artworkUrl", "").trim().ifBlank {
                        if (id.endsWith("_liked") && provider == MusicProvider.SPOTIFY) {
                            "https://misc.scdn.co/liked-songs/liked-songs-300.png"
                        } else ""
                    }
                    list.add(
                        NativeMusicItem(
                            id = id,
                            title = title,
                            subtitle = o.optString("subtitle", ""),
                            provider = provider,
                            itemType = parsedType,
                            playbackUriOrQuery = o.optString("playbackUriOrQuery", ""),
                            streamUrl = o.optString("streamUrl", ""),
                            artworkUrl = savedArt,
                            durationMs = o.optLong("durationMs", 0L),
                            trackCount = o.optInt("trackCount", 0),
                            isSavedInLibrary = true
                        )
                    )
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        val likedCount = loadSavedLikedTracks(context, provider).size
        return listOf(
            NativeMusicItem(
                id = "${provider.name}_liked",
                title = "Liked Songs",
                subtitle = if (likedCount > 0) "Playlist \u2022 $likedCount saved tracks"
                else "Tap 'Sync Account' to import your ${provider.label} playlists & songs",
                provider = provider,
                itemType = NativeMusicItemType.PLAYLIST,
                playbackUriOrQuery = "spotify:collection:tracks",
                artworkUrl = if (provider == MusicProvider.SPOTIFY) "https://misc.scdn.co/liked-songs/liked-songs-300.png" else "",
                trackCount = likedCount,
                isSavedInLibrary = true
            )
        )
    }

    private fun saveLibraryList(
        context: Context,
        provider: MusicProvider,
        items: List<NativeMusicItem>
    ) {
        val key = if (provider == MusicProvider.SPOTIFY) KEY_SAVED_SPOTIFY_ITEMS else KEY_SAVED_YT_ITEMS
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("subtitle", item.subtitle)
                    put("itemType", item.itemType.name)
                    put("playbackUriOrQuery", item.playbackUriOrQuery)
                    put("streamUrl", item.streamUrl)
                    put("artworkUrl", item.artworkUrl)
                    put("durationMs", item.durationMs)
                    put("trackCount", item.trackCount)
                }
            )
        }
        prefs(context).edit().putString(key, arr.toString()).apply()
    }

    private fun saveCachedPlaylistTracks(
        context: Context,
        playlistId: String,
        tracks: List<NativeMusicItem>
    ) {
        if (tracks.isEmpty()) return
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("subtitle", t.subtitle)
                    put("playbackUriOrQuery", t.playbackUriOrQuery)
                    put("streamUrl", t.streamUrl)
                    put("artworkUrl", t.artworkUrl)
                    put("durationMs", t.durationMs)
                }
            )
        }
        prefs(context).edit().putString(KEY_PLAYLIST_TRACKS_PREFIX + playlistId, arr.toString()).apply()
    }

    private fun loadCachedPlaylistTracks(
        context: Context,
        playlistId: String,
        playlistTitle: String,
        provider: MusicProvider
    ): List<NativeMusicItem> {
        val raw = prefs(context).getString(KEY_PLAYLIST_TRACKS_PREFIX + playlistId, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<NativeMusicItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    NativeMusicItem(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        title = o.optString("title", "Song"),
                        subtitle = o.optString("subtitle", ""),
                        provider = provider,
                        itemType = NativeMusicItemType.TRACK,
                        playbackUriOrQuery = o.optString("playbackUriOrQuery", ""),
                        streamUrl = o.optString("streamUrl", ""),
                        artworkUrl = o.optString("artworkUrl", ""),
                        durationMs = o.optLong("durationMs", 0L),
                        isSavedInLibrary = true
                    )
                )
            }
            if (list.size >= 5 && playlistTitle.isNotBlank() &&
                list.count { it.title.equals(playlistTitle.trim(), ignoreCase = true) } >= list.size / 2
            ) {
                prefs(context).edit().remove(KEY_PLAYLIST_TRACKS_PREFIX + playlistId).apply()
                return emptyList()
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveItemToLocalLibrary(context: Context, item: NativeMusicItem): List<NativeMusicItem> {
        if (item.itemType == NativeMusicItemType.TRACK) {
            saveTrackToLikedSongs(context, item)
        }
        val current = loadLibraryAndPlaylists(context, item.provider).toMutableList()
        if (current.none { it.title.equals(item.title, ignoreCase = true) && it.subtitle.equals(item.subtitle, ignoreCase = true) }) {
            current.add(0, item.copy(isSavedInLibrary = true))
            saveLibraryList(context, item.provider, current)
        }
        return current
    }

    private fun replaceSavedLikedTracks(context: Context, tracks: List<NativeMusicItem>) {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("subtitle", t.subtitle)
                    put("playbackUriOrQuery", t.playbackUriOrQuery)
                    put("streamUrl", t.streamUrl)
                    put("artworkUrl", t.artworkUrl)
                    put("durationMs", t.durationMs)
                }
            )
        }
        prefs(context).edit().putString(KEY_LIKED_TRACKS_JSON, arr.toString()).apply()
    }

    private fun saveTrackToLikedSongs(context: Context, track: NativeMusicItem) {
        val existing = loadSavedLikedTracks(context, track.provider).toMutableList()
        if (existing.none { it.title.equals(track.title, true) && it.subtitle.equals(track.subtitle, true) }) {
            existing.add(0, track.copy(itemType = NativeMusicItemType.TRACK, isSavedInLibrary = true))
            replaceSavedLikedTracks(context, existing)
        }
    }

    fun loadSavedLikedTracks(context: Context, provider: MusicProvider): List<NativeMusicItem> {
        val raw = prefs(context).getString(KEY_LIKED_TRACKS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<NativeMusicItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    NativeMusicItem(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        title = o.optString("title", "Song"),
                        subtitle = o.optString("subtitle", ""),
                        provider = provider,
                        itemType = NativeMusicItemType.TRACK,
                        playbackUriOrQuery = o.optString("playbackUriOrQuery", ""),
                        streamUrl = o.optString("streamUrl", ""),
                        artworkUrl = o.optString("artworkUrl", ""),
                        durationMs = o.optLong("durationMs", 0L),
                        isSavedInLibrary = true
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun removeItemFromLocalLibrary(
        context: Context,
        itemId: String,
        provider: MusicProvider
    ): List<NativeMusicItem> {
        val current = loadLibraryAndPlaylists(context, provider).filterNot { it.id == itemId }
        saveLibraryList(context, provider, current)
        return current
    }

    suspend fun resolveArtworkUrlForItem(item: NativeMusicItem): String? = withContext(Dispatchers.IO) {
        if (item.artworkUrl.startsWith("http")) return@withContext item.artworkUrl
        if (item.id.endsWith("_liked")) {
            return@withContext "https://misc.scdn.co/liked-songs/liked-songs-300.png"
        }
        val cacheKey = "${item.itemType.name}_${item.id}_${item.title}"
        artworkUrlCache[cacheKey]?.let { if (it.isNotBlank()) return@withContext it }

        val rawYtId = item.playbackUriOrQuery.trim().ifBlank { item.id.removePrefix("yt_vid_").trim() }
        if (YOUTUBE_VIDEO_ID_REGEX.matches(rawYtId)) {
            val ytThumb = "https://i.ytimg.com/vi/$rawYtId/hqdefault.jpg"
            artworkUrlCache[cacheKey] = ytThumb
            return@withContext ytThumb
        }

        val cleanPlId = extractCleanSpotifyPlaylistId(item.id.ifBlank { item.playbackUriOrQuery })
        if (cleanPlId.isNotBlank()) {
            val oembedThumb = fetchSpotifyOembedThumbnail("https://open.spotify.com/playlist/$cleanPlId")
            if (!oembedThumb.isNullOrBlank()) {
                artworkUrlCache[cacheKey] = oembedThumb
                return@withContext oembedThumb
            }
        }

        val cleanTrackId = item.id
            .removePrefix("sp_tr_")
            .removePrefix("sp_liked_")
            .removePrefix("spotify:track:")
            .trim()
        if (SPOTIFY_ID_REGEX.matches(cleanTrackId)) {
            val oembedThumb = fetchSpotifyOembedThumbnail("https://open.spotify.com/track/$cleanTrackId")
            if (!oembedThumb.isNullOrBlank()) {
                artworkUrlCache[cacheKey] = oembedThumb
                return@withContext oembedThumb
            }
        }

        null
    }

    private fun fetchSpotifyOembedThumbnail(spotifyItemUrl: String): String? {
        return try {
            val encoded = URLEncoder.encode(spotifyItemUrl, "UTF-8")
            val conn = (URL("https://open.spotify.com/oembed?url=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", DESKTOP_CHROME_UA)
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val thumb = JSONObject(body).optString("thumbnail_url", "").trim()
                thumb.takeIf { it.startsWith("http") }
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchTracksFromSpotifyEmbed(
        playlistId: String,
        provider: MusicProvider
    ): Pair<List<NativeMusicItem>, String> = withContext(Dispatchers.IO) {
        val cleanId = extractCleanSpotifyPlaylistId(playlistId)
        if (cleanId.isBlank()) return@withContext emptyList<NativeMusicItem>() to ""

        val list = mutableListOf<NativeMusicItem>()
        var playlistCoverUrl = ""
        val embedUrls = listOf(
            "https://open.spotify.com/embed/playlist/$cleanId",
            "https://open.spotify.com/embed-legacy/playlist/$cleanId"
        )

        for (embedUrl in embedUrls) {
            try {
                val conn = (URL(embedUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", DESKTOP_CHROME_UA)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                }
                if (conn.responseCode in 200..299) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                    val startIdx = html.indexOf(marker)
                    if (startIdx >= 0) {
                        val jsonStart = startIdx + marker.length
                        val endIdx = html.indexOf("</script>", jsonStart)
                        if (endIdx > jsonStart) {
                            val nextJson = JSONObject(html.substring(jsonStart, endIdx))
                            val entity = nextJson
                                .optJSONObject("props")
                                ?.optJSONObject("pageProps")
                                ?.optJSONObject("state")
                                ?.optJSONObject("data")
                                ?.optJSONObject("entity")

                            playlistCoverUrl = entity
                                ?.optJSONObject("coverArt")
                                ?.optJSONArray("sources")
                                ?.optJSONObject(0)
                                ?.optString("url", "")
                                ?.ifBlank {
                                    entity.optJSONObject("visualIdentity")
                                        ?.optJSONArray("image")
                                        ?.optJSONObject(0)
                                        ?.optString("url", "") ?: ""
                                } ?: ""

                            val trackList = entity?.optJSONArray("trackList")
                            if (trackList != null && trackList.length() > 0) {
                                for (i in 0 until trackList.length()) {
                                    val t = trackList.optJSONObject(i) ?: continue
                                    val title = t.optString("title", "").trim().takeIf { it.isNotBlank() } ?: continue
                                    val subtitle = t.optString("subtitle", "").replace('\u00A0', ' ').trim()
                                    val uri = t.optString("uri", "").trim()
                                    val trackId = uri.substringAfterLast(":").ifBlank { "sp_emb_${cleanId}_$i" }
                                    val durMs = t.optLong("duration", 0L)
                                    list.add(
                                        NativeMusicItem(
                                            id = "sp_tr_$trackId",
                                            title = title,
                                            subtitle = subtitle,
                                            provider = provider,
                                            itemType = NativeMusicItemType.TRACK,
                                            playbackUriOrQuery = if (SPOTIFY_ID_REGEX.matches(trackId)) "spotify:track:$trackId" else "$title $subtitle".trim(),
                                            streamUrl = "",
                                            artworkUrl = "",
                                            durationMs = durMs,
                                            isSavedInLibrary = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (list.isNotEmpty()) return@withContext list to playlistCoverUrl
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        list to playlistCoverUrl
    }

    private fun parseSpotifyTrackJsonNode(tr: JSONObject?, fallbackIndex: Int, provider: MusicProvider): NativeMusicItem? {
        if (tr == null) return null
        val name = tr.optString("name", "").trim().takeIf { it.isNotBlank() } ?: return null
        val id = tr.optString("id", "").ifBlank { tr.optString("uri", "sp_tr_$fallbackIndex").substringAfterLast(":") }.trim()

        val artistsArr = tr.optJSONArray("artists")
            ?: tr.optJSONObject("artists")?.optJSONArray("items")
        val artistNames = mutableListOf<String>()
        if (artistsArr != null) {
            for (aIdx in 0 until artistsArr.length()) {
                val aObj = artistsArr.optJSONObject(aIdx) ?: continue
                val aName = aObj.optString("name", "").ifBlank {
                    aObj.optJSONObject("profile")?.optString("name", "") ?: ""
                }.trim()
                if (aName.isNotBlank()) artistNames.add(aName)
            }
        }
        val artist = artistNames.joinToString(", ")

        val artUrl = tr.optJSONObject("album")
            ?.optJSONArray("images")
            ?.optJSONObject(0)
            ?.optString("url", "")
            ?.ifBlank {
                tr.optJSONObject("albumOfTrack")
                    ?.optJSONObject("coverArt")
                    ?.optJSONArray("sources")
                    ?.optJSONObject(0)
                    ?.optString("url", "") ?: ""
            } ?: ""

        val durMs = tr.optLong("duration_ms", 0L).takeIf { it > 0L }
            ?: tr.optJSONObject("duration")?.optLong("totalMilliseconds", 0L)
            ?: tr.optJSONObject("trackDuration")?.optLong("totalMilliseconds", 0L)
            ?: 0L

        val uriOrQuery = if (SPOTIFY_ID_REGEX.matches(id)) "spotify:track:$id" else "$name $artist".trim()

        return NativeMusicItem(
            id = "sp_tr_$id",
            title = name,
            subtitle = artist,
            provider = provider,
            itemType = NativeMusicItemType.TRACK,
            playbackUriOrQuery = uriOrQuery,
            streamUrl = "",
            artworkUrl = artUrl,
            durationMs = durMs,
            isSavedInLibrary = true
        )
    }

    suspend fun fetchTracksForPlaylist(
        context: Context,
        playlist: NativeMusicItem,
        bridge: BridgeSettings
    ): List<NativeMusicItem> = withContext(Dispatchers.IO) {
        if (playlist.id.startsWith("plex_") || playlist.playbackUriOrQuery.startsWith("plex:")) {
            return@withContext fetchPlexTracksForPlaylist(bridge, playlist)
        }
        val isLikedSongsPlaylist = playlist.id.endsWith("_liked") ||
                playlist.title.equals("Liked Songs", ignoreCase = true)

        if (isLikedSongsPlaylist) {
            val savedLiked = loadSavedLikedTracks(context, playlist.provider)
            if (savedLiked.isNotEmpty()) {
                return@withContext savedLiked
            }
            val cachedLiked = loadCachedPlaylistTracks(
                context = context,
                playlistId = "${playlist.provider.name}_liked",
                playlistTitle = "Liked Songs",
                provider = playlist.provider
            )
            if (cachedLiked.isNotEmpty()) {
                return@withContext cachedLiked
            }
        } else {
            val cleanPlId = extractCleanSpotifyPlaylistId(
                playlist.id.ifBlank { playlist.playbackUriOrQuery }
            )
            val cacheKey = if (cleanPlId.isNotBlank()) "sp_pl_$cleanPlId" else playlist.id
            val cached = loadCachedPlaylistTracks(
                context = context,
                playlistId = cacheKey,
                playlistTitle = playlist.title,
                provider = playlist.provider
            )
            if (cached.isNotEmpty()) {
                return@withContext cached
            }
        }

        if (playlist.provider == MusicProvider.SPOTIFY) {
            val cleanPlId = extractCleanSpotifyPlaylistId(
                playlist.id.removePrefix("sp_pl_").ifBlank { playlist.playbackUriOrQuery }
            )
            val token = getValidSpotifyToken(context)

            if (!token.isNullOrBlank()) {
                try {
                    val endpoint = when {
                        isLikedSongsPlaylist -> "https://api.spotify.com/v1/me/tracks?limit=50"
                        cleanPlId.isNotBlank() -> "https://api.spotify.com/v1/playlists/$cleanPlId/tracks?limit=100"
                        else -> ""
                    }
                    if (endpoint.isNotBlank()) {
                        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 6000
                            readTimeout = 6000
                            applySpotifyAuthHeaders(this, context, token)
                        }
                        if (conn.responseCode in 200..299) {
                            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            conn.disconnect()
                            val items = json.optJSONArray("items") ?: JSONArray()
                            val apiTracks = mutableListOf<NativeMusicItem>()
                            for (i in 0 until items.length()) {
                                val itemObj = items.optJSONObject(i) ?: continue
                                val tr = itemObj.optJSONObject("track") ?: itemObj.optJSONObject("item") ?: itemObj
                                val parsed = parseSpotifyTrackJsonNode(tr, i, playlist.provider)
                                if (parsed != null) {
                                    apiTracks.add(parsed)
                                }
                            }
                            if (apiTracks.isNotEmpty()) {
                                if (isLikedSongsPlaylist) {
                                    replaceSavedLikedTracks(context, apiTracks)
                                    saveCachedPlaylistTracks(context, "${MusicProvider.SPOTIFY.name}_liked", apiTracks)
                                } else {
                                    saveCachedPlaylistTracks(context, "sp_pl_$cleanPlId", apiTracks)
                                }
                                return@withContext apiTracks
                            }
                        } else {
                            conn.disconnect()
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (!isLikedSongsPlaylist && cleanPlId.isNotBlank()) {
                val (embedTracks, embedCoverUrl) = fetchTracksFromSpotifyEmbed(
                    playlistId = cleanPlId,
                    provider = playlist.provider
                )
                if (embedTracks.isNotEmpty()) {
                    if (embedCoverUrl.startsWith("http")) {
                        val currentLib = loadLibraryAndPlaylists(context, MusicProvider.SPOTIFY).toMutableList()
                        val idx = currentLib.indexOfFirst { extractCleanSpotifyPlaylistId(it.id) == cleanPlId }
                        if (idx >= 0 && currentLib[idx].artworkUrl.isBlank()) {
                            currentLib[idx] = currentLib[idx].copy(artworkUrl = embedCoverUrl)
                            saveLibraryList(context, MusicProvider.SPOTIFY, currentLib)
                        }
                    }
                    saveCachedPlaylistTracks(context, "sp_pl_$cleanPlId", embedTracks)
                    return@withContext embedTracks
                }
            }

            val isCustomGenrePlaylist = !isLikedSongsPlaylist &&
                    !playlist.id.startsWith("sp_") &&
                    !playlist.playbackUriOrQuery.startsWith("spotify:") &&
                    playlist.subtitle.equals("Custom Playlist", ignoreCase = true)

            if (isCustomGenrePlaylist) {
                val customQuery = playlist.playbackUriOrQuery.ifBlank { playlist.title }
                val fetched = searchSpotifyCatalogTracks(context, customQuery)
                if (fetched.isNotEmpty()) {
                    saveCachedPlaylistTracks(context, playlist.id, fetched)
                }
                return@withContext fetched
            }

            return@withContext emptyList()
        }

        if (!isLikedSongsPlaylist) {
            val query = playlist.playbackUriOrQuery
                .takeIf { it.isNotBlank() && !it.startsWith("http") && !it.startsWith("spotify:") }
                ?: playlist.title
            val ytTracks = searchCatalogTracksForCustomQuery(playlist.provider, query)
            if (ytTracks.isNotEmpty()) {
                saveCachedPlaylistTracks(context, playlist.id, ytTracks)
            }
            return@withContext ytTracks
        }

        emptyList()
    }

    /**
     * Resolves a direct audio stream when `MusicProvider.YOUTUBE_MUSIC` is active.
     * - Fast Path: If `query` is already an 11-character YouTube `videoId`, skips the search
     *   POST request and resolves the stream directly from `youtubei/v1/player`.
     */
    suspend fun resolveFullLengthAudioStreamDetailed(
        query: String,
        originalTrackTitle: String = ""
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext null

        // 1. Fast Path: Direct 11-character YouTube videoId (0 search round-trips)
        val cleanVideoIdCandidate = cleanQuery.removePrefix("yt_vid_").trim()
        if (YOUTUBE_VIDEO_ID_REGEX.matches(cleanVideoIdCandidate)) {
            val directStream = fetchYouTubeInnertubeAudioStream(cleanVideoIdCandidate)
            if (directStream != null && directStream.streamUrl.startsWith("http")) {
                return@withContext directStream
            }
        }

        // 2. Fallback: Search YouTube Music WEB_REMIX for matching studio videoIds
        val candidateVideoIds = searchOfficialStudioVideoIds(
            query = cleanQuery,
            originalTrackTitle = originalTrackTitle.ifBlank { cleanQuery }
        )
        for (videoId in candidateVideoIds.take(3)) {
            val ytStream = fetchYouTubeInnertubeAudioStream(videoId)
            if (ytStream != null && ytStream.streamUrl.startsWith("http") && ytStream.durationMs >= 45_000L) {
                return@withContext ytStream
            }
        }
        null
    }

    suspend fun resolveFullLengthAudioStream(query: String): Pair<String, Long>? =
        withContext(Dispatchers.IO) {
            val detailed = resolveFullLengthAudioStreamDetailed(query) ?: return@withContext null
            detailed.streamUrl to detailed.durationMs
        }

    private fun isUnwantedCoverOrAcousticTitle(
        candidateTitle: String,
        originalTrackTitle: String
    ): Boolean {
        val candidateLower = candidateTitle.lowercase()
        val originalLower = originalTrackTitle.lowercase()
        for (kw in UNWANTED_VERSION_KEYWORDS) {
            if (candidateLower.contains(kw) && !originalLower.contains(kw)) {
                return true
            }
        }
        return false
    }

    private fun searchOfficialStudioVideoIds(
        query: String,
        originalTrackTitle: String
    ): List<String> {
        val orderedIds = LinkedHashSet<String>()
        try {
            val searchedItems = searchYouTubeMusicWebRemixItems(query)
            for (item in searchedItems) {
                if (!isUnwantedCoverOrAcousticTitle(item.title, originalTrackTitle)) {
                    val vid = item.playbackUriOrQuery.trim()
                    if (YOUTUBE_VIDEO_ID_REGEX.matches(vid)) {
                        orderedIds.add(vid)
                    }
                }
            }
            for (item in searchedItems) {
                val vid = item.playbackUriOrQuery.trim()
                if (YOUTUBE_VIDEO_ID_REGEX.matches(vid)) {
                    orderedIds.add(vid)
                }
            }
        } catch (_: Exception) {
        }
        return orderedIds.toList()
    }

    internal fun forceRefreshSpotifyTokenSync(context: Context): String? =
        refreshSpotifyTokenFromCookie(context)

    private fun fetchYouTubeInnertubeAudioStream(videoId: String): ResolvedAudioStream? {
        val clients = listOf(
            JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.60.19")
                put("androidSdkVersion", 32)
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12L")
                put("hl", "en")
                put("gl", "GB")
            } to "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            JSONObject().apply {
                put("clientName", "IOS")
                put("clientVersion", "19.45.4")
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
                put("osName", "iPhone")
                put("osVersion", "18.1.0.22B83")
                put("hl", "en")
                put("gl", "GB")
            } to "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            JSONObject().apply {
                put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                put("clientVersion", "2.0")
                put("hl", "en")
                put("gl", "GB")
            } to "Mozilla/5.0 (PlayStation; PlayStation 5/2.26) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0 Safari/605.1.15",
            JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", "19.44.38")
                put("androidSdkVersion", 34)
                put("osName", "Android")
                put("osVersion", "14")
                put("hl", "en")
                put("gl", "GB")
            } to "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip"
        )

        for ((clientObj, userAgent) in clients) {
            try {
                val url = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                val payload = JSONObject().apply {
                    put("context", JSONObject().put("client", clientObj))
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3500
                    readTimeout = 3500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", userAgent)
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(payload.toString())
                    it.flush()
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val root = JSONObject(body)
                    val streamingData = root.optJSONObject("streamingData") ?: continue
                    val adaptive = streamingData.optJSONArray("adaptiveFormats")
                    if (adaptive != null && adaptive.length() > 0) {
                        var bestM4aUrl = ""
                        var bestAnyAudioUrl = ""
                        var bestDurationMs = 0L

                        for (i in 0 until adaptive.length()) {
                            val fmt = adaptive.optJSONObject(i) ?: continue
                            val mime = fmt.optString("mimeType", "")
                            val directUrl = fmt.optString("url", "")
                            val durMs = fmt.optString("approxDurationMs", "0").toLongOrNull() ?: 0L
                            if (directUrl.startsWith("http") && mime.startsWith("audio/")) {
                                if (durMs > bestDurationMs) bestDurationMs = durMs
                                if (mime.contains("mp4", true) || mime.contains("m4a", true)) {
                                    bestM4aUrl = directUrl
                                    break
                                } else if (bestAnyAudioUrl.isBlank()) {
                                    bestAnyAudioUrl = directUrl
                                }
                            }
                        }

                        val chosenUrl = bestM4aUrl.ifBlank { bestAnyAudioUrl }
                        if (chosenUrl.isNotBlank()) {
                            val lengthSec = root.optJSONObject("videoDetails")
                                ?.optString("lengthSeconds", "0")?.toLongOrNull() ?: 0L
                            val finalDurMs = if (bestDurationMs > 0L) bestDurationMs else (lengthSec * 1000L)
                            if (finalDurMs >= 45_000L) {
                                return ResolvedAudioStream(
                                    streamUrl = chosenUrl,
                                    durationMs = finalDurMs,
                                    userAgent = userAgent
                                )
                            }
                        }
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun parseDurationMmSsToMillis(rawDuration: String): Long {
        val parts = rawDuration.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> ((parts[0] * 60L) + parts[1]) * 1000L
            3 -> ((parts[0] * 3600L) + (parts[1] * 60L) + parts[2]) * 1000L
            else -> 210_000L
        }
    }

    /**
     * Queries YouTube Music's `youtubei/v1/search` (`WEB_REMIX` Song filter) directly and extracts
     * each song's 11-character `videoId`, title, artist/album subtitle, duration, and cover art
     * so tapping any search result plays immediately without a second search request.
     */
    private fun searchYouTubeMusicWebRemixItems(
        query: String,
        provider: MusicProvider = MusicProvider.YOUTUBE_MUSIC
    ): List<NativeMusicItem> {
        val results = mutableListOf<NativeMusicItem>()
        val seenVideoIds = HashSet<String>()
        try {
            val ytmUrl = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            val payload = JSONObject().apply {
                put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject().apply {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", "1.20250310.01.00")
                            put("hl", "en")
                            put("gl", "GB")
                        }
                    )
                )
                put("query", query.trim())
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==") // Songs filter
            }
            val conn = (ytmUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", DESKTOP_CHROME_UA)
                setRequestProperty("Origin", "https://music.youtube.com")
            }
            OutputStreamWriter(conn.outputStream).use {
                it.write(payload.toString())
                it.flush()
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                fun walkJsonForTrackRenderers(node: Any?) {
                    when (node) {
                        is JSONObject -> {
                            val renderer = node.optJSONObject("musicResponsiveListItemRenderer")
                            if (renderer != null) {
                                val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId", "")
                                    ?.ifBlank {
                                        renderer.optJSONObject("overlay")
                                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                            ?.optJSONObject("content")
                                            ?.optJSONObject("musicPlayButtonRenderer")
                                            ?.optJSONObject("playNavigationEndpoint")
                                            ?.optJSONObject("watchEndpoint")
                                            ?.optString("videoId", "")
                                            .orEmpty()
                                    }.orEmpty().trim()

                                if (YOUTUBE_VIDEO_ID_REGEX.matches(videoId) && seenVideoIds.add(videoId)) {
                                    val flexCols = renderer.optJSONArray("flexColumns")
                                    var trackTitle = ""
                                    val subtitleParts = mutableListOf<String>()
                                    var durationMs = 210_000L

                                    if (flexCols != null && flexCols.length() > 0) {
                                        val col0Runs = flexCols.optJSONObject(0)
                                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                            ?.optJSONObject("text")
                                            ?.optJSONArray("runs")
                                        trackTitle = col0Runs?.optJSONObject(0)?.optString("text", "").orEmpty().trim()

                                        if (flexCols.length() > 1) {
                                            val col1Runs = flexCols.optJSONObject(1)
                                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                                ?.optJSONObject("text")
                                                ?.optJSONArray("runs")
                                            if (col1Runs != null) {
                                                for (rIdx in 0 until col1Runs.length()) {
                                                    val txt = col1Runs.optJSONObject(rIdx)?.optString("text", "").orEmpty().trim()
                                                    if (txt.isBlank() || txt == "\u2022" || txt == "&" || txt == ",") continue
                                                    if (txt.matches(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"))) {
                                                        durationMs = parseDurationMmSsToMillis(txt)
                                                    } else {
                                                        subtitleParts.add(txt)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    val thumbs = renderer.optJSONObject("thumbnail")
                                        ?.optJSONObject("musicThumbnailRenderer")
                                        ?.optJSONObject("thumbnail")
                                        ?.optJSONArray("thumbnails")
                                    val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                                        thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "").orEmpty()
                                    } else {
                                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                    }

                                    if (trackTitle.isNotBlank()) {
                                        results.add(
                                            NativeMusicItem(
                                                id = "yt_vid_$videoId",
                                                title = trackTitle,
                                                subtitle = subtitleParts.take(2).joinToString(" \u2022 "),
                                                provider = provider,
                                                itemType = NativeMusicItemType.TRACK,
                                                playbackUriOrQuery = videoId,
                                                streamUrl = "",
                                                artworkUrl = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                                                durationMs = durationMs
                                            )
                                        )
                                    }
                                }
                            } else {
                                val keys = node.keys()
                                while (keys.hasNext()) {
                                    walkJsonForTrackRenderers(node.opt(keys.next()))
                                }
                            }
                        }
                        is JSONArray -> {
                            for (i in 0 until node.length()) {
                                walkJsonForTrackRenderers(node.opt(i))
                            }
                        }
                    }
                }

                walkJsonForTrackRenderers(JSONObject(body))
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        return results
    }

    private suspend fun searchCatalogTracksForCustomQuery(
        provider: MusicProvider,
        query: String
    ): List<NativeMusicItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // 1. Primary: Direct YouTube Music WEB_REMIX search (returns exact videoId + artwork in 1 call)
        val ytItems = searchYouTubeMusicWebRemixItems(cleanQuery, provider)
        if (ytItems.isNotEmpty()) {
            return@withContext ytItems
        }

        // 2. Secondary Fallback: Apple iTunes metadata search if YouTube Music search is unreachable
        val list = mutableListOf<NativeMusicItem>()
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=26")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val trackName = o.optString("trackName", "").takeIf { it.isNotBlank() } ?: continue
                    val artistName = o.optString("artistName", "")
                    val albumName = o.optString("collectionName", "")
                    val artworkUrl = o.optString("artworkUrl100", "").replace("100x100bb", "300x300bb")
                    val trackTimeMillis = o.optLong("trackTimeMillis", 210_000L)
                    val trackId = o.optLong("trackId", i.toLong())

                    list.add(
                        NativeMusicItem(
                            id = "${provider.name}_song_$trackId",
                            title = trackName,
                            subtitle = if (albumName.isNotBlank()) "$artistName \u2022 $albumName" else artistName,
                            provider = provider,
                            itemType = NativeMusicItemType.TRACK,
                            playbackUriOrQuery = "$trackName $artistName",
                            streamUrl = "",
                            artworkUrl = artworkUrl,
                            durationMs = trackTimeMillis
                        )
                    )
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        list
    }

    suspend fun resolveDirectAudioStreamUrl(query: String): String? = withContext(Dispatchers.IO) {
        val fullStream = resolveFullLengthAudioStreamDetailed(query)
        if (fullStream != null && fullStream.streamUrl.startsWith("http")) {
            return@withContext fullStream.streamUrl
        }
        null
    }

    suspend fun syncCloudPlaylists(
        context: Context,
        provider: MusicProvider,
        bridge: BridgeSettings
    ): Result<List<NativeMusicItem>> = withContext(Dispatchers.IO) {
        try {
            JarvisLiveBridge.isMusicSyncingFlow.value = true
            JarvisLiveBridge.musicSyncProgressFlow.value = 0.05f
            JarvisLiveBridge.musicSyncStatusFlow.value = "Connecting to ${provider.label} library (5%)..."

            val existingLibrary = loadLibraryAndPlaylists(context, provider).toMutableList()
            val synced = mutableListOf<NativeMusicItem>()

            if (provider == MusicProvider.SPOTIFY) {
                val token = getValidSpotifyToken(context)
                val existingLiked = loadSavedLikedTracks(context, MusicProvider.SPOTIFY)
                synced.add(
                    NativeMusicItem(
                        id = "${provider.name}_liked",
                        title = "Liked Songs",
                        subtitle = if (existingLiked.isNotEmpty()) "Playlist \u2022 ${existingLiked.size} saved tracks"
                        else "Playlist \u2022 Your saved Spotify tracks",
                        provider = MusicProvider.SPOTIFY,
                        itemType = NativeMusicItemType.PLAYLIST,
                        playbackUriOrQuery = "spotify:collection:tracks",
                        artworkUrl = "https://misc.scdn.co/liked-songs/liked-songs-300.png",
                        trackCount = existingLiked.size,
                        isSavedInLibrary = true
                    )
                )

                if (!token.isNullOrBlank()) {
                    var offset = 0
                    var hasMorePlaylists = true
                    while (hasMorePlaylists && offset < 300) {
                        val pct = (0.10f + (offset / 300f) * 0.25f).coerceIn(0.10f, 0.35f)
                        JarvisLiveBridge.musicSyncProgressFlow.value = pct
                        JarvisLiveBridge.musicSyncStatusFlow.value =
                            "Fetching Spotify playlists (${(synced.size - 1).coerceAtLeast(existingLibrary.size - 1)} found)..."

                        try {
                            val plConn = (URL("https://api.spotify.com/v1/me/playlists?limit=50&offset=$offset").openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                connectTimeout = 6000
                                readTimeout = 6000
                                applySpotifyAuthHeaders(this, context, token)
                            }
                            if (plConn.responseCode in 200..299) {
                                val json = JSONObject(plConn.inputStream.bufferedReader().use { it.readText() })
                                val items = json.optJSONArray("items") ?: JSONArray()
                                for (i in 0 until items.length()) {
                                    val p = items.optJSONObject(i) ?: continue
                                    val rawId = p.optString("id", "")
                                    val cleanPlId = extractCleanSpotifyPlaylistId(rawId)
                                    val name = p.optString("name", "Playlist").trim()
                                    val owner = p.optJSONObject("owner")?.optString("display_name", "Spotify") ?: "Spotify"
                                    val total = p.optJSONObject("tracks")?.optInt("total", 0) ?: 0
                                    val imageUrl = p.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""
                                    if (cleanPlId.isNotBlank() && name.isNotBlank() && name.lowercase() !in BLOCKED_SPOTIFY_UI_TITLES) {
                                        synced.add(
                                            NativeMusicItem(
                                                id = "sp_pl_$cleanPlId",
                                                title = name,
                                                subtitle = "Playlist \u2022 $owner ($total tracks)",
                                                provider = MusicProvider.SPOTIFY,
                                                itemType = NativeMusicItemType.PLAYLIST,
                                                playbackUriOrQuery = "spotify:playlist:$cleanPlId",
                                                artworkUrl = imageUrl,
                                                trackCount = total,
                                                isSavedInLibrary = true
                                            )
                                        )
                                    }
                                }
                                val nextUrl = json.optString("next", "")
                                hasMorePlaylists = items.length() > 0 && nextUrl.isNotBlank() && nextUrl != "null"
                                offset += 50
                                delay(180L)
                            } else {
                                hasMorePlaylists = false
                            }
                            plConn.disconnect()
                        } catch (_: Exception) {
                            hasMorePlaylists = false
                        }
                    }

                    JarvisLiveBridge.musicSyncProgressFlow.value = 0.40f
                    JarvisLiveBridge.musicSyncStatusFlow.value = "Syncing your Liked Songs (40%)..."

                    val likedTracks = mutableListOf<NativeMusicItem>()
                    var likedOffset = 0
                    var hasMoreLiked = true
                    while (hasMoreLiked && likedOffset < 150) {
                        try {
                            val trConn = (URL("https://api.spotify.com/v1/me/tracks?limit=50&offset=$likedOffset").openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                connectTimeout = 6000
                                readTimeout = 6000
                                applySpotifyAuthHeaders(this, context, token)
                            }
                            if (trConn.responseCode in 200..299) {
                                val json = JSONObject(trConn.inputStream.bufferedReader().use { it.readText() })
                                val items = json.optJSONArray("items") ?: JSONArray()
                                for (i in 0 until items.length()) {
                                    val tr = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                                    val parsed = parseSpotifyTrackJsonNode(tr, likedOffset + i, MusicProvider.SPOTIFY)
                                    if (parsed != null) {
                                        likedTracks.add(parsed.copy(id = "sp_liked_${parsed.id.removePrefix("sp_tr_")}"))
                                    }
                                }
                                val nextUrl = json.optString("next", "")
                                hasMoreLiked = items.length() > 0 && nextUrl.isNotBlank() && nextUrl != "null"
                                likedOffset += 50
                                delay(180L)
                            } else {
                                hasMoreLiked = false
                            }
                            trConn.disconnect()
                        } catch (_: Exception) {
                            hasMoreLiked = false
                        }
                    }
                    if (likedTracks.isNotEmpty()) {
                        replaceSavedLikedTracks(context, likedTracks)
                        saveCachedPlaylistTracks(context, "${MusicProvider.SPOTIFY.name}_liked", likedTracks)
                        synced[0] = synced[0].copy(
                            subtitle = "Playlist \u2022 ${likedTracks.size} saved tracks",
                            trackCount = likedTracks.size
                        )
                    }
                }
            }

            for (existing in existingLibrary) {
                val cleanId = extractCleanSpotifyPlaylistId(existing.id.ifBlank { existing.playbackUriOrQuery })
                if (cleanId.isNotBlank()) {
                    val matchIdx = synced.indexOfFirst { extractCleanSpotifyPlaylistId(it.id) == cleanId }
                    if (matchIdx < 0) {
                        synced.add(existing.copy(id = "sp_pl_$cleanId", playbackUriOrQuery = "spotify:playlist:$cleanId"))
                    } else if (synced[matchIdx].artworkUrl.isBlank() && existing.artworkUrl.isNotBlank()) {
                        synced[matchIdx] = synced[matchIdx].copy(artworkUrl = existing.artworkUrl)
                    }
                }
            }

            val finalPlaylists = if (synced.size > 1) {
                synced.distinctBy { item ->
                    val cId = extractCleanSpotifyPlaylistId(item.id)
                    if (cId.isNotBlank()) "sp_pl_$cId" else item.id
                }.toMutableList()
            } else {
                existingLibrary.toMutableList()
            }
            saveLibraryList(context, provider, finalPlaylists)

            val realPlaylistsToSync = finalPlaylists.filter {
                extractCleanSpotifyPlaylistId(it.id.ifBlank { it.playbackUriOrQuery }).isNotBlank()
            }
            val totalToSync = realPlaylistsToSync.size.coerceAtLeast(1)

            for ((idx, pl) in realPlaylistsToSync.withIndex()) {
                val pct = 0.45f + (0.55f * ((idx + 1).toFloat() / totalToSync.toFloat()))
                val pctInt = (pct * 100).roundToInt().coerceIn(45, 100)
                JarvisLiveBridge.musicSyncProgressFlow.value = pct
                JarvisLiveBridge.musicSyncStatusFlow.value =
                    "Syncing playlist ${idx + 1} of ${realPlaylistsToSync.size}: ${pl.title} ($pctInt%)..."

                if (pl.artworkUrl.isBlank()) {
                    val resolvedCover = resolveArtworkUrlForItem(pl)
                    if (!resolvedCover.isNullOrBlank()) {
                        val libIdx = finalPlaylists.indexOfFirst { it.id == pl.id }
                        if (libIdx >= 0) {
                            finalPlaylists[libIdx] = finalPlaylists[libIdx].copy(artworkUrl = resolvedCover)
                        }
                    }
                }

                val cleanPlId = extractCleanSpotifyPlaylistId(pl.id.ifBlank { pl.playbackUriOrQuery })
                val alreadyCached = loadCachedPlaylistTracks(
                    context = context,
                    playlistId = "sp_pl_$cleanPlId",
                    playlistTitle = pl.title,
                    provider = pl.provider
                )
                if (alreadyCached.isEmpty()) {
                    fetchTracksForPlaylist(context, pl, bridge)
                    delay(220L)
                }
            }

            saveLibraryList(context, provider, finalPlaylists)
            JarvisLiveBridge.musicSyncProgressFlow.value = 1.0f
            JarvisLiveBridge.musicSyncStatusFlow.value =
                "Sync complete (100%) \u2022 ${finalPlaylists.size} playlists synced!"
            delay(600L)

            Result.success(finalPlaylists)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            JarvisLiveBridge.isMusicSyncingFlow.value = false
        }
    }

    // -----------------------------------------------------------------------
    // PLEX MEDIA SERVER NATIVE CATALOG & DIRECT STREAM RESOLVER (PORT 32400)
    // -----------------------------------------------------------------------
    private fun normalizePlexBaseUrl(rawUrl: String): String {
        val clean = rawUrl.trim().trimEnd('/')
        if (clean.isBlank()) return ""
        val withScheme = if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            clean
        } else {
            "http://$clean"
        }
        return if (Regex(":\\d+$").containsMatchIn(withScheme.substringAfter("://"))) {
            withScheme
        } else {
            "$withScheme:32400"
        }
    }

    private fun buildPlexUrlWithToken(baseUrl: String, pathAndQuery: String, plexToken: String): String {
        val cleanPath = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
        val full = "$baseUrl$cleanPath"
        if (plexToken.isBlank()) return full
        val sep = if (full.contains("?")) "&" else "?"
        return "${full}${sep}X-Plex-Token=${URLEncoder.encode(plexToken.trim(), "UTF-8")}"
    }

    private fun parsePlexMetadataTrackNode(
        meta: JSONObject,
        baseUrl: String,
        plexToken: String,
        provider: MusicProvider = MusicProvider.SPOTIFY
    ): NativeMusicItem? {
        val title = meta.optString("title", "").trim().takeIf { it.isNotBlank() } ?: return null
        val ratingKey = meta.optString("ratingKey", UUID.randomUUID().toString()).trim()
        val artist = meta.optString("grandparentTitle", meta.optString("originalTitle", "Plex Audio")).trim()
        val album = meta.optString("parentTitle", "").trim()
        val durationMs = meta.optLong("duration", 210_000L)
        val thumbPath = meta.optString("thumb", meta.optString("parentThumb", "")).trim()
        val artworkUrl = if (thumbPath.isNotBlank()) {
            buildPlexUrlWithToken(baseUrl, thumbPath, plexToken)
        } else ""

        val mediaArr = meta.optJSONArray("Media")
        val firstPart = mediaArr?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)
        val partKey = firstPart?.optString("key", "").orEmpty().trim()
        val directStreamUrl = if (partKey.isNotBlank()) {
            buildPlexUrlWithToken(baseUrl, partKey, plexToken)
        } else ""
        if (directStreamUrl.isBlank()) return null

        val subtitle = buildString {
            append(artist)
            if (album.isNotBlank()) append(" \u2022 $album")
            append(" \u2022 Plex")
        }
        return NativeMusicItem(
            id = "plex_tr_$ratingKey",
            title = title,
            subtitle = subtitle,
            provider = provider,
            itemType = NativeMusicItemType.TRACK,
            playbackUriOrQuery = "plex:track:$ratingKey",
            streamUrl = directStreamUrl,
            artworkUrl = artworkUrl,
            durationMs = durationMs,
            isSavedInLibrary = true
        )
    }

    suspend fun fetchPlexPlaylistsAndTracks(
        bridge: BridgeSettings
    ): List<NativeMusicItem> = withContext(Dispatchers.IO) {
        val baseUrl = normalizePlexBaseUrl(bridge.plexServerUrl)
        if (baseUrl.isBlank()) return@withContext emptyList()
        val token = bridge.plexToken.trim()
        val results = mutableListOf<NativeMusicItem>()

        // 1. Fetch audio playlists from /playlists
        try {
            val url = URL(buildPlexUrlWithToken(baseUrl, "/playlists", token))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val metadataArr = JSONObject(body)
                    .optJSONObject("MediaContainer")
                    ?.optJSONArray("Metadata") ?: JSONArray()
                for (i in 0 until metadataArr.length()) {
                    val pl = metadataArr.optJSONObject(i) ?: continue
                    val plType = pl.optString("playlistType", "audio")
                    if (!plType.equals("audio", ignoreCase = true)) continue
                    val ratingKey = pl.optString("ratingKey", "").trim()
                    val title = pl.optString("title", "Plex Playlist").trim()
                    val leafCount = pl.optInt("leafCount", 0)
                    val composite = pl.optString("composite", pl.optString("thumb", "")).trim()
                    val artUrl = if (composite.isNotBlank()) buildPlexUrlWithToken(baseUrl, composite, token) else ""
                    if (ratingKey.isNotBlank()) {
                        results.add(
                            NativeMusicItem(
                                id = "plex_pl_$ratingKey",
                                title = title,
                                subtitle = "Plex Playlist \u2022 $leafCount tracks",
                                provider = bridge.musicProvider,
                                itemType = NativeMusicItemType.PLAYLIST,
                                playbackUriOrQuery = "plex:playlist:$ratingKey",
                                artworkUrl = artUrl,
                                trackCount = leafCount,
                                isSavedInLibrary = true
                            )
                        )
                    }
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlexPlaylists note: ${e.message}")
        }

        // 2. Also discover Music Library sections (/library/sections)
        try {
            val secUrl = URL(buildPlexUrlWithToken(baseUrl, "/library/sections", token))
            val secConn = (secUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
            }
            if (secConn.responseCode in 200..299) {
                val body = secConn.inputStream.bufferedReader().use { it.readText() }
                secConn.disconnect()
                val dirs = JSONObject(body)
                    .optJSONObject("MediaContainer")
                    ?.optJSONArray("Directory") ?: JSONArray()
                for (i in 0 until dirs.length()) {
                    val dir = dirs.optJSONObject(i) ?: continue
                    if (!dir.optString("type", "").equals("artist", ignoreCase = true)) continue
                    val secKey = dir.optString("key", "").trim()
                    val secTitle = dir.optString("title", "Plex Music Library").trim()
                    if (secKey.isNotBlank()) {
                        results.add(
                            NativeMusicItem(
                                id = "plex_sec_$secKey",
                                title = "Plex: $secTitle",
                                subtitle = "Plex Media Server Library",
                                provider = bridge.musicProvider,
                                itemType = NativeMusicItemType.PLAYLIST,
                                playbackUriOrQuery = "plex:section:$secKey",
                                isSavedInLibrary = true
                            )
                        )
                    }
                }
            } else {
                secConn.disconnect()
            }
        } catch (_: Exception) {
        }

        results
    }

    suspend fun fetchPlexTracksForPlaylist(
        bridge: BridgeSettings,
        playlist: NativeMusicItem
    ): List<NativeMusicItem> = withContext(Dispatchers.IO) {
        val baseUrl = normalizePlexBaseUrl(bridge.plexServerUrl)
        if (baseUrl.isBlank()) return@withContext emptyList()
        val token = bridge.plexToken.trim()
        val endpointPath = when {
            playlist.playbackUriOrQuery.startsWith("plex:section:") -> {
                val secKey = playlist.playbackUriOrQuery.removePrefix("plex:section:").trim()
                "/library/sections/$secKey/all?type=10"
            }
            playlist.id.startsWith("plex_sec_") -> {
                val secKey = playlist.id.removePrefix("plex_sec_").trim()
                "/library/sections/$secKey/all?type=10"
            }
            else -> {
                val ratingKey = playlist.playbackUriOrQuery
                    .removePrefix("plex:playlist:")
                    .ifBlank { playlist.id.removePrefix("plex_pl_") }
                    .trim()
                "/playlists/$ratingKey/items"
            }
        }
        val tracks = mutableListOf<NativeMusicItem>()
        try {
            val conn = (URL(buildPlexUrlWithToken(baseUrl, endpointPath, token)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val metadataArr = JSONObject(body)
                    .optJSONObject("MediaContainer")
                    ?.optJSONArray("Metadata") ?: JSONArray()
                for (i in 0 until metadataArr.length()) {
                    val meta = metadataArr.optJSONObject(i) ?: continue
                    parsePlexMetadataTrackNode(meta, baseUrl, token, playlist.provider)?.let {
                        tracks.add(it)
                    }
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlexTracksForPlaylist note: ${e.message}")
        }
        tracks
    }

    suspend fun searchPlexCatalog(
        bridge: BridgeSettings,
        query: String
    ): List<NativeMusicItem> = withContext(Dispatchers.IO) {
        val baseUrl = normalizePlexBaseUrl(bridge.plexServerUrl)
        val cleanQuery = query.trim()
        if (baseUrl.isBlank() || cleanQuery.isBlank()) return@withContext emptyList()
        val token = bridge.plexToken.trim()
        val tracks = mutableListOf<NativeMusicItem>()
        try {
            val path = "/hubs/search?query=${URLEncoder.encode(cleanQuery, "UTF-8")}&limit=25"
            val conn = (URL(buildPlexUrlWithToken(baseUrl, path, token)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val hubs = JSONObject(body)
                    .optJSONObject("MediaContainer")
                    ?.optJSONArray("Hub") ?: JSONArray()
                for (hIdx in 0 until hubs.length()) {
                    val hub = hubs.optJSONObject(hIdx) ?: continue
                    val hubType = hub.optString("type", "")
                    if (!hubType.equals("track", ignoreCase = true)) continue
                    val metaArr = hub.optJSONArray("Metadata") ?: continue
                    for (mIdx in 0 until metaArr.length()) {
                        val meta = metaArr.optJSONObject(mIdx) ?: continue
                        parsePlexMetadataTrackNode(meta, baseUrl, token, bridge.musicProvider)?.let {
                            tracks.add(it)
                        }
                    }
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchPlexCatalog note: ${e.message}")
        }
        tracks
    }

    suspend fun searchMusicCatalog(
        context: Context,
        provider: MusicProvider,
        query: String
    ): Result<List<NativeMusicItem>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext Result.success(emptyList())
        val bridge = runCatching { DeviceRepository.loadBridge(context) }.getOrDefault(BridgeSettings())
        val plexTracks = if (bridge.plexServerUrl.isNotBlank()) {
            searchPlexCatalog(bridge, cleanQuery)
        } else {
            emptyList()
        }
        val providerTracks = if (provider == MusicProvider.SPOTIFY) {
            searchSpotifyCatalogTracks(context, cleanQuery)
        } else {
            searchCatalogTracksForCustomQuery(provider, cleanQuery)
        }
        val tracks = plexTracks + providerTracks
        if (tracks.isNotEmpty()) {
            Result.success(tracks)
        } else {
            Result.failure(
                Exception(
                    if (provider == MusicProvider.SPOTIFY && !isSpotifySignedIn(context)) {
                        "Sign in to Spotify first to search the Spotify catalog."
                    } else {
                        "No results found for '$cleanQuery'"
                    }
                )
            )
        }
    }

    suspend fun searchCatalog(
        context: Context,
        provider: MusicProvider,
        query: String
    ): List<NativeMusicItem> = searchMusicCatalog(context, provider, query).getOrDefault(emptyList())
}
