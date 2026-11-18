package com.jarvis.smarthomelauncher.ui.modals

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------------------------------------------------------------------------
// NATIVE JAVASCRIPT BRIDGE FOR SPOTIFY & YOUTUBE MUSIC WEBVIEW
// - Captures ONLY the user's personal libraryV3 playlists, Liked Songs & cover art
// - Reports live scanning / importing progress to the Compose loading overlay
// - Hides the raw WebView pane as soon as login completes
// ---------------------------------------------------------------------------
internal class ClaraWebLoginJsBridge(
    private val onStatusChanged: (String, String) -> Unit,
    private val onDataReceived: (String) -> Unit
) {
    @JavascriptInterface
    fun postStatusUpdate(headline: String, detail: String) {
        onStatusChanged(headline, detail)
    }

    @JavascriptInterface
    fun postExtractedData(jsonPayload: String) {
        onDataReceived(jsonPayload)
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun MusicWebLoginModal(
    provider: MusicProvider,
    onSessionCaptured: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var capturedBearerToken by remember { mutableStateOf("") }
    var capturedClientToken by remember { mutableStateOf("") }
    var hasCompletedCapture by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.removeJavascriptInterface("ClaraWebBridge")
                (webViewRef?.parent as? ViewGroup)?.removeView(webViewRef)
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    val alreadySignedIn = remember(provider) {
        if (provider == MusicProvider.SPOTIFY) {
            NativeMusicCatalogClient.isSpotifySignedIn(context)
        } else {
            NativeMusicCatalogClient.isYouTubeSignedIn(context)
        }
    }

    var isExtractingInBackground by remember { mutableStateOf(alreadySignedIn) }
    var extractionHeadline by remember {
        mutableStateOf(
            if (alreadySignedIn) {
                "Connecting to your ${provider.label} library..."
            } else {
                "Sign in to your ${provider.label} account below"
            }
        )
    }
    var extractionDetail by remember {
        mutableStateOf(
            if (alreadySignedIn) {
                "Preparing to scan your complete library, cover art & Liked Songs..."
            } else {
                "Your playlists, cover art & Liked Songs will be imported automatically as soon as you sign in."
            }
        )
    }

    val loginUrl = remember(provider) {
        when (provider) {
            MusicProvider.SPOTIFY ->
                "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
            MusicProvider.YOUTUBE_MUSIC ->
                "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2Flibrary%2Fplaylists"
        }
    }

    val spotifyTokenAndLibraryHookJs = remember {
        """
        (function() {
            if (window.__claraHookInstalled) return;
            window.__claraHookInstalled = true;
            window.__claraSpotifyToken = window.__claraSpotifyToken || '';
            window.__claraClientToken = window.__claraClientToken || '';
            window.__claraLibraryPlaylists = window.__claraLibraryPlaylists || [];
            window.__claraLikedTracks = window.__claraLikedTracks || [];
            window.__claraPlaylistTracksMap = window.__claraPlaylistTracksMap || {};
            window.__claraLibraryV3Url = window.__claraLibraryV3Url || '';
            window.__claraLibraryV3Hash = window.__claraLibraryV3Hash || '';
            window.__claraFetchLikedHash = window.__claraFetchLikedHash || '';

            const validIdRegex = /^[A-Za-z0-9]{22}$/;
            const blockedNames = new Set([
                'liked songs', 'your library', 'create playlist', 'getting started',
                'your episodes', 'new episodes', 'episodes', 'home', 'search', 'browse'
            ]);

            const extractCoverFromNode = function(node) {
                if (!node || typeof node !== 'object') return '';
                try {
                    if (node.images && Array.isArray(node.images.items) && node.images.items[0]) {
                        const s = node.images.items[0].sources;
                        if (Array.isArray(s) && s[0] && s[0].url) return s[0].url;
                    }
                    if (Array.isArray(node.images) && node.images[0] && node.images[0].url) {
                        return node.images[0].url;
                    }
                    if (node.coverArt && Array.isArray(node.coverArt.sources) && node.coverArt.sources[0]) {
                        return node.coverArt.sources[0].url || '';
                    }
                    if (node.albumOfTrack && node.albumOfTrack.coverArt && Array.isArray(node.albumOfTrack.coverArt.sources) && node.albumOfTrack.coverArt.sources[0]) {
                        return node.albumOfTrack.coverArt.sources[0].url || '';
                    }
                    if (node.album && Array.isArray(node.album.images) && node.album.images[0]) {
                        return node.album.images[0].url || '';
                    }
                } catch (e) {}
                return '';
            };

            const addLibraryPlaylist = function(plId, title, owner, count, artworkUrl) {
                if (!plId || !validIdRegex.test(plId) || !title) return;
                const cleanTitle = String(title).trim();
                if (!cleanTitle || blockedNames.has(cleanTitle.toLowerCase())) return;
                const existingIdx = window.__claraLibraryPlaylists.findIndex(x => x.id === plId);
                const sub = 'Playlist • ' + (owner || 'Spotify') + (count > 0 ? ' (' + count + ' tracks)' : '');
                if (existingIdx >= 0) {
                    const existing = window.__claraLibraryPlaylists[existingIdx];
                    if (count > 0 && existing.trackCount === 0) {
                        existing.trackCount = count;
                        existing.subtitle = sub;
                    }
                    if (artworkUrl && !existing.artworkUrl) {
                        existing.artworkUrl = artworkUrl;
                    }
                } else {
                    window.__claraLibraryPlaylists.push({
                        id: plId,
                        title: cleanTitle,
                        subtitle: sub,
                        artworkUrl: artworkUrl || '',
                        trackCount: count || 0
                    });
                }
            };

            const extractTracksFromItemsArray = function(itemsArr) {
                const results = [];
                if (!Array.isArray(itemsArr)) return results;
                for (const entry of itemsArr) {
                    if (!entry || typeof entry !== 'object') continue;
                    const tr = entry.track || (entry.itemV2 && entry.itemV2.data) || (entry.item && entry.item.data) || entry.data || entry;
                    if (!tr || typeof tr !== 'object') continue;
                    const name = tr.name || '';
                    if (!name || typeof name !== 'string') continue;
                    let artist = '';
                    if (Array.isArray(tr.artists) && tr.artists.length > 0) {
                        artist = tr.artists.map(a => (a && (a.name || (a.profile && a.profile.name))) || '').filter(Boolean).join(', ');
                    } else if (tr.artists && Array.isArray(tr.artists.items)) {
                        artist = tr.artists.items.map(a => (a && ((a.profile && a.profile.name) || a.name)) || '').filter(Boolean).join(', ');
                    } else if (tr.firstArtist && Array.isArray(tr.firstArtist.items) && tr.firstArtist.items[0]) {
                        artist = (tr.firstArtist.items[0].profile && tr.firstArtist.items[0].profile.name) || '';
                    }
                    const artUrl = extractCoverFromNode(tr);
                    const durMs = tr.duration_ms
                        || (tr.duration && tr.duration.totalMilliseconds)
                        || (tr.trackDuration && tr.trackDuration.totalMilliseconds)
                        || 0;
                    const rawUri = tr.uri || tr.id || '';
                    const trackId = String(rawUri).split(':').pop() || String(results.length);
                    results.push({
                        id: trackId,
                        title: name.trim(),
                        subtitle: artist.trim(),
                        artworkUrl: artUrl,
                        durationMs: durMs
                    });
                }
                return results;
            };

            const parseLibraryV3Node = function(libNode) {
                if (!libNode || typeof libNode !== 'object') return;
                const items = libNode.items || [];
                for (const entry of items) {
                    if (!entry || typeof entry !== 'object') continue;
                    const itemNode = entry.item || entry;
                    const dataNode = itemNode.data || itemNode.playlist || itemNode;
                    const uri = dataNode.uri || itemNode.uri || itemNode._uri || entry.uri || '';
                    const name = dataNode.name || itemNode.name || '';
                    if (typeof uri === 'string' && uri.startsWith('spotify:playlist:') && name) {
                        const plId = uri.replace('spotify:playlist:', '').trim();
                        const owner = (dataNode.ownerV2 && dataNode.ownerV2.data && dataNode.ownerV2.data.name)
                            || (dataNode.owner && dataNode.owner.display_name)
                            || 'Spotify';
                        const count = itemNode.count || dataNode.totalLength || 0;
                        const artUrl = extractCoverFromNode(dataNode) || extractCoverFromNode(itemNode);
                        addLibraryPlaylist(plId, name, owner, count, artUrl);
                    }
                }
            };

            const extractPersistedHash = function(reqUrl, reqBody) {
                try {
                    if (reqUrl.includes('extensions=')) {
                        const u = new URL(reqUrl, 'https://open.spotify.com');
                        const extParam = u.searchParams.get('extensions');
                        if (extParam) {
                            const extJson = JSON.parse(extParam);
                            if (extJson && extJson.persistedQuery && extJson.persistedQuery.sha256Hash) {
                                return extJson.persistedQuery.sha256Hash;
                            }
                        }
                    }
                    if (reqBody) {
                        const parsedBody = JSON.parse(reqBody);
                        if (parsedBody && parsedBody.extensions && parsedBody.extensions.persistedQuery) {
                            return parsedBody.extensions.persistedQuery.sha256Hash || '';
                        }
                    }
                } catch (e) {}
                return '';
            };

            const origFetch = window.fetch;
            window.fetch = async function(...args) {
                let reqUrl = '';
                let reqBody = '';
                try {
                    const req = args[0];
                    const init = args[1] || {};
                    reqUrl = (typeof req === 'string') ? req : (req && req.url ? req.url : '');
                    if (typeof init.body === 'string') reqBody = init.body;
                    const headers = init.headers || (req && req.headers) || {};
                    const readHeader = function(key) {
                        if (typeof headers.get === 'function') {
                            return headers.get(key) || headers.get(key.toLowerCase()) || '';
                        } else if (Array.isArray(headers)) {
                            for (const pair of headers) {
                                if (pair && pair[0] && pair[0].toLowerCase() === key.toLowerCase()) return pair[1];
                            }
                        } else if (typeof headers === 'object') {
                            return headers[key] || headers[key.toLowerCase()] || '';
                        }
                        return '';
                    };
                    const auth = readHeader('Authorization');
                    const cTok = readHeader('client-token');
                    if (auth && auth.toLowerCase().startsWith('bearer ') && auth.length > 30) {
                        window.__claraSpotifyToken = auth;
                    }
                    if (cTok && cTok.length > 20) {
                        window.__claraClientToken = cTok;
                    }
                    if (reqUrl.includes('pathfinder/v1/query') || reqUrl.includes('pathfinder/v2/query')) {
                        if (reqUrl.includes('operationName=libraryV3') || reqBody.includes('"libraryV3"')) {
                            window.__claraLibraryV3Url = reqUrl.split('?')[0];
                            const h = extractPersistedHash(reqUrl, reqBody);
                            if (h) window.__claraLibraryV3Hash = h;
                        }
                        if (reqUrl.includes('operationName=fetchLibraryTracks') || reqBody.includes('"fetchLibraryTracks"')) {
                            const h = extractPersistedHash(reqUrl, reqBody);
                            if (h) window.__claraFetchLikedHash = h;
                        }
                    }
                } catch (e) {}

                const response = await origFetch.apply(this, args);
                try {
                    if (reqUrl.includes('pathfinder/v1/query') || reqUrl.includes('pathfinder/v2/query') || reqUrl.includes('/v1/me/playlists') || reqUrl.includes('/v1/me/tracks')) {
                        const cloned = response.clone();
                        cloned.json().then(function(json) {
                            try {
                                if (!json || typeof json !== 'object') return;
                                const libV3 = json.data && json.data.me && json.data.me.libraryV3;
                                if (libV3) {
                                    parseLibraryV3Node(libV3);
                                } else if (reqUrl.includes('/v1/me/playlists') && Array.isArray(json.items)) {
                                    for (const p of json.items) {
                                        if (p && p.id && p.name) {
                                            const owner = (p.owner && p.owner.display_name) || 'Spotify';
                                            const total = (p.tracks && p.tracks.total) || 0;
                                            const art = extractCoverFromNode(p);
                                            addLibraryPlaylist(p.id, p.name, owner, total, art);
                                        }
                                    }
                                }

                                const libTracks = json.data && json.data.me && json.data.me.library && json.data.me.library.tracks;
                                if (libTracks && Array.isArray(libTracks.items)) {
                                    const parsedLiked = extractTracksFromItemsArray(libTracks.items);
                                    for (const lt of parsedLiked) {
                                        if (!window.__claraLikedTracks.some(x => x.title === lt.title && x.subtitle === lt.subtitle)) {
                                            window.__claraLikedTracks.push(lt);
                                        }
                                    }
                                } else if (reqUrl.includes('/v1/me/tracks') && Array.isArray(json.items)) {
                                    const parsedLiked = extractTracksFromItemsArray(json.items);
                                    for (const lt of parsedLiked) {
                                        if (!window.__claraLikedTracks.some(x => x.title === lt.title && x.subtitle === lt.subtitle)) {
                                            window.__claraLikedTracks.push(lt);
                                        }
                                    }
                                }

                                const plV2 = json.data && json.data.playlistV2;
                                if (plV2 && plV2.uri && plV2.content && Array.isArray(plV2.content.items)) {
                                    const plId = String(plV2.uri).replace('spotify:playlist:', '').trim();
                                    if (validIdRegex.test(plId)) {
                                        const trs = extractTracksFromItemsArray(plV2.content.items);
                                        if (trs.length > 0) {
                                            window.__claraPlaylistTracksMap[plId] = trs;
                                        }
                                    }
                                }
                            } catch (e) {}
                        }).catch(function() {});
                    }
                } catch (e) {}
                return response;
            };

            const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
            XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                try {
                    if (name && name.toLowerCase() === 'authorization' && value && value.toLowerCase().startsWith('bearer ') && value.length > 30) {
                        window.__claraSpotifyToken = value;
                    }
                    if (name && name.toLowerCase() === 'client-token' && value && value.length > 20) {
                        window.__claraClientToken = value;
                    }
                } catch (e) {}
                return origSetHeader.apply(this, arguments);
            };
        })();
        """.trimIndent()
    }

    fun triggerAsyncWebExtraction(wv: WebView?) {
        if (hasCompletedCapture || wv == null) return
        isExtractingInBackground = true
        extractionHeadline = "Scanning your complete library, cover art & Liked Songs..."
        extractionDetail = "Reading your personal ${provider.label} playlists and saved tracks in the background..."

        val injectedKotlinToken = capturedBearerToken.replace("'", "")
        val injectedClientToken = capturedClientToken.replace("'", "")

        if (provider == MusicProvider.SPOTIFY) {
            wv.evaluateJavascript(
                """
                (async function() {
                    try {
                        const sleep = ms => new Promise(r => setTimeout(r, ms));
                        const reportStatus = function(h, d) {
                            try { window.ClaraWebBridge.postStatusUpdate(h, d); } catch (e) {}
                        };
                        const validIdRegex = /^[A-Za-z0-9]{22}$/;
                        const blockedNames = new Set([
                            'liked songs', 'your library', 'create playlist', 'getting started',
                            'your episodes', 'new episodes', 'episodes', 'home', 'search', 'browse',
                            'playlists', 'by you'
                        ]);

                        reportStatus(
                            'Scanning your complete library, cover art & Liked Songs...',
                            'Authenticating with your Spotify library session...'
                        );

                        let token = window.__claraSpotifyToken || '$injectedKotlinToken' || '';
                        let clientToken = window.__claraClientToken || '$injectedClientToken' || '';

                        if (!token) {
                            try {
                                const tokResp = await fetch('https://open.spotify.com/get_access_token?reason=transport&productType=web_player', {
                                    credentials: 'include'
                                });
                                if (tokResp.ok) {
                                    const tokJson = await tokResp.json();
                                    if (tokJson && tokJson.accessToken && tokJson.accessToken.length > 25) {
                                        token = 'Bearer ' + tokJson.accessToken;
                                        window.__claraSpotifyToken = token;
                                    }
                                }
                            } catch (e) {}
                        }

                        const playlistsMap = new Map();
                        const addPlaylistEntry = function(id, title, subtitle, trackCount, artworkUrl) {
                            if (!id || !validIdRegex.test(id) || !title) return;
                            const cleanTitle = String(title).trim();
                            if (!cleanTitle || blockedNames.has(cleanTitle.toLowerCase())) return;
                            if (!playlistsMap.has(id)) {
                                playlistsMap.set(id, {
                                    id: id,
                                    title: cleanTitle,
                                    subtitle: subtitle || 'Spotify Playlist',
                                    artworkUrl: artworkUrl || '',
                                    trackCount: trackCount || 0
                                });
                            } else {
                                const existing = playlistsMap.get(id);
                                if (trackCount > 0 && existing.trackCount === 0) {
                                    existing.trackCount = trackCount;
                                    existing.subtitle = subtitle || existing.subtitle;
                                }
                                if (artworkUrl && !existing.artworkUrl) {
                                    existing.artworkUrl = artworkUrl;
                                }
                            }
                        };

                        for (const gp of (window.__claraLibraryPlaylists || [])) {
                            if (gp && gp.id && gp.title) {
                                addPlaylistEntry(gp.id, gp.title, gp.subtitle, gp.trackCount, gp.artworkUrl);
                            }
                        }

                        if (token && window.__claraLibraryV3Hash) {
                            reportStatus(
                                'Scanning your complete library, cover art & Liked Songs...',
                                'Fetching your complete Spotify playlist catalog...'
                            );
                            const gqlEndpoint = window.__claraLibraryV3Url || 'https://api-partner.spotify.com/pathfinder/v1/query';
                            for (const pageOffset of [0, 50]) {
                                try {
                                    const variables = {
                                        filters: ['Playlists'],
                                        order: null,
                                        textFilter: '',
                                        features: ['LIKED_SONGS', 'YOUR_EPISODES'],
                                        limit: 500,
                                        offset: pageOffset,
                                        flatten: false,
                                        expandedFolders: [],
                                        folderUri: null,
                                        includeFoldersWhenFlattening: true
                                    };
                                    const extensions = {
                                        persistedQuery: {
                                            version: 1,
                                            sha256Hash: window.__claraLibraryV3Hash
                                        }
                                    };
                                    const url = gqlEndpoint
                                        + '?operationName=libraryV3'
                                        + '&variables=' + encodeURIComponent(JSON.stringify(variables))
                                        + '&extensions=' + encodeURIComponent(JSON.stringify(extensions));
                                    const hdrs = { 'Authorization': token };
                                    if (clientToken) hdrs['client-token'] = clientToken;
                                    const resp = await fetch(url, { headers: hdrs });
                                    if (!resp.ok) break;
                                    const j = await resp.json();
                                    const items = (j && j.data && j.data.me && j.data.me.libraryV3 && j.data.me.libraryV3.items) || [];
                                    if (items.length === 0) break;
                                    for (const entry of items) {
                                        const itemNode = (entry && entry.item) || entry;
                                        const dataNode = (itemNode && (itemNode.data || itemNode.playlist)) || itemNode;
                                        const uri = (dataNode && dataNode.uri) || (itemNode && itemNode.uri) || '';
                                        const name = (dataNode && dataNode.name) || (itemNode && itemNode.name) || '';
                                        if (typeof uri === 'string' && uri.startsWith('spotify:playlist:') && name) {
                                            const plId = uri.replace('spotify:playlist:', '').trim();
                                            const owner = (dataNode.ownerV2 && dataNode.ownerV2.data && dataNode.ownerV2.data.name) || 'Spotify';
                                            const count = itemNode.count || dataNode.totalLength || 0;
                                            let art = '';
                                            if (dataNode.images && Array.isArray(dataNode.images.items) && dataNode.images.items[0] && Array.isArray(dataNode.images.items[0].sources) && dataNode.images.items[0].sources[0]) {
                                                art = dataNode.images.items[0].sources[0].url || '';
                                            }
                                            addPlaylistEntry(plId, name, 'Playlist • ' + owner, count, art);
                                        }
                                    }
                                } catch (e) { break; }
                            }
                        }

                        const libRoot = document.querySelector('[aria-label="Your Library"], #Desktop_LeftSidebar_Id, nav');
                        if (libRoot) {
                            const chips = Array.from(libRoot.querySelectorAll('button, [role="checkbox"], [role="tab"]'));
                            const plChip = chips.find(c => (c.innerText || '').trim() === 'Playlists');
                            if (plChip && plChip.getAttribute('aria-checked') !== 'true' && plChip.getAttribute('aria-selected') !== 'true') {
                                plChip.click();
                                await sleep(500);
                            }

                            const scrapeSidebarDom = function() {
                                const candidates = Array.from(
                                    libRoot.querySelectorAll('a[href*="/playlist/"], [data-encore-id="card"], [data-encore-id="listRow"], [role="row"], [role="gridcell"], li[role="listitem"]')
                                );
                                for (const el of candidates) {
                                    const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href*="/playlist/"]');
                                    const href = (linkEl && linkEl.getAttribute('href')) || el.getAttribute('href') || '';
                                    let plId = '';
                                    if (href.includes('/playlist/')) {
                                        plId = href.split('/playlist/')[1].split('?')[0].split('/')[0].trim();
                                    }
                                    if (!validIdRegex.test(plId)) {
                                        const ariaTarget = el.getAttribute('aria-labelledby') || (el.querySelector('[aria-labelledby]') && el.querySelector('[aria-labelledby]').getAttribute('aria-labelledby')) || el.id || '';
                                        const m = ariaTarget.match(/playlist:([A-Za-z0-9]{22})/);
                                        if (m && m[1]) plId = m[1];
                                    }
                                    if (!validIdRegex.test(plId)) continue;
                                    const imgEl = el.querySelector('img');
                                    const artUrl = (imgEl && imgEl.src && imgEl.src.startsWith('http')) ? imgEl.src : '';
                                    const lines = (el.innerText || '').split('\n').map(s => s.trim()).filter(s => s.length > 0 && s !== 'Pinned');
                                    const title = lines[0] || '';
                                    const sub = lines.slice(1).join(' • ') || 'Spotify Playlist';
                                    addPlaylistEntry(plId, title, sub, 0, artUrl);
                                }
                                return candidates;
                            };

                            const scrollContainers = Array.from(libRoot.querySelectorAll('[data-overlayscrollbars-viewport], .os-viewport, div'))
                                .filter(el => el.scrollHeight > el.clientHeight + 20);
                            const scroller = scrollContainers[0] || libRoot;

                            for (let step = 0; step < 14; step++) {
                                const els = scrapeSidebarDom();
                                reportStatus(
                                    'Scanning your complete library, cover art & Liked Songs...',
                                    'Found ' + playlistsMap.size + ' playlists so far (scanning page ' + (step + 1) + ' of 14)...'
                                );
                                if (els.length > 0) {
                                    try {
                                        els[els.length - 1].scrollIntoView({ block: 'end' });
                                    } catch (e) {}
                                }
                                scroller.scrollTop += 320;
                                await sleep(220);
                            }
                            scrapeSidebarDom();
                            scroller.scrollTop = 0;

                            for (const gp of (window.__claraLibraryPlaylists || [])) {
                                if (gp && gp.id && gp.title) {
                                    addPlaylistEntry(gp.id, gp.title, gp.subtitle, gp.trackCount, gp.artworkUrl);
                                }
                            }
                        }

                        if (token && token.length > 25) {
                            let offset = 0;
                            while (offset < 300) {
                                try {
                                    const plResp = await fetch('https://api.spotify.com/v1/me/playlists?limit=50&offset=' + offset, {
                                        headers: { 'Authorization': token }
                                    });
                                    if (!plResp.ok) break;
                                    const plData = await plResp.json();
                                    const items = plData.items || [];
                                    for (const p of items) {
                                        if (p && p.id && p.name) {
                                            const owner = (p.owner && p.owner.display_name) ? p.owner.display_name : 'Spotify';
                                            const total = (p.tracks && p.tracks.total) ? p.tracks.total : 0;
                                            const art = (Array.isArray(p.images) && p.images[0] && p.images[0].url) ? p.images[0].url : '';
                                            addPlaylistEntry(
                                                p.id,
                                                p.name,
                                                'Playlist • ' + owner + ' (' + total + ' tracks)',
                                                total,
                                                art
                                            );
                                        }
                                    }
                                    if (items.length === 0 || !plData.next) break;
                                    offset += 50;
                                } catch (e) { break; }
                            }
                        }

                        reportStatus(
                            'Importing your library & Liked Songs...',
                            'Syncing your saved Liked Songs and high-resolution cover art (' + playlistsMap.size + ' playlists found)...'
                        );

                        const likedTracks = [...(window.__claraLikedTracks || [])];
                        const addLikedTrack = function(id, title, subtitle, durationMs, artworkUrl) {
                            if (!title) return;
                            const cleanT = String(title).trim();
                            const cleanS = String(subtitle || '').trim();
                            if (!cleanT) return;
                            if (!likedTracks.some(x => x.title.toLowerCase() === cleanT.toLowerCase() && x.subtitle.toLowerCase() === cleanS.toLowerCase())) {
                                likedTracks.push({
                                    id: 'sp_liked_' + (id || likedTracks.length),
                                    title: cleanT,
                                    subtitle: cleanS,
                                    artworkUrl: artworkUrl || '',
                                    durationMs: durationMs || 0
                                });
                            }
                        };

                        if (token && token.length > 25) {
                            let likedOffset = 0;
                            while (likedOffset < 150) {
                                try {
                                    const trResp = await fetch('https://api.spotify.com/v1/me/tracks?limit=50&offset=' + likedOffset, {
                                        headers: { 'Authorization': token }
                                    });
                                    if (!trResp.ok) break;
                                    const trData = await trResp.json();
                                    const items = trData.items || [];
                                    for (const item of items) {
                                        const tr = item && item.track;
                                        if (tr && tr.name) {
                                            const artist = (tr.artists && tr.artists[0] && tr.artists[0].name) ? tr.artists[0].name : '';
                                            const art = (tr.album && Array.isArray(tr.album.images) && tr.album.images[0]) ? tr.album.images[0].url : '';
                                            addLikedTrack(tr.id, tr.name, artist, tr.duration_ms || 0, art);
                                        }
                                    }
                                    if (items.length === 0 || !trData.next) break;
                                    likedOffset += 50;
                                } catch (e) { break; }
                            }
                        }

                        if (likedTracks.length === 0) {
                            const likedLink = document.querySelector('a[href="/collection/tracks"], [aria-labelledby*="collection:tracks"]');
                            if (likedLink) {
                                likedLink.click();
                                await sleep(1400);
                                for (const lt of (window.__claraLikedTracks || [])) {
                                    addLikedTrack(lt.id, lt.title, lt.subtitle, lt.durationMs, lt.artworkUrl);
                                }
                                const trackRows = Array.from(document.querySelectorAll('[data-testid="tracklist-row"]'));
                                for (let i = 0; i < trackRows.length; i++) {
                                    const row = trackRows[i];
                                    const titleEl = row.querySelector('a[href*="/track/"], [dir="auto"]');
                                    const trackLink = row.querySelector('a[href*="/track/"]');
                                    const href = (trackLink && trackLink.getAttribute('href')) || '';
                                    const trId = href.includes('/track/') ? href.split('/track/')[1].split('?')[0].trim() : ('dom_' + i);
                                    const artistEls = Array.from(row.querySelectorAll('a[href*="/artist/"]'));
                                    const imgEl = row.querySelector('img');
                                    const tName = titleEl ? (titleEl.innerText || '').trim() : '';
                                    const tArtist = artistEls.map(a => (a.innerText || '').trim()).filter(Boolean).join(', ');
                                    const tArt = (imgEl && imgEl.src) ? imgEl.src : '';
                                    if (tName) {
                                        addLikedTrack(trId, tName, tArtist, 0, tArt);
                                    }
                                }
                            }
                        }

                        reportStatus(
                            'Finishing library import...',
                            'Saving ' + playlistsMap.size + ' playlists and ' + likedTracks.length + ' Liked Songs into Clara...'
                        );

                        const finalPlaylists = Array.from(playlistsMap.values());
                        window.ClaraWebBridge.postExtractedData(JSON.stringify({
                            token: token,
                            clientToken: clientToken,
                            playlists: finalPlaylists,
                            likedTracks: likedTracks,
                            playlistTracksMap: window.__claraPlaylistTracksMap || {}
                        }));
                    } catch (e) {
                        window.ClaraWebBridge.postExtractedData(JSON.stringify({
                            token: '$injectedKotlinToken',
                            clientToken: '$injectedClientToken',
                            playlists: window.__claraLibraryPlaylists || [],
                            likedTracks: window.__claraLikedTracks || [],
                            playlistTracksMap: window.__claraPlaylistTracksMap || {}
                        }));
                    }
                })();
                """.trimIndent(),
                null
            )
        } else {
            wv.evaluateJavascript(
                """
                (function() {
                    try {
                        window.ClaraWebBridge.postStatusUpdate(
                            'Scanning your complete YouTube Music library...',
                            'Importing your playlists and cover art...'
                        );
                        const items = Array.from(document.querySelectorAll('ytmusic-two-row-item-renderer, ytmusic-responsive-list-item-renderer'));
                        const out = items.map(el => {
                            const t = el.querySelector('.title, yt-formatted-string.title');
                            const s = el.querySelector('.subtitle, yt-formatted-string.subtitle');
                            const img = el.querySelector('img');
                            return {
                                title: t ? t.innerText.trim() : '',
                                subtitle: s ? s.innerText.trim() : '',
                                artworkUrl: (img && img.src) ? img.src : ''
                            };
                        }).filter(x => x.title.length > 0);
                        window.ClaraWebBridge.postExtractedData(JSON.stringify({ playlists: out }));
                    } catch (e) {
                        window.ClaraWebBridge.postExtractedData(JSON.stringify({ playlists: [] }));
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.90f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isExtractingInBackground) {
                                "Syncing ${provider.label} Library"
                            } else {
                                "Sign in to ${provider.label}"
                            },
                            color = GhTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = extractionHeadline,
                            color = GhAccentGreen,
                            fontSize = 11.sp,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Surface(
                    color = Color(0xFF121212),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GhTileSurfaceElevated),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .alpha(if (isExtractingInBackground) 0f else 1f),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        mediaPlaybackRequiresUserGesture = false
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        userAgentString =
                                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onPermissionRequest(request: PermissionRequest?) {
                                            val req = request ?: return
                                            try {
                                                req.grant(req.resources)
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }

                                    addJavascriptInterface(
                                        ClaraWebLoginJsBridge(
                                            onStatusChanged = { headline, detail ->
                                                scope.launch(Dispatchers.Main) {
                                                    isExtractingInBackground = true
                                                    if (headline.isNotBlank()) extractionHeadline = headline
                                                    if (detail.isNotBlank()) extractionDetail = detail
                                                }
                                            },
                                            onDataReceived = { rawJson ->
                                                if (hasCompletedCapture) return@ClaraWebLoginJsBridge
                                                val parsed = runCatching { JSONObject(rawJson) }.getOrDefault(JSONObject())
                                                if (provider == MusicProvider.SPOTIFY) {
                                                    val cookie = CookieManager.getInstance()
                                                        .getCookie("https://open.spotify.com") ?: ""
                                                    val jsToken = parsed.optString("token", "").ifBlank { capturedBearerToken }
                                                    val jsClientToken = parsed.optString("clientToken", "").ifBlank { capturedClientToken }
                                                    val plArr = parsed.optJSONArray("playlists")?.toString() ?: "[]"
                                                    val likedArr = parsed.optJSONArray("likedTracks")?.toString() ?: "[]"
                                                    val plTracksMap = parsed.optJSONObject("playlistTracksMap")?.toString() ?: "{}"
                                                    if (jsToken.length > 20 || plArr != "[]" || likedArr != "[]") {
                                                        hasCompletedCapture = true
                                                        NativeMusicCatalogClient.saveWebCapturedSpotifySession(
                                                            context = context,
                                                            bearerToken = jsToken,
                                                            cookieHeader = cookie,
                                                            extractedPlaylistsJson = plArr,
                                                            extractedLikedTracksJson = likedArr,
                                                            extractedPlaylistTracksMapJson = plTracksMap,
                                                            clientToken = jsClientToken
                                                        )
                                                        scope.launch(Dispatchers.Main) {
                                                            onSessionCaptured()
                                                        }
                                                    }
                                                } else {
                                                    hasCompletedCapture = true
                                                    val cookie = CookieManager.getInstance()
                                                        .getCookie("https://music.youtube.com") ?: ""
                                                    val plArr = parsed.optJSONArray("playlists")?.toString() ?: "[]"
                                                    NativeMusicCatalogClient.saveWebCapturedYouTubeSession(
                                                        context = context,
                                                        cookieHeader = cookie,
                                                        extractedPlaylistsJson = plArr
                                                    )
                                                    scope.launch(Dispatchers.Main) {
                                                        onSessionCaptured()
                                                    }
                                                }
                                            }
                                        ),
                                        "ClaraWebBridge"
                                    )

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            val currentUrl = url ?: ""
                                            if (provider == MusicProvider.SPOTIFY) {
                                                view?.evaluateJavascript(spotifyTokenAndLibraryHookJs, null)
                                                val cookie = CookieManager.getInstance()
                                                    .getCookie("https://open.spotify.com") ?: ""
                                                if (currentUrl.startsWith("https://open.spotify.com") && cookie.contains("sp_dc=")) {
                                                    isExtractingInBackground = true
                                                    extractionHeadline = "Scanning your complete library, cover art & Liked Songs..."
                                                    extractionDetail = "Connected to Spotify \u2014 preparing to import your playlists and tracks..."
                                                }
                                            } else if (provider == MusicProvider.YOUTUBE_MUSIC) {
                                                val cookie = CookieManager.getInstance()
                                                    .getCookie("https://music.youtube.com") ?: ""
                                                if (currentUrl.startsWith("https://music.youtube.com") &&
                                                    (cookie.contains("SAPISID=") || cookie.contains("LOGIN_INFO="))
                                                ) {
                                                    isExtractingInBackground = true
                                                    extractionHeadline = "Scanning your complete YouTube Music library..."
                                                    extractionDetail = "Connected to YouTube Music \u2014 preparing to import your playlists..."
                                                }
                                            }
                                        }

                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): WebResourceResponse? {
                                            val headers = request?.requestHeaders ?: emptyMap()
                                            val authHeader = headers.entries
                                                .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                                ?.value ?: ""
                                            val clientTokHeader = headers.entries
                                                .firstOrNull { it.key.equals("client-token", ignoreCase = true) }
                                                ?.value ?: ""
                                            if (provider == MusicProvider.SPOTIFY) {
                                                if (authHeader.startsWith("Bearer ", ignoreCase = true) && authHeader.length > 35) {
                                                    capturedBearerToken = authHeader
                                                }
                                                if (clientTokHeader.length > 20) {
                                                    capturedClientToken = clientTokHeader
                                                }
                                            }
                                            return super.shouldInterceptRequest(view, request)
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val currentUrl = url ?: return
                                            if (provider == MusicProvider.SPOTIFY &&
                                                currentUrl.startsWith("https://open.spotify.com")
                                            ) {
                                                view?.evaluateJavascript(spotifyTokenAndLibraryHookJs, null)
                                                val cookie = CookieManager.getInstance()
                                                    .getCookie("https://open.spotify.com") ?: ""
                                                if (cookie.contains("sp_dc=")) {
                                                    isExtractingInBackground = true
                                                    extractionHeadline = "Scanning your complete library, cover art & Liked Songs..."
                                                    extractionDetail = "Loading your Spotify playlists and saved songs in the background..."
                                                    scope.launch {
                                                        delay(2200L)
                                                        if (!hasCompletedCapture) {
                                                            extractionDetail = "Reading your playlist catalog and high-resolution cover art..."
                                                        }
                                                        delay(3000L)
                                                        triggerAsyncWebExtraction(view)
                                                    }
                                                } else {
                                                    isExtractingInBackground = false
                                                }
                                            } else if (provider == MusicProvider.YOUTUBE_MUSIC &&
                                                currentUrl.startsWith("https://music.youtube.com")
                                            ) {
                                                val cookie = CookieManager.getInstance()
                                                    .getCookie("https://music.youtube.com") ?: ""
                                                if (cookie.contains("SAPISID=") || cookie.contains("LOGIN_INFO=")) {
                                                    isExtractingInBackground = true
                                                    extractionHeadline = "Scanning your complete YouTube Music library..."
                                                    extractionDetail = "Loading your playlists and liked songs in the background..."
                                                    scope.launch {
                                                        delay(3500L)
                                                        triggerAsyncWebExtraction(view)
                                                    }
                                                } else {
                                                    isExtractingInBackground = false
                                                }
                                            } else if (currentUrl.contains("accounts.spotify.com") ||
                                                currentUrl.contains("accounts.google.com")
                                            ) {
                                                isExtractingInBackground = false
                                                extractionHeadline = "Sign in to your ${provider.label} account below"
                                            }
                                        }
                                    }
                                    loadUrl(loginUrl)
                                    webViewRef = this
                                }
                            }
                        )

                        if (isExtractingInBackground) {
                            val infiniteTransition = rememberInfiniteTransition(label = "LibrarySyncPulse")
                            val iconPulseScale by infiniteTransition.animateFloat(
                                initialValue = 0.92f,
                                targetValue = 1.08f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "SyncIconScale"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(GhTileSurface)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                    modifier = Modifier.widthIn(max = 520.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(88.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = GhAccentGreen,
                                            strokeWidth = 3.5.dp,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Surface(
                                            color = GhAccentGreen.copy(alpha = 0.18f),
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .size(64.dp)
                                                .scale(iconPulseScale)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Outlined.LibraryMusic,
                                                    contentDescription = null,
                                                    tint = GhAccentGreen,
                                                    modifier = Modifier.size(30.dp)
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = extractionHeadline,
                                        color = GhTextPrimary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = GhFont,
                                        textAlign = TextAlign.Center
                                    )

                                    Text(
                                        text = extractionDetail,
                                        color = GhAccentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = GhFont,
                                        textAlign = TextAlign.Center
                                    )

                                    LinearProgressIndicator(
                                        color = GhAccentBlue,
                                        trackColor = GhTileSurfaceElevated,
                                        modifier = Modifier
                                            .fillMaxWidth(0.75f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(50))
                                    )

                                    Surface(
                                        color = GhTileSurfaceElevated.copy(alpha = 0.65f),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = GhAccentBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Automatic background sync in progress \u2014 this window will close automatically once your playlists, cover art & Liked Songs are ready.",
                                                color = GhTextSecondary,
                                                fontSize = 11.sp,
                                                fontFamily = GhFont
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
