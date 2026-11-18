package com.jarvis.smarthomelauncher

import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.popups.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// CLOUD & LOCAL PHOTO FOLDER / SUBFOLDER MODELS
// ---------------------------------------------------------------------------

data class CloudPhotoAlbumOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val albumUrl: String,
    val coverUrl: String = "",
    val isFolder: Boolean = false
)

data class LocalPhotoFolderOption(
    val folderPath: String,
    val displayName: String,
    val parentPath: String,
    val depth: Int,
    val photoUris: List<String>
)

// ---------------------------------------------------------------------------
// PHOTO SLIDESHOW RESOLVER, SUBFOLDER SCANNER & HIGH-RES BITMAP CACHE
// - Identifies both parent folders and nested subfolders on Local Storage
//   (via MediaStore RELATIVE_PATH + DocumentFile SAF recursive tree traversal)
//   and in Cloud (Google Photos / OneDrive folder & subfolder drill-down).
// - Preserves full 2K/4K image sharpness with non-destructive inSampleSize
//   calculation and automatic cloud thumbnail-to-original URL upgrading.
// ---------------------------------------------------------------------------

object PhotoSlideshowResolver {
    private val bitmapCache = object : LruCache<String, ImageBitmap>(36) {}
    private val cloudAlbumUrlCache = ConcurrentHashMap<String, List<String>>()

    /**
     * Upgrades cloud thumbnail URLs (Google Photos `=w...` or OneDrive `width=...&height=...`)
     * to crisp 2560x1600 high-resolution streams prior to downloading.
     */
    fun upgradeCloudImageUrlToHighRes(rawUrl: String): String {
        val clean = rawUrl.trim()
        if (clean.isBlank()) return clean

        // 1. Google Photos lh3.googleusercontent.com high-resolution parameter upgrade
        if (clean.contains("lh3.googleusercontent.com")) {
            val base = clean.substringBefore("=")
            return "$base=w2560-h1600"
        }

        // 2. Microsoft OneDrive / SharePoint / 1drv thumbnail URL upgrade
        if (clean.contains("1drv.com", ignoreCase = true) ||
            clean.contains("onedrive.com", ignoreCase = true) ||
            clean.contains("live.com", ignoreCase = true) ||
            clean.contains("sharepoint.com", ignoreCase = true) ||
            clean.contains("microsoftpersonalcontent.com", ignoreCase = true)
        ) {
            var upgraded = clean
                .replace(Regex("([?&])width=\\d+", RegexOption.IGNORE_CASE), "$1width=2560")
                .replace(Regex("([?&])height=\\d+", RegexOption.IGNORE_CASE), "$1height=1600")
                .replace(Regex("/c\\d+x\\d+/", RegexOption.IGNORE_CASE), "/c2560x1600/")
            if (upgraded.contains("thumbnail", ignoreCase = true) &&
                !upgraded.contains("width=", ignoreCase = true)
            ) {
                val sep = if (upgraded.contains("?")) "&" else "?"
                upgraded = "${upgraded}${sep}width=2560&height=1600"
            }
            return upgraded
        }

        return clean
    }

    /**
     * Scans MediaStore on the device to identify all photo folders AND their nested subfolders
     * (e.g. `Pictures/Holiday` and `Pictures/Holiday/Beach`).
     */
    suspend fun scanLocalDevicePhotoFolders(context: Context): List<LocalPhotoFolderOption> =
        withContext(Dispatchers.IO) {
            val folderMap = LinkedHashMap<String, MutableList<String>>()
            try {
                val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Images.Media.RELATIVE_PATH
                    )
                } else {
                    @Suppress("DEPRECATION")
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Images.Media.DATA
                    )
                }
                val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    } else {
                        @Suppress("DEPRECATION")
                        cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    }

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        ).toString()
                        val bucket = if (bucketCol >= 0) cursor.getString(bucketCol).orEmpty() else "Photos"
                        val rawPath = if (relCol >= 0) cursor.getString(relCol).orEmpty() else bucket
                        val normalizedPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            rawPath.trim('/').ifBlank { bucket.ifBlank { "Pictures" } }
                        } else {
                            rawPath.substringBeforeLast('/', bucket).trim('/').substringAfter("0/").ifBlank { bucket }
                        }
                        folderMap.getOrPut(normalizedPath) { mutableListOf() }.add(contentUri)
                    }
                }
            } catch (_: Exception) {
            }

            // Ensure parent folders exist in the hierarchy so subfolders are clearly nested under their parent
            val allPaths = LinkedHashSet<String>()
            for (path in folderMap.keys) {
                val segments = path.split('/').filter { it.isNotBlank() }
                var running = ""
                for (seg in segments) {
                    running = if (running.isEmpty()) seg else "$running/$seg"
                    allPaths.add(running)
                }
            }

            allPaths.sorted().map { path ->
                val segments = path.split('/').filter { it.isNotBlank() }
                val depth = (segments.size - 1).coerceAtLeast(0)
                val parentPath = if (segments.size > 1) segments.dropLast(1).joinToString("/") else ""
                val displayName = segments.lastOrNull() ?: path
                LocalPhotoFolderOption(
                    folderPath = path,
                    displayName = displayName,
                    parentPath = parentPath,
                    depth = depth,
                    photoUris = folderMap[path]?.toList().orEmpty()
                )
            }
        }

    /**
     * Recursively scans a SAF directory tree URI (`content://.../tree/...`) and discovers
     * both the root folder and all nested subfolders.
     */
    suspend fun scanSafTreeWithSubfolders(
        context: Context,
        treeUri: Uri
    ): List<LocalPhotoFolderOption> = withContext(Dispatchers.IO) {
        val results = mutableListOf<LocalPhotoFolderOption>()
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            fun traverse(dir: DocumentFile, currentPath: String, parentPath: String, depth: Int) {
                val photosInThisDir = mutableListOf<String>()
                val childDirs = mutableListOf<DocumentFile>()
                for (child in dir.listFiles()) {
                    if (child.isDirectory) {
                        childDirs.add(child)
                    } else {
                        val mime = child.type.orEmpty().lowercase()
                        val name = child.name.orEmpty().lowercase()
                        if (mime.startsWith("image/") ||
                            name.endsWith(".jpg") ||
                            name.endsWith(".jpeg") ||
                            name.endsWith(".png") ||
                            name.endsWith(".webp") ||
                            name.endsWith(".heic")
                        ) {
                            photosInThisDir.add(child.uri.toString())
                        }
                    }
                }
                val folderName = dir.name?.ifBlank { "Folder" } ?: "Folder"
                results.add(
                    LocalPhotoFolderOption(
                        folderPath = currentPath,
                        displayName = folderName,
                        parentPath = parentPath,
                        depth = depth,
                        photoUris = photosInThisDir
                    )
                )
                for (sub in childDirs.sortedBy { it.name.orEmpty() }) {
                    val subName = sub.name?.ifBlank { "Subfolder" } ?: "Subfolder"
                    traverse(
                        dir = sub,
                        currentPath = "$currentPath/$subName",
                        parentPath = currentPath,
                        depth = depth + 1
                    )
                }
            }
            val rootName = rootDoc.name?.ifBlank { "Selected Folder" } ?: "Selected Folder"
            traverse(rootDoc, rootName, "", 0)
        } catch (_: Exception) {
        }
        results
    }

    suspend fun resolveMediaItems(
        sourceType: SlideshowSourceType,
        localUris: List<String>,
        cloudUrl: String
    ): List<String> = withContext(Dispatchers.IO) {
        when (sourceType) {
            SlideshowSourceType.LOCAL_DEVICE -> {
                localUris.map { it.trim() }.filter { it.isNotBlank() }
            }
            SlideshowSourceType.CUSTOM_URLS -> {
                cloudUrl.split(",", "\n", ";")
                    .map { upgradeCloudImageUrlToHighRes(it.trim()) }
                    .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            }
            SlideshowSourceType.GOOGLE_PHOTOS -> {
                val clean = cloudUrl.trim()
                if (clean.isBlank()) return@withContext emptyList()
                cloudAlbumUrlCache[clean]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

                val entries = clean.split(",", "\n", ";").map { it.trim() }.filter { it.isNotBlank() }
                val combined = LinkedHashSet<String>()
                for (entry in entries) {
                    if (entry.contains("lh3.googleusercontent.com") ||
                        entry.endsWith(".jpg", true) ||
                        entry.endsWith(".jpeg", true) ||
                        entry.endsWith(".png", true)
                    ) {
                        combined.add(upgradeCloudImageUrlToHighRes(entry))
                    } else if (entry.startsWith("http")) {
                        combined.addAll(extractGooglePhotosAlbumUrls(entry))
                    }
                }
                val finalList = combined.toList()
                if (finalList.isNotEmpty()) {
                    cloudAlbumUrlCache[clean] = finalList
                }
                finalList
            }
            SlideshowSourceType.ONEDRIVE -> {
                val clean = cloudUrl.trim()
                if (clean.isBlank()) return@withContext emptyList()
                cloudAlbumUrlCache[clean]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

                val entries = clean.split(",", "\n", ";").map { it.trim() }.filter { it.isNotBlank() }
                val combined = LinkedHashSet<String>()
                for (entry in entries) {
                    if (entry.contains("download", true) ||
                        entry.contains("thumbnail", true) ||
                        entry.contains("microsoftpersonalcontent.com", true) ||
                        entry.endsWith(".jpg", true) ||
                        entry.endsWith(".jpeg", true) ||
                        entry.endsWith(".png", true)
                    ) {
                        combined.add(upgradeCloudImageUrlToHighRes(entry))
                    } else if (entry.startsWith("http")) {
                        combined.addAll(extractOneDriveSharedUrls(entry))
                    }
                }
                val finalList = combined.toList()
                if (finalList.isNotEmpty()) {
                    cloudAlbumUrlCache[clean] = finalList
                }
                finalList
            }
        }
    }

    fun clearCloudCache() {
        cloudAlbumUrlCache.clear()
    }

    private fun getCookieForUrl(urlStr: String): String {
        return try {
            val cm = CookieManager.getInstance()
            val direct = cm.getCookie(urlStr).orEmpty()
            if (direct.isNotBlank()) return direct
            if (urlStr.contains("1drv") || urlStr.contains("onedrive") || urlStr.contains("microsoft") || urlStr.contains("sharepoint")) {
                listOf(
                    cm.getCookie("https://onedrive.live.com").orEmpty(),
                    cm.getCookie("https://my.microsoftpersonalcontent.com").orEmpty(),
                    cm.getCookie("https://api.onedrive.com").orEmpty()
                ).filter { it.isNotBlank() }.joinToString("; ")
            } else {
                direct
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchUrlHtmlFollowingRedirects(urlStr: String): String {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
            val cookie = getCookieForUrl(currentUrl)
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                if (cookie.isNotBlank()) {
                    setRequestProperty("Cookie", cookie)
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                redirects++
            } else {
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                return text
            }
        }
        return ""
    }

    private fun extractGooglePhotosAlbumUrls(albumUrl: String): List<String> {
        return try {
            val html = fetchUrlHtmlFollowingRedirects(albumUrl)
            if (html.isBlank()) return emptyList()
            val regex = Regex("""https://lh3\.googleusercontent\.com/[a-zA-Z0-9_-]{25,}""")
            val results = LinkedHashSet<String>()
            regex.findAll(html).forEach { match ->
                val baseUrl = match.value
                if (!baseUrl.contains("/a/") && !baseUrl.contains("/a-/")) {
                    results.add("$baseUrl=w2560-h1600")
                }
            }
            results.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractOneDriveSharedUrls(shareUrl: String): List<String> {
        return try {
            val results = LinkedHashSet<String>()
            val html = fetchUrlHtmlFollowingRedirects(shareUrl)
            if (html.isNotBlank()) {
                val unescaped = html.replace("\\u0026", "&").replace("\\/", "/")
                val urlRegex = Regex("""https://[a-zA-Z0-9.-]+\.(?:1drv\.com|onedrive\.com|live\.com|sharepoint\.com|microsoftpersonalcontent\.com)/[^\s"'<>\\]+""")
                urlRegex.findAll(unescaped).forEach { m ->
                    val candidate = m.value
                    if (candidate.contains("download", true) ||
                        candidate.contains("thumbnail", true) ||
                        candidate.contains("Items", true) ||
                        candidate.endsWith(".jpg", true) ||
                        candidate.endsWith(".jpeg", true) ||
                        candidate.endsWith(".png", true)
                    ) {
                        results.add(upgradeCloudImageUrlToHighRes(candidate))
                    }
                }
                val ogMatch = Regex("""property=["']og:image["']\s+content=["']([^"']+)["']""").find(unescaped)
                    ?: Regex("""content=["']([^"']+)["']\s+property=["']og:image["']""").find(unescaped)
                ogMatch?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }?.let {
                    results.add(upgradeCloudImageUrlToHighRes(it))
                }
            }
            if (results.isEmpty() && shareUrl.startsWith("http")) {
                if (shareUrl.contains("1drv.ms")) {
                    val base64 = android.util.Base64.encodeToString(
                        shareUrl.toByteArray(Charsets.UTF_8),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                    )
                    results.add("https://api.onedrive.com/v1.0/shares/u!$base64/root/content")
                } else {
                    results.add(upgradeCloudImageUrlToHighRes(shareUrl))
                }
            }
            results.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun loadBitmap(context: Context, uriOrUrl: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val rawClean = uriOrUrl.trim()
        if (rawClean.isBlank()) return@withContext null
        val clean = if (rawClean.startsWith("http://", true) || rawClean.startsWith("https://", true)) {
            upgradeCloudImageUrlToHighRes(rawClean)
        } else {
            rawClean
        }
        bitmapCache.get(clean)?.let { return@withContext it }

        val decodedBitmap = try {
            if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
                val cookie = getCookieForUrl(clean)
                val conn = (URL(clean).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    )
                    if (cookie.isNotBlank()) {
                        setRequestProperty("Cookie", cookie)
                    }
                }
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    decodeHighResBitmapBytes(bytes, reqWidth = 2560, reqHeight = 1600)
                } else {
                    conn.disconnect()
                    null
                }
            } else {
                val parsedUri = Uri.parse(clean)
                val bytes = context.contentResolver.openInputStream(parsedUri)?.use { it.readBytes() }
                if (bytes != null) {
                    decodeHighResBitmapBytes(bytes, reqWidth = 2560, reqHeight = 1600)
                } else null
            }
        } catch (_: Exception) {
            null
        }

        if (decodedBitmap != null) {
            val imgBmp = decodedBitmap.asImageBitmap()
            bitmapCache.put(clean, imgBmp)
            imgBmp
        } else {
            null
        }
    }

    /**
     * Decodes a high-resolution Bitmap using Android's recommended `halfWidth / inSampleSize >= reqWidth`
     * math so 12MP–24MP photos never get over-downsampled below 2K resolution.
     */
    @Suppress("DEPRECATION")
    private fun decodeHighResBitmapBytes(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val photoW = boundsOpts.outWidth
        val photoH = boundsOpts.outHeight
        if (photoW <= 0 || photoH <= 0) return null

        var sampleSize = 1
        if (photoH > reqHeight || photoW > reqWidth) {
            val halfH = photoH / 2
            val halfW = photoW / 2
            while ((halfH / sampleSize) >= reqHeight && (halfW / sampleSize) >= reqWidth) {
                sampleSize *= 2
            }
        }

        return try {
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inDither = false
                inPreferQualityOverSpeed = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } catch (_: OutOfMemoryError) {
            bitmapCache.evictAll()
            val fallbackOpts = BitmapFactory.Options().apply {
                inSampleSize = (sampleSize * 2).coerceAtLeast(2)
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, fallbackOpts)
            }.getOrNull()
        }
    }
}

// ---------------------------------------------------------------------------
// EDGE-TO-EDGE PHOTO SLIDESHOW WIDGET TILE
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GhEdgeToEdgePhotoSlideshowTile(
    tile: SmartTile,
    bridgeSettings: BridgeSettings,
    isEditMode: Boolean,
    onUpdateTile: (SmartTile) -> Unit,
    onToggleEditMode: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onToggleColSpan: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleHeight: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDeleteTile: () -> Unit
) {
    val context = LocalContext.current
    var showConfigModal by remember(tile.id) { mutableStateOf(false) }
    var isHeaderOverlayVisible by remember(tile.id) { mutableStateOf(true) }
    var overlayTouchNonce by remember { mutableIntStateOf(0) }

    val effectiveSource = if (tile.slideshowLocalUris.isNotEmpty() || tile.slideshowCloudUrl.isNotBlank()) {
        tile.slideshowSourceType
    } else {
        bridgeSettings.slideshowSourceType
    }
    val effectiveLocalUris = if (tile.slideshowLocalUris.isNotEmpty()) {
        tile.slideshowLocalUris
    } else {
        bridgeSettings.slideshowLocalUris
    }
    val effectiveCloudUrl = if (tile.slideshowCloudUrl.isNotBlank()) {
        tile.slideshowCloudUrl
    } else {
        bridgeSettings.slideshowCloudUrl
    }
    val effectiveIntervalSec = tile.slideshowIntervalSeconds.takeIf { it >= 3 }
        ?: bridgeSettings.slideshowIntervalSeconds.coerceAtLeast(3)

    var resolvedItems by remember(effectiveSource, effectiveLocalUris, effectiveCloudUrl) {
        mutableStateOf<List<String>>(emptyList())
    }
    var isLoadingAlbum by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(effectiveSource, effectiveLocalUris, effectiveCloudUrl) {
        isLoadingAlbum = true
        val list = PhotoSlideshowResolver.resolveMediaItems(
            sourceType = effectiveSource,
            localUris = effectiveLocalUris,
            cloudUrl = effectiveCloudUrl
        )
        resolvedItems = list
        currentIndex = 0
        isLoadingAlbum = false
    }

    LaunchedEffect(resolvedItems, currentIndex, effectiveIntervalSec) {
        if (resolvedItems.isEmpty()) {
            currentBitmap = null
            return@LaunchedEffect
        }
        val safeIdx = currentIndex.coerceIn(0, resolvedItems.lastIndex)
        val bmp = PhotoSlideshowResolver.loadBitmap(context, resolvedItems[safeIdx])
        if (bmp != null) {
            currentBitmap = bmp
        }
        if (resolvedItems.size > 1) {
            // Preload next slide in the background so transitions are instant and crisp
            val nextIdx = (safeIdx + 1) % resolvedItems.size
            PhotoSlideshowResolver.loadBitmap(context, resolvedItems[nextIdx])
            delay(effectiveIntervalSec.coerceAtLeast(3) * 1000L)
            currentIndex = nextIdx
        }
    }

    LaunchedEffect(isHeaderOverlayVisible, overlayTouchNonce, isEditMode, resolvedItems.size) {
        if (isHeaderOverlayVisible && !isEditMode && resolvedItems.isNotEmpty()) {
            delay(4500L)
            isHeaderOverlayVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121318))
            .combinedClickable(
                enabled = !isEditMode,
                onClick = {
                    if (resolvedItems.isEmpty()) {
                        showConfigModal = true
                    } else if (!isHeaderOverlayVisible) {
                        isHeaderOverlayVisible = true
                        overlayTouchNonce++
                    } else {
                        currentIndex = (currentIndex + 1) % resolvedItems.size
                        overlayTouchNonce++
                    }
                },
                onLongClick = { onToggleEditMode() }
            )
    ) {
        val bmpToDraw = currentBitmap
        if (bmpToDraw != null) {
            Crossfade(
                targetState = bmpToDraw,
                animationSpec = tween(durationMillis = 800),
                label = "TilePhotoCrossfade"
            ) { targetBmp ->
                Image(
                    bitmap = targetBmp,
                    contentDescription = tile.title,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = GhAccentPurple,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = if (isLoadingAlbum) {
                            "Loading ${effectiveSource.label}..."
                        } else {
                            "Tap to Select Local Folders / Subfolders, Google Photos or OneDrive"
                        },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontFamily = GhFont,
                        textAlign = TextAlign.Center
                    )
                    if (!isEditMode && !isLoadingAlbum) {
                        FilledTonalButton(
                            onClick = { showConfigModal = true },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = GhAccentPurple.copy(alpha = 0.25f),
                                contentColor = GhAccentPurple
                            )
                        ) {
                            Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Choose Folders / Subfolders", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isHeaderOverlayVisible || isEditMode || resolvedItems.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.78f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = GhAccentPurple,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEditMode && !tile.isVisible) "${tile.title} (Hidden)" else tile.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!isEditMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (resolvedItems.size > 1) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.clickable {
                                        currentIndex = (currentIndex + 1) % resolvedItems.size
                                    }
                                ) {
                                    Text(
                                        text = "${currentIndex + 1}/${resolvedItems.size}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Surface(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.clickable { showConfigModal = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Tune,
                                        contentDescription = "Configure Photos",
                                        tint = GhAccentPurple,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Folders",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfigModal && !isEditMode) {
        PhotoSlideshowConfigModal(
            title = tile.title,
            initialSourceType = effectiveSource,
            initialLocalUris = effectiveLocalUris,
            initialCloudUrl = effectiveCloudUrl,
            initialIntervalSeconds = effectiveIntervalSec,
            initialIncludeSubfolders = tile.slideshowIncludeSubfolders,
            initialSelectedFolders = tile.slideshowSelectedFolders,
            onSave = { newSource, newLocalUris, newCloudUrl, newIntervalSec, includeSubfolders, selectedFolders ->
                PhotoSlideshowResolver.clearCloudCache()
                onUpdateTile(
                    tile.copy(
                        slideshowSourceType = newSource,
                        slideshowLocalUris = newLocalUris,
                        slideshowCloudUrl = newCloudUrl,
                        slideshowIntervalSeconds = newIntervalSec,
                        slideshowIncludeSubfolders = includeSubfolders,
                        slideshowSelectedFolders = selectedFolders
                    )
                )
                showConfigModal = false
            },
            onDismiss = { showConfigModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// FULL-SCREEN SWIPABLE DASHBOARD SLIDESHOW PAGE
// ---------------------------------------------------------------------------

@Composable
fun FullScreenPhotoSlideshowPage(
    bridgeSettings: BridgeSettings,
    tiles: List<SmartTile>,
    onUpdateBridgeSettings: (BridgeSettings) -> Unit,
    @Suppress("UNUSED_PARAMETER") onReturnToDashboard: () -> Unit
) {
    val context = LocalContext.current
    var showConfigModal by remember { mutableStateOf(false) }
    var showControlsOverlay by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }

    val fallbackTile = remember(tiles) {
        tiles.firstOrNull {
            it.type == TileType.PHOTO_SLIDESHOW &&
                    (it.slideshowLocalUris.isNotEmpty() || it.slideshowCloudUrl.isNotBlank())
        }
    }

    val activeSource = if (bridgeSettings.slideshowLocalUris.isNotEmpty() || bridgeSettings.slideshowCloudUrl.isNotBlank()) {
        bridgeSettings.slideshowSourceType
    } else {
        fallbackTile?.slideshowSourceType ?: bridgeSettings.slideshowSourceType
    }
    val activeLocalUris = if (bridgeSettings.slideshowLocalUris.isNotEmpty()) {
        bridgeSettings.slideshowLocalUris
    } else {
        fallbackTile?.slideshowLocalUris.orEmpty()
    }
    val activeCloudUrl = if (bridgeSettings.slideshowCloudUrl.isNotBlank()) {
        bridgeSettings.slideshowCloudUrl
    } else {
        fallbackTile?.slideshowCloudUrl.orEmpty()
    }
    val activeIntervalSec = bridgeSettings.slideshowIntervalSeconds.coerceAtLeast(3)

    var resolvedItems by remember(activeSource, activeLocalUris, activeCloudUrl) {
        mutableStateOf<List<String>>(emptyList())
    }
    var isLoadingAlbum by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
    val weather by JarvisLiveBridge.weatherFlow.collectAsState()

    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
        while (true) {
            val now = Date()
            currentTime = timeFmt.format(now)
            currentDate = dateFmt.format(now)
            delay(1000L)
        }
    }

    LaunchedEffect(activeSource, activeLocalUris, activeCloudUrl) {
        isLoadingAlbum = true
        val list = PhotoSlideshowResolver.resolveMediaItems(
            sourceType = activeSource,
            localUris = activeLocalUris,
            cloudUrl = activeCloudUrl
        )
        resolvedItems = list
        currentIndex = 0
        isLoadingAlbum = false
    }

    LaunchedEffect(resolvedItems, currentIndex, activeIntervalSec) {
        if (resolvedItems.isEmpty()) {
            currentBitmap = null
            return@LaunchedEffect
        }
        val safeIdx = currentIndex.coerceIn(0, resolvedItems.lastIndex)
        val bmp = PhotoSlideshowResolver.loadBitmap(context, resolvedItems[safeIdx])
        if (bmp != null) {
            currentBitmap = bmp
        }
        if (resolvedItems.size > 1) {
            val nextIdx = (safeIdx + 1) % resolvedItems.size
            PhotoSlideshowResolver.loadBitmap(context, resolvedItems[nextIdx])
            delay(activeIntervalSec * 1000L)
            currentIndex = nextIdx
        }
    }

    LaunchedEffect(showControlsOverlay, interactionNonce, resolvedItems.size) {
        if (showControlsOverlay && resolvedItems.isNotEmpty()) {
            delay(5000L)
            showControlsOverlay = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0E))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControlsOverlay = !showControlsOverlay
                interactionNonce++
            }
    ) {
        val bmpToDraw = currentBitmap
        if (bmpToDraw != null) {
            Crossfade(
                targetState = bmpToDraw,
                animationSpec = tween(durationMillis = 1000),
                label = "FullScreenSlideshowCrossfade"
            ) { targetBmp ->
                Image(
                    bitmap = targetBmp,
                    contentDescription = "Slideshow Photo",
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = GhTileSurface,
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
                    modifier = Modifier.widthIn(max = 520.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = GhAccentPurple,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (isLoadingAlbum) {
                                "Loading ${activeSource.label}..."
                            } else {
                                "Photo Slideshow"
                            },
                            color = GhTextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = "Browse local folders & subfolders on this tablet, or sign in to Google Photos or Microsoft OneDrive to select your albums and subfolders.",
                            color = GhTextSecondary,
                            fontSize = 13.sp,
                            fontFamily = GhFont,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { showConfigModal = true },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentGreen,
                                contentColor = Color(0xFF0F1114)
                            )
                        ) {
                            Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Select Folders & Subfolders", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (bridgeSettings.showClockOnSlideshowPage) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Column {
                    Text(
                        text = currentTime,
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GhFont,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = "$currentDate \u2022 ${weather.temperatureCelsius}\u00B0C ${weather.conditionText}",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GhFont
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showControlsOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (resolvedItems.size > 1) {
                    FilledTonalIconButton(
                        onClick = {
                            currentIndex = if (currentIndex - 1 >= 0) currentIndex - 1 else resolvedItems.lastIndex
                            interactionNonce++
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.65f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Photo")
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "${currentIndex + 1} / ${resolvedItems.size}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = {
                            currentIndex = (currentIndex + 1) % resolvedItems.size
                            interactionNonce++
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.65f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Photo")
                    }
                }
                FilledTonalButton(
                    onClick = { showConfigModal = true },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.65f),
                        contentColor = GhAccentGreen
                    )
                ) {
                    Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Slideshow Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showConfigModal) {
        PhotoSlideshowConfigModal(
            title = "Photo Slideshow",
            initialSourceType = activeSource,
            initialLocalUris = activeLocalUris,
            initialCloudUrl = activeCloudUrl,
            initialIntervalSeconds = activeIntervalSec,
            initialIncludeSubfolders = true,
            initialSelectedFolders = emptyList(),
            onSave = { newSource, newLocalUris, newCloudUrl, newIntervalSec, _, _ ->
                PhotoSlideshowResolver.clearCloudCache()
                val updated = bridgeSettings.copy(
                    slideshowSourceType = newSource,
                    slideshowLocalUris = newLocalUris,
                    slideshowCloudUrl = newCloudUrl,
                    slideshowIntervalSeconds = newIntervalSec
                )
                DeviceRepository.saveBridge(context, updated)
                onUpdateBridgeSettings(updated)
                showConfigModal = false
            },
            onDismiss = { showConfigModal = false }
        )
    }
}

// ---------------------------------------------------------------------------
// PHOTO SLIDESHOW CONFIGURATION MODAL (WITH SUBFOLDER BROWSER)
// ---------------------------------------------------------------------------

@Composable
fun PhotoSlideshowConfigModal(
    title: String,
    initialSourceType: SlideshowSourceType,
    initialLocalUris: List<String>,
    initialCloudUrl: String,
    initialIntervalSeconds: Int,
    initialIncludeSubfolders: Boolean = true,
    initialSelectedFolders: List<String> = emptyList(),
    onSave: (SlideshowSourceType, List<String>, String, Int, Boolean, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draftSource by remember { mutableStateOf(initialSourceType) }
    val draftLocalUris = remember { mutableStateListOf<String>().apply { addAll(initialLocalUris) } }
    var draftCloudUrl by remember { mutableStateOf(initialCloudUrl) }
    var draftIntervalSec by remember { mutableIntStateOf(initialIntervalSeconds.coerceAtLeast(3)) }
    var draftIncludeSubfolders by remember { mutableStateOf(initialIncludeSubfolders) }
    val draftSelectedFolders = remember { mutableStateListOf<String>().apply { addAll(initialSelectedFolders) } }

    var activeCloudPickerProvider by remember { mutableStateOf<SlideshowSourceType?>(null) }
    var showLocalFolderSubfolderBrowser by remember { mutableStateOf(false) }
    val safDiscoveredFolders = remember { mutableStateListOf<LocalPhotoFolderOption>() }
    var showAdvancedLinkOptions by remember {
        mutableStateOf(initialSourceType == SlideshowSourceType.CUSTOM_URLS)
    }

    val compactInputStyle = TextStyle(fontSize = 13.sp, fontFamily = GhFont, color = GhTextPrimary)

    val intervalOptions = listOf(
        5 to "Every 5 Seconds",
        10 to "Every 10 Seconds (Default)",
        15 to "Every 15 Seconds",
        30 to "Every 30 Seconds",
        60 to "Every 1 Minute",
        300 to "Every 5 Minutes"
    )

    val availableSourceOptions = remember(showAdvancedLinkOptions) {
        SlideshowSourceType.entries.filter {
            it != SlideshowSourceType.CUSTOM_URLS || showAdvancedLinkOptions
        }
    }

    val localPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val uriStr = uri.toString()
                if (uriStr !in draftLocalUris) {
                    draftLocalUris.add(uriStr)
                }
            }
        }
    }

    val safFolderTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            scope.launch {
                val scanned = PhotoSlideshowResolver.scanSafTreeWithSubfolders(context, treeUri)
                if (scanned.isNotEmpty()) {
                    safDiscoveredFolders.clear()
                    safDiscoveredFolders.addAll(scanned)
                    showLocalFolderSubfolderBrowser = true
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = GhAccentPurple,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "$title \u2014 Photo Source, Folders & Subfolders",
                                color = GhTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = GhFont
                            )
                            Text(
                                text = "Browse folders & nested subfolders on this device, Google Photos, or Microsoft OneDrive.",
                                color = GhTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = GhFont
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(color = GhTileSurfaceElevated)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CleanDropdownSelector(
                        label = "1. Photo Source (Local, Google Photos, OneDrive)",
                        selectedText = draftSource.label,
                        options = availableSourceOptions,
                        optionLabel = { it.label },
                        optionSubtitle = { it.subtitle },
                        onOptionSelected = { draftSource = it },
                        textStyle = compactInputStyle,
                        modifier = Modifier.weight(1.2f)
                    )
                    CleanDropdownSelector(
                        label = "2. Slide Change Speed",
                        selectedText = intervalOptions.firstOrNull { it.first == draftIntervalSec }?.second
                            ?: "Every ${draftIntervalSec}s",
                        options = intervalOptions,
                        optionLabel = { it.second },
                        onOptionSelected = { (sec, _) -> draftIntervalSec = sec },
                        textStyle = compactInputStyle,
                        modifier = Modifier.weight(0.8f)
                    )
                }

                Surface(
                    color = GhTileSurfaceElevated.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include Subfolders Automatically",
                                color = GhTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "When you tick a parent folder, automatically include photos from all of its nested subfolders.",
                                color = GhTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = draftIncludeSubfolders,
                            onCheckedChange = { draftIncludeSubfolders = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0F1114),
                                checkedTrackColor = GhAccentGreen
                            )
                        )
                    }
                }

                when (draftSource) {
                    SlideshowSourceType.LOCAL_DEVICE -> {
                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GhAccentPurple.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Selected Local Photos (${draftLocalUris.size} photos across ${draftSelectedFolders.size.coerceAtLeast(if (draftLocalUris.isNotEmpty()) 1 else 0)} folder/subfolders)",
                                    color = GhTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Browse all folders & subfolders on your device to tick specific subfolders, or pick individual photos.",
                                    color = GhTextSecondary,
                                    fontSize = 11.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { showLocalFolderSubfolderBrowser = true },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF0F1114)
                                        )
                                    ) {
                                        Icon(Icons.Outlined.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Browse Folders & Subfolders", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    FilledTonalButton(
                                        onClick = { safFolderTreeLauncher.launch(null) },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = GhTileSurface,
                                            contentColor = GhAccentBlue
                                        )
                                    ) {
                                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Scan Custom Folder Tree", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    FilledTonalButton(
                                        onClick = { localPhotoPickerLauncher.launch(arrayOf("image/*")) },
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Pick Individual Photos", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (draftLocalUris.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = {
                                                draftLocalUris.clear()
                                                draftSelectedFolders.clear()
                                            },
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text("Clear (${draftLocalUris.size})", fontSize = 11.sp, color = GhAccentRed)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SlideshowSourceType.GOOGLE_PHOTOS -> {
                        val importedCount = draftCloudUrl.split(",", "\n").count { it.trim().startsWith("http") }
                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (importedCount > 0) {
                                                "Google Photos Connected ($importedCount photos/albums linked) \u2713"
                                            } else {
                                                "Sign In to Google Photos & Tick Albums / Subfolders"
                                            },
                                            color = if (importedCount > 0) GhAccentGreen else GhTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Log in with your Google account to browse your albums and nested shared folders.",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            activeCloudPickerProvider = SlideshowSourceType.GOOGLE_PHOTOS
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF0F1114)
                                        )
                                    ) {
                                        Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Sign In & Select Albums", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    SlideshowSourceType.ONEDRIVE -> {
                        val importedCount = draftCloudUrl.split(",", "\n").count { it.trim().startsWith("http") }
                        Surface(
                            color = GhTileSurfaceElevated.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GhAccentBlue.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (importedCount > 0) {
                                                "Microsoft OneDrive Connected ($importedCount high-res photos linked) \u2713"
                                            } else {
                                                "Sign In to Microsoft OneDrive & Browse Subfolders"
                                            },
                                            color = if (importedCount > 0) GhAccentGreen else GhTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Click any folder name to open its subfolders, and tick the checkbox on the folders you want to include.",
                                            color = GhTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            activeCloudPickerProvider = SlideshowSourceType.ONEDRIVE
                                        },
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GhAccentGreen,
                                            contentColor = Color(0xFF0F1114)
                                        )
                                    ) {
                                        Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Sign In & Browse Subfolders", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    SlideshowSourceType.CUSTOM_URLS -> {}
                }

                if (draftSource != SlideshowSourceType.LOCAL_DEVICE) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedLinkOptions = !showAdvancedLinkOptions },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showAdvancedLinkOptions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = GhTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showAdvancedLinkOptions) "Hide Advanced Link Options" else "Advanced Link Options (Optional)",
                            color = GhTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = GhFont
                        )
                    }

                    AnimatedVisibility(visible = showAdvancedLinkOptions) {
                        OutlinedTextField(
                            value = draftCloudUrl,
                            onValueChange = { draftCloudUrl = it },
                            label = {
                                Text(
                                    when (draftSource) {
                                        SlideshowSourceType.GOOGLE_PHOTOS -> "Google Photos Shared Album Link (Optional)"
                                        SlideshowSourceType.ONEDRIVE -> "OneDrive Shared Folder / Subfolder Link (Optional)"
                                        else -> "Direct Image Links (Comma or line separated)"
                                    },
                                    fontSize = 11.sp
                                )
                            },
                            textStyle = compactInputStyle,
                            shape = RoundedCornerShape(14.dp),
                            colors = claraFieldColors(),
                            maxLines = if (draftSource == SlideshowSourceType.CUSTOM_URLS) 4 else 1,
                            singleLine = draftSource != SlideshowSourceType.CUSTOM_URLS,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = GhTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                draftSource,
                                draftLocalUris.toList(),
                                draftCloudUrl.trim(),
                                draftIntervalSec,
                                draftIncludeSubfolders,
                                draftSelectedFolders.toList()
                            )
                        },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GhAccentGreen,
                            contentColor = Color(0xFF0F1114)
                        )
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Slideshow", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showLocalFolderSubfolderBrowser) {
        LocalFolderAndSubfolderPickerModal(
            includeSubfoldersByDefault = draftIncludeSubfolders,
            extraSafFolders = safDiscoveredFolders,
            initiallySelectedPaths = draftSelectedFolders.toSet(),
            onFoldersConfirmed = { selectedFolderPaths, collectedUris, includeSubs ->
                draftIncludeSubfolders = includeSubs
                draftSelectedFolders.clear()
                draftSelectedFolders.addAll(selectedFolderPaths)
                draftLocalUris.clear()
                draftLocalUris.addAll(collectedUris)
                showLocalFolderSubfolderBrowser = false
            },
            onDismiss = { showLocalFolderSubfolderBrowser = false }
        )
    }

    activeCloudPickerProvider?.let { providerToPick ->
        CloudPhotosAlbumPickerModal(
            provider = providerToPick,
            includeSubfolders = draftIncludeSubfolders,
            onPhotosExtracted = { extractedUrls ->
                if (extractedUrls.isNotEmpty()) {
                    draftCloudUrl = extractedUrls.joinToString(",")
                }
                activeCloudPickerProvider = null
            },
            onDismiss = { activeCloudPickerProvider = null }
        )
    }
}

// ---------------------------------------------------------------------------
// LOCAL FOLDER & NESTED SUBFOLDER TREE CHECKLIST MODAL
// ---------------------------------------------------------------------------

@Composable
fun LocalFolderAndSubfolderPickerModal(
    includeSubfoldersByDefault: Boolean,
    extraSafFolders: List<LocalPhotoFolderOption> = emptyList(),
    initiallySelectedPaths: Set<String> = emptySet(),
    onFoldersConfirmed: (List<String>, List<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isScanning by remember { mutableStateOf(true) }
    var includeSubfolders by remember { mutableStateOf(includeSubfoldersByDefault) }
    val allFolders = remember { mutableStateListOf<LocalPhotoFolderOption>() }
    var selectedPaths by remember { mutableStateOf(initiallySelectedPaths) }

    LaunchedEffect(extraSafFolders.size) {
        isScanning = true
        val mediaStoreFolders = PhotoSlideshowResolver.scanLocalDevicePhotoFolders(context)
        allFolders.clear()
        allFolders.addAll(extraSafFolders)
        mediaStoreFolders.forEach { mf ->
            if (allFolders.none { it.folderPath == mf.folderPath }) {
                allFolders.add(mf)
            }
        }
        if (selectedPaths.isEmpty() && allFolders.isNotEmpty()) {
            selectedPaths = setOf(allFolders.first().folderPath)
        }
        isScanning = false
    }

    fun toggleFolderSelection(folder: LocalPhotoFolderOption, checked: Boolean) {
        val next = selectedPaths.toMutableSet()
        if (checked) {
            next.add(folder.folderPath)
            if (includeSubfolders) {
                allFolders.filter { it.folderPath.startsWith("${folder.folderPath}/") }
                    .forEach { sub -> next.add(sub.folderPath) }
            }
        } else {
            next.remove(folder.folderPath)
            if (includeSubfolders) {
                allFolders.filter { it.folderPath.startsWith("${folder.folderPath}/") }
                    .forEach { sub -> next.remove(sub.folderPath) }
            }
        }
        selectedPaths = next
    }

    val totalSelectedPhotosCount = remember(selectedPaths, allFolders.size) {
        allFolders.filter { it.folderPath in selectedPaths }
            .flatMap { it.photoUris }
            .distinct()
            .size
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.86f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select Photo Folders & Subfolders",
                            color = GhTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Subfolders are indented (\u21B3) underneath their parent folder so you can tick parent folders or individual subfolders.",
                            color = GhTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val chosenPhotos = allFolders
                                    .filter { it.folderPath in selectedPaths }
                                    .flatMap { it.photoUris }
                                    .distinct()
                                onFoldersConfirmed(
                                    selectedPaths.toList(),
                                    chosenPhotos,
                                    includeSubfolders
                                )
                            },
                            enabled = selectedPaths.isNotEmpty(),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GhAccentGreen,
                                contentColor = Color(0xFF0F1114)
                            )
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Use Selected ($totalSelectedPhotosCount photos)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = includeSubfolders,
                            onCheckedChange = { includeSubfolders = it }
                        )
                        Text(
                            text = "Checking a parent folder automatically selects all its nested subfolders",
                            color = GhTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    TextButton(
                        onClick = {
                            selectedPaths = if (selectedPaths.size == allFolders.size) {
                                emptySet()
                            } else {
                                allFolders.map { it.folderPath }.toSet()
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedPaths.size == allFolders.size) "Unselect All" else "Select All Folders & Subfolders",
                            color = GhAccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(color = GhTileSurfaceElevated)

                if (isScanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = GhAccentGreen)
                    }
                } else if (allFolders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No local photo folders found on this device yet.",
                            color = GhTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(allFolders, key = { it.folderPath }) { folder ->
                            val isChecked = folder.folderPath in selectedPaths
                            val isSubfolder = folder.depth > 0
                            val childSubfolderCount = allFolders.count {
                                it.parentPath == folder.folderPath
                            }

                            Surface(
                                color = if (isChecked) GhTileGreenActive else GhTileSurfaceElevated.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isChecked) GhAccentGreen.copy(alpha = 0.65f) else GhTileSurfaceElevated
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = (folder.depth * 22).coerceAtMost(88).dp)
                                    .clickable {
                                        toggleFolderSelection(folder, !isChecked)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                toggleFolderSelection(folder, checked)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = if (isSubfolder) Icons.Outlined.SubdirectoryArrowRight else Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = if (isChecked) GhAccentGreen else if (isSubfolder) GhAccentBlue else GhTextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = folder.displayName,
                                                    color = GhTextPrimary,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Surface(
                                                    color = if (isSubfolder) GhAccentBlue.copy(alpha = 0.18f) else GhAccentPurple.copy(alpha = 0.18f),
                                                    shape = RoundedCornerShape(50)
                                                ) {
                                                    Text(
                                                        text = if (isSubfolder) "Subfolder" else "Folder",
                                                        color = if (isSubfolder) GhAccentBlue else GhAccentPurple,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = buildString {
                                                    append("Path: ${folder.folderPath} \u2022 ${folder.photoUris.size} direct photo(s)")
                                                    if (childSubfolderCount > 0) {
                                                        append(" \u2022 $childSubfolderCount subfolder(s)")
                                                    }
                                                },
                                                color = GhTextSecondary,
                                                fontSize = 11.sp
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

// ---------------------------------------------------------------------------
// INTERACTIVE GOOGLE PHOTOS & ONEDRIVE OAUTH LOGIN + SUBFOLDER DRILL-DOWN PICKER
// - Hooks OneDrive's internal JSON API (`fetch` & `XMLHttpRequest`) + Fluent-UI
//   DOM rows so subfolders appear fast and reliably.
// - Separates Checkbox Ticking (left checkbox) from Subfolder Drill-Down
//   (clicking the folder row or "Open Subfolders ->" button).
// - Extracts full-resolution `@content.downloadUrl` / `=w2560-h1600` images.
// ---------------------------------------------------------------------------

internal class CloudPhotosJsBridge(
    private val onAlbumsDiscovered: (String) -> Unit,
    private val onPhotosExtracted: (String) -> Unit
) {
    @JavascriptInterface
    fun postAlbumsList(jsonArrayStr: String) {
        onAlbumsDiscovered(jsonArrayStr)
    }

    @JavascriptInterface
    fun postAlbumPhotos(jsonArrayStr: String) {
        onPhotosExtracted(jsonArrayStr)
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun CloudPhotosAlbumPickerModal(
    provider: SlideshowSourceType,
    includeSubfolders: Boolean = true,
    onPhotosExtracted: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isGooglePhotos = provider == SlideshowSourceType.GOOGLE_PHOTOS
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.removeJavascriptInterface("ClaraPhotoBridge")
                (webViewRef?.parent as? ViewGroup)?.removeView(webViewRef)
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    val startUrl = remember(provider) {
        if (isGooglePhotos) {
            "https://photos.google.com/albums"
        } else {
            "https://onedrive.live.com/?view=0"
        }
    }

    val folderBreadcrumbStack = remember { mutableStateListOf<Pair<String, String>>() }
    var isSignedInAndScanning by remember { mutableStateOf(false) }
    var isExtractingSelectedAlbums by remember { mutableStateOf(false) }
    var showRawWebViewBrowser by remember { mutableStateOf(false) }
    var statusHeadline by remember {
        mutableStateOf("Sign in to ${provider.label} below")
    }
    var statusSubtext by remember {
        mutableStateOf("Once signed in, Clara will list your folders, subfolders & albums.")
    }

    val discoveredAlbums = remember { mutableStateListOf<CloudPhotoAlbumOption>() }
    var selectedAlbumUrls by remember { mutableStateOf(setOf<String>()) }
    val collectedPhotoUrls = remember { LinkedHashSet<String>() }

    // Injects an API + SPA hook into OneDrive so whenever OneDrive's React app fetches
    // folder children JSON (`/children` or `RenderListDataAsStream`), we capture all
    // subfolders and high-res `@content.downloadUrl` photos immediately without waiting!
    val oneDriveApiInterceptorJs = remember {
        """
        (function() {
            if (window.__claraOneDriveHookInstalled) return;
            window.__claraOneDriveHookInstalled = true;
            window.__claraApiFolders = window.__claraApiFolders || [];
            window.__claraApiPhotosByFolder = window.__claraApiPhotosByFolder || {};
            window.__claraCurrentFolderHighResPhotos = window.__claraCurrentFolderHighResPhotos || [];

            const processOneDriveItemsArray = function(items) {
                if (!Array.isArray(items)) return;
                const foundFolders = [];
                const foundPhotos = [];
                for (const item of items) {
                    if (!item || typeof item !== 'object') continue;
                    const name = item.name || item.FileLeafRef || '';
                    if (!name) continue;
                    const isFolder = Boolean(
                        item.folder ||
                        item.package ||
                        item.FSObjType === '1' ||
                        item.FSObjType === 1 ||
                        (item.contentType && String(item.contentType).toLowerCase().includes('folder'))
                    );
                    if (isFolder) {
                        const childCount = (item.folder && typeof item.folder.childCount === 'number')
                            ? item.folder.childCount
                            : -1;
                        const webUrl = item.webUrl || '';
                        const id = item.id || webUrl || ('dom_click:' + name);
                        foundFolders.push({
                            id: id,
                            title: String(name).trim(),
                            subtitle: childCount >= 0 ? ('OneDrive Folder \u2022 ' + childCount + ' items') : 'OneDrive Folder / Subfolder',
                            albumUrl: webUrl && webUrl.startsWith('http') ? webUrl : ('dom_click:' + String(name).trim()),
                            coverUrl: '',
                            isFolder: true
                        });
                    } else {
                        const mime = (item.file && item.file.mimeType) ? String(item.file.mimeType).toLowerCase() : '';
                        const isImage = Boolean(
                            item.image ||
                            item.photo ||
                            mime.startsWith('image/') ||
                            /\.(jpg|jpeg|png|webp|heic|bmp)$/i.test(String(name))
                        );
                        if (isImage) {
                            let bestUrl = item['@content.downloadUrl'] || item['@microsoft.graph.downloadUrl'] || '';
                            if (!bestUrl && Array.isArray(item.thumbnails) && item.thumbnails.length > 0) {
                                const t0 = item.thumbnails[0];
                                bestUrl = (t0.large && t0.large.url) || (t0.c1920x1080 && t0.c1920x1080.url) || (t0.medium && t0.medium.url) || '';
                            }
                            if (bestUrl && bestUrl.startsWith('http')) {
                                foundPhotos.push(bestUrl);
                            }
                        }
                    }
                }
                if (foundFolders.length > 0) {
                    window.__claraApiFolders = foundFolders;
                }
                if (foundPhotos.length > 0) {
                    for (const p of foundPhotos) {
                        if (!window.__claraCurrentFolderHighResPhotos.includes(p)) {
                            window.__claraCurrentFolderHighResPhotos.push(p);
                        }
                    }
                }
            };

            const inspectJsonPayload = function(json) {
                if (!json || typeof json !== 'object') return;
                if (Array.isArray(json.value)) {
                    processOneDriveItemsArray(json.value);
                } else if (Array.isArray(json.items)) {
                    processOneDriveItemsArray(json.items);
                } else if (json.ListData && Array.isArray(json.ListData.Row)) {
                    processOneDriveItemsArray(json.ListData.Row);
                }
            };

            const origFetch = window.fetch;
            window.fetch = async function(...args) {
                const resp = await origFetch.apply(this, args);
                try {
                    const url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                    if (url.includes('children') || url.includes('items') || url.includes('RenderListDataAsStream') || url.includes('files')) {
                        resp.clone().json().then(inspectJsonPayload).catch(() => {});
                    }
                } catch (e) {}
                return resp;
            };

            const origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__claraUrl = String(url || '');
                this.addEventListener('load', function() {
                    try {
                        if (this.__claraUrl.includes('children') || this.__claraUrl.includes('items') || this.__claraUrl.includes('RenderListDataAsStream')) {
                            const j = JSON.parse(this.responseText);
                            inspectJsonPayload(j);
                        }
                    } catch (e) {}
                });
                return origOpen.apply(this, arguments);
            };
        })();
        """.trimIndent()
    }

    fun scanAlbumsAndSubfoldersFromDom(wv: WebView?) {
        if (wv == null) return
        if (isGooglePhotos) {
            wv.evaluateJavascript(
                """
                (async function() {
                    const sleep = ms => new Promise(r => setTimeout(r, ms));
                    for (let s = 0; s < 3; s++) {
                        window.scrollBy(0, 600);
                        await sleep(160);
                    }
                    window.scrollTo(0, 0);
                    const results = [];
                    const seen = new Set();
                    results.push({
                        id: 'all_photos',
                        title: 'All Recent Photos (Main Library Stream)',
                        subtitle: 'Your main Google Photos library',
                        albumUrl: 'https://photos.google.com/',
                        coverUrl: '',
                        isFolder: false
                    });
                    const links = Array.from(document.querySelectorAll('a[href*="/album/"], a[href*="/share/"]'));
                    for (const a of links) {
                        const href = a.href || '';
                        if (!href || seen.has(href)) continue;
                        seen.add(href);
                        const lines = (a.innerText || a.getAttribute('aria-label') || '')
                            .split('\n')
                            .map(x => x.trim())
                            .filter(Boolean);
                        const title = lines[0] || ('Google Photos Album ' + results.length);
                        const subtitle = lines.slice(1).join(' \u2022 ') || 'Google Photos Album';
                        const img = a.querySelector('img');
                        let cover = (img && img.src) ? img.src : '';
                        results.push({
                            id: href,
                            title: title,
                            subtitle: subtitle,
                            albumUrl: href,
                            coverUrl: cover,
                            isFolder: false
                        });
                    }
                    window.ClaraPhotoBridge.postAlbumsList(JSON.stringify(results));
                })();
                """.trimIndent(),
                null
            )
        } else {
            wv.evaluateJavascript(
                """
                (async function() {
                    const sleep = ms => new Promise(r => setTimeout(r, ms));
                    // Wait briefly if OneDrive's Fluent UI grid is still mounting
                    for (let wait = 0; wait < 8; wait++) {
                        const hasRows = document.querySelectorAll('[data-automationid="DetailsRow"], [data-automationid="FieldRenderer-name"], [role="row"], [role="gridcell"], a[href*="id="]').length > 0;
                        if (hasRows || (window.__claraApiFolders && window.__claraApiFolders.length > 0)) break;
                        await sleep(220);
                    }

                    const results = [];
                    const seenTitles = new Set();
                    const currentPhotoCount = (window.__claraCurrentFolderHighResPhotos || []).length;

                    results.push({
                        id: 'onedrive_current_folder::' + window.location.href,
                        title: 'Photos in This Folder' + (currentPhotoCount > 0 ? (' (' + currentPhotoCount + ' high-res photos ready)') : ''),
                        subtitle: 'Tick the checkbox on the left to include photos directly inside this folder',
                        albumUrl: window.location.href,
                        coverUrl: '',
                        isFolder: false
                    });

                    // 1. Add folders captured directly from OneDrive's JSON API
                    if (Array.isArray(window.__claraApiFolders)) {
                        for (const f of window.__claraApiFolders) {
                            if (!f || !f.title) continue;
                            const cleanT = String(f.title).trim();
                            const key = cleanT.toLowerCase();
                            if (!cleanT || seenTitles.has(key)) continue;
                            seenTitles.add(key);
                            results.push({
                                id: f.id || ('dom_click:' + cleanT),
                                title: cleanT,
                                subtitle: f.subtitle || 'Subfolder \u2022 Tap row to open subfolders, or tick checkbox to select',
                                albumUrl: f.albumUrl || ('dom_click:' + cleanT),
                                coverUrl: f.coverUrl || '',
                                isFolder: true
                            });
                        }
                    }

                    // 2. Also scrape Fluent-UI DOM rows/tiles (works even when OneDrive uses <div>/<button> without href)
                    const rowSelectors = [
                        '[data-automationid="DetailsRow"]',
                        '[data-automationid="list-content-row"]',
                        '[role="row"]',
                        '[role="listitem"]',
                        '[data-automationid="Tile"]',
                        'a[href*="id="]'
                    ].join(', ');

                    const domNodes = Array.from(document.querySelectorAll(rowSelectors));
                    for (const node of domNodes) {
                        const nameEl = node.querySelector('[data-automationid="FieldRenderer-name"], [data-automationid="name"], button, a') || node;
                        const rawName = (nameEl.innerText || nameEl.getAttribute('aria-label') || node.getAttribute('aria-label') || '')
                            .split('\n')[0]
                            .trim();
                        if (!rawName || rawName.length > 100) continue;
                        const lower = rawName.toLowerCase();
                        if (seenTitles.has(lower)) continue;
                        if (['name', 'modified', 'file size', 'sharing', 'my files', 'home', 'photos', 'shared', 'recycle bin'].includes(lower)) continue;
                        // Skip individual image/video files when building the folder/subfolder list
                        if (/\.(jpg|jpeg|png|webp|heic|gif|bmp|mp4|mov|avi|pdf|docx|xlsx|zip)$/i.test(rawName)) continue;

                        const linkEl = node.tagName === 'A' ? node : node.querySelector('a[href]');
                        const href = (linkEl && linkEl.href && linkEl.href.startsWith('http')) ? linkEl.href : ('dom_click:' + rawName);

                        seenTitles.add(lower);
                        results.push({
                            id: href + '::' + rawName,
                            title: rawName,
                            subtitle: 'Subfolder \u2022 Tap row to open subfolders, or tick checkbox to select',
                            albumUrl: href,
                            coverUrl: '',
                            isFolder: true
                        });
                    }

                    window.ClaraPhotoBridge.postAlbumsList(JSON.stringify(results));
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun openSubfolderInWebView(wv: WebView?, folderOption: CloudPhotoAlbumOption) {
        if (wv == null) return
        val curUrl = wv.url ?: startUrl
        folderBreadcrumbStack.add(folderOption.title to curUrl)
        discoveredAlbums.clear()
        isSignedInAndScanning = true
        statusHeadline = "Opening '${folderOption.title}'..."
        statusSubtext = "Loading nested subfolders and high-resolution photos..."

        val target = folderOption.albumUrl
        val escapedTitle = folderOption.title.replace("\\", "\\\\").replace("'", "\\'")

        if (target.startsWith("http")) {
            wv.evaluateJavascript(
                "window.__claraApiFolders = []; window.__claraCurrentFolderHighResPhotos = [];",
                null
            )
            wv.loadUrl(target)
            scope.launch {
                delay(1400L)
                scanAlbumsAndSubfoldersFromDom(wv)
            }
        } else {
            // Click the Fluent-UI folder row directly inside OneDrive's SPA and re-scan
            wv.evaluateJavascript(
                """
                (async function() {
                    const sleep = ms => new Promise(r => setTimeout(r, ms));
                    window.__claraApiFolders = [];
                    window.__claraCurrentFolderHighResPhotos = [];
                    const targetName = '$escapedTitle'.toLowerCase();
                    const candidates = Array.from(document.querySelectorAll(
                        '[data-automationid="FieldRenderer-name"], [data-automationid="name"], [role="row"] a, [role="row"] button, [role="gridcell"] button, [role="listitem"] a, a, button, span'
                    ));
                    const match = candidates.find(el => (el.innerText || el.textContent || '').trim().toLowerCase() === targetName);
                    if (match) {
                        match.click();
                        try {
                            const row = match.closest('[data-automationid="DetailsRow"], [role="row"], [role="listitem"]');
                            if (row) {
                                const dblEvt = new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window });
                                row.dispatchEvent(dblEvt);
                            }
                        } catch (e) {}
                    }
                })();
                """.trimIndent(),
                null
            )
            scope.launch {
                delay(1200L)
                scanAlbumsAndSubfoldersFromDom(wv)
            }
        }
    }

    fun scrapePhotosAndSubfolderLinksOnCurrentPage(wv: WebView?) {
        if (wv == null) return
        wv.evaluateJavascript(
            """
            (async function() {
                const sleep = ms => new Promise(r => setTimeout(r, ms));
                const urls = new Set(window.__claraCurrentFolderHighResPhotos || []);

                const upgradeUrl = function(raw) {
                    if (!raw || !raw.startsWith('https://')) return '';
                    if (raw.includes('lh3.googleusercontent.com') && !raw.includes('/a/') && !raw.includes('/a-/')) {
                        return raw.split('=')[0] + '=w2560-h1600';
                    }
                    if (raw.includes('1drv.com') || raw.includes('onedrive.com') || raw.includes('live.com') || raw.includes('sharepoint.com') || raw.includes('microsoftpersonalcontent.com')) {
                        return raw
                            .replace(/([?&])width=\d+/gi, '$1width=2560')
                            .replace(/([?&])height=\d+/gi, '$1height=1600')
                            .replace(/\/c\d+x\d+\//gi, '/c2560x1600/');
                    }
                    return '';
                };

                const collectVisible = function() {
                    for (const apiUrl of (window.__claraCurrentFolderHighResPhotos || [])) {
                        if (apiUrl && apiUrl.startsWith('http')) urls.add(apiUrl);
                    }
                    document.querySelectorAll('img').forEach(img => {
                        const src = img.currentSrc || img.src || '';
                        if ((img.width > 90 || img.naturalWidth > 90) && !src.includes('avatar') && !src.includes('icon')) {
                            const up = upgradeUrl(src);
                            if (up) urls.add(up);
                        }
                    });
                    document.querySelectorAll('[style*="background-image"]').forEach(el => {
                        const st = (el.style && el.style.backgroundImage) || '';
                        const m = st.match(/url\(["']?(https:\/\/[^"')]+)["']?\)/);
                        if (m && m[1]) {
                            const up = upgradeUrl(m[1]);
                            if (up) urls.add(up);
                        }
                    });
                };

                for (let step = 0; step < 5; step++) {
                    collectVisible();
                    window.scrollBy(0, 800);
                    await sleep(220);
                }
                collectVisible();
                window.ClaraPhotoBridge.postAlbumPhotos(JSON.stringify(Array.from(urls)));
            })();
            """.trimIndent(),
            null
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = GhTileSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, GhTileSurfaceElevated),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.90f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (discoveredAlbums.isNotEmpty() && !showRawWebViewBrowser) {
                                "Select Your ${provider.label} Folders & Subfolders"
                            } else {
                                "Connect ${provider.label}"
                            },
                            color = GhTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GhFont
                        )
                        Text(
                            text = statusHeadline,
                            color = GhAccentGreen,
                            fontSize = 11.sp,
                            fontFamily = GhFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (folderBreadcrumbStack.isNotEmpty() && !isExtractingSelectedAlbums) {
                            FilledTonalButton(
                                onClick = {
                                    val prev = folderBreadcrumbStack.removeAt(folderBreadcrumbStack.lastIndex)
                                    discoveredAlbums.clear()
                                    isSignedInAndScanning = true
                                    statusHeadline = "Returning to ${prev.first}..."
                                    webViewRef?.evaluateJavascript(
                                        "window.__claraApiFolders = []; window.__claraCurrentFolderHighResPhotos = [];",
                                        null
                                    )
                                    if (webViewRef?.canGoBack() == true) {
                                        webViewRef?.goBack()
                                    } else {
                                        webViewRef?.loadUrl(prev.second)
                                    }
                                    scope.launch {
                                        delay(1200L)
                                        scanAlbumsAndSubfoldersFromDom(webViewRef)
                                    }
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Up Folder", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!isExtractingSelectedAlbums) {
                            FilledTonalButton(
                                onClick = {
                                    if (showRawWebViewBrowser) {
                                        showRawWebViewBrowser = false
                                        isSignedInAndScanning = true
                                        scanAlbumsAndSubfoldersFromDom(webViewRef)
                                    } else {
                                        showRawWebViewBrowser = true
                                    }
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(
                                    imageVector = if (showRawWebViewBrowser) Icons.Outlined.Checklist else Icons.Outlined.Web,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (showRawWebViewBrowser) "Scan Current Folder" else "Web Browser View",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if ((discoveredAlbums.isNotEmpty() || showRawWebViewBrowser) && !isExtractingSelectedAlbums) {
                            Button(
                                onClick = {
                                    isExtractingSelectedAlbums = true
                                    showRawWebViewBrowser = false
                                    val chosen = selectedAlbumUrls.toList()
                                    statusHeadline = "Importing high-resolution photos for your slideshow..."
                                    statusSubtext = "Collecting full-resolution photo streams..."

                                    scope.launch {
                                        if (chosen.isEmpty()) {
                                            scrapePhotosAndSubfolderLinksOnCurrentPage(webViewRef)
                                            delay(1600L)
                                        } else {
                                            for ((idx, albumUrl) in chosen.withIndex()) {
                                                statusHeadline = "Scanning selection ${idx + 1} of ${chosen.size}..."
                                                val cleanTarget = albumUrl.substringAfter("onedrive_current_folder::")
                                                if (cleanTarget.startsWith("http") && cleanTarget != webViewRef?.url) {
                                                    webViewRef?.loadUrl(cleanTarget)
                                                    delay(1800L)
                                                } else if (cleanTarget.startsWith("dom_click:")) {
                                                    val folderName = cleanTarget.removePrefix("dom_click:").replace("'", "\\'")
                                                    webViewRef?.evaluateJavascript(
                                                        """
                                                        (function() {
                                                            const candidates = Array.from(document.querySelectorAll('[data-automationid="FieldRenderer-name"], [role="row"] a, [role="row"] button, a, button'));
                                                            const m = candidates.find(el => (el.innerText || '').trim().toLowerCase() === '$folderName'.toLowerCase());
                                                            if (m) m.click();
                                                        })();
                                                        """.trimIndent(),
                                                        null
                                                    )
                                                    delay(1600L)
                                                }
                                                scrapePhotosAndSubfolderLinksOnCurrentPage(webViewRef)
                                                delay(1500L)
                                            }
                                        }
                                        val finalList = if (collectedPhotoUrls.isNotEmpty()) {
                                            collectedPhotoUrls.map { PhotoSlideshowResolver.upgradeCloudImageUrlToHighRes(it) }
                                        } else {
                                            chosen.filter { it.startsWith("http") }
                                        }
                                        CookieManager.getInstance().flush()
                                        onPhotosExtracted(finalList)
                                    }
                                },
                                enabled = selectedAlbumUrls.isNotEmpty() || showRawWebViewBrowser,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GhAccentGreen,
                                    contentColor = Color(0xFF0F1114)
                                )
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (selectedAlbumUrls.isNotEmpty()) "Use Selected (${selectedAlbumUrls.size})" else "Use Current Folder",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        FilledTonalIconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
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
                                .alpha(
                                    if (!showRawWebViewBrowser && (isSignedInAndScanning || discoveredAlbums.isNotEmpty() || isExtractingSelectedAlbums)) 0f else 1f
                                ),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        userAgentString =
                                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                    }

                                    addJavascriptInterface(
                                        CloudPhotosJsBridge(
                                            onAlbumsDiscovered = { rawJson ->
                                                scope.launch(Dispatchers.Main) {
                                                    try {
                                                        val arr = JSONArray(rawJson)
                                                        discoveredAlbums.clear()
                                                        for (i in 0 until arr.length()) {
                                                            val o = arr.optJSONObject(i) ?: continue
                                                            val title = o.optString("title", "").trim()
                                                            val url = o.optString("albumUrl", "").trim()
                                                            if (title.isNotBlank() && url.isNotBlank()) {
                                                                discoveredAlbums.add(
                                                                    CloudPhotoAlbumOption(
                                                                        id = o.optString("id", url),
                                                                        title = title,
                                                                        subtitle = o.optString("subtitle", ""),
                                                                        albumUrl = url,
                                                                        coverUrl = o.optString("coverUrl", ""),
                                                                        isFolder = o.optBoolean("isFolder", false)
                                                                    )
                                                                )
                                                            }
                                                        }
                                                        isSignedInAndScanning = false
                                                        statusHeadline = "Tap any folder name to open its subfolders, or tick the checkbox on the left to select it."
                                                    } catch (_: Exception) {
                                                        isSignedInAndScanning = false
                                                    }
                                                }
                                            },
                                            onPhotosExtracted = { rawJson ->
                                                scope.launch(Dispatchers.Main) {
                                                    try {
                                                        val arr = JSONArray(rawJson)
                                                        for (i in 0 until arr.length()) {
                                                            val u = arr.optString(i, "").trim()
                                                            if (u.startsWith("http")) {
                                                                collectedPhotoUrls.add(
                                                                    PhotoSlideshowResolver.upgradeCloudImageUrlToHighRes(u)
                                                                )
                                                            }
                                                        }
                                                    } catch (_: Exception) {
                                                    }
                                                }
                                            }
                                        ),
                                        "ClaraPhotoBridge"
                                    )

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            if (!isGooglePhotos) {
                                                view?.evaluateJavascript(oneDriveApiInterceptorJs, null)
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val currentUrl = url ?: return
                                            CookieManager.getInstance().flush()
                                            if (!isGooglePhotos) {
                                                view?.evaluateJavascript(oneDriveApiInterceptorJs, null)
                                            }

                                            val isLoginScreen = currentUrl.contains("accounts.google.com") ||
                                                    currentUrl.contains("login.live.com") ||
                                                    currentUrl.contains("login.microsoftonline.com")

                                            if (isLoginScreen) {
                                                isSignedInAndScanning = false
                                                statusHeadline = "Sign in to your account below"
                                                return
                                            }

                                            if (!isExtractingSelectedAlbums && !showRawWebViewBrowser && discoveredAlbums.isEmpty() &&
                                                (currentUrl.contains("photos.google.com") || currentUrl.contains("onedrive.live.com"))
                                            ) {
                                                isSignedInAndScanning = true
                                                statusHeadline = "Scanning your ${provider.label} folders & subfolders..."
                                                statusSubtext = "Loading your folder and subfolder list..."
                                                scope.launch {
                                                    delay(1300L)
                                                    scanAlbumsAndSubfoldersFromDom(view)
                                                }
                                            }
                                        }
                                    }

                                    loadUrl(startUrl)
                                    webViewRef = this
                                }
                            }
                        )

                        if (!showRawWebViewBrowser && discoveredAlbums.isNotEmpty() && !isExtractingSelectedAlbums) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(GhTileSurface)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val breadcrumbText = if (folderBreadcrumbStack.isEmpty()) {
                                        "Root Folders & Albums (${discoveredAlbums.size})"
                                    } else {
                                        "Path: " + folderBreadcrumbStack.joinToString(" / ") { it.first }
                                    }
                                    Text(
                                        text = breadcrumbText,
                                        color = GhTextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                isSignedInAndScanning = true
                                                scanAlbumsAndSubfoldersFromDom(webViewRef)
                                            }
                                        ) {
                                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp), tint = GhAccentGreen)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Rescan", color = GhAccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(
                                            onClick = {
                                                selectedAlbumUrls = if (selectedAlbumUrls.size == discoveredAlbums.size) {
                                                    emptySet()
                                                } else {
                                                    discoveredAlbums.map { it.albumUrl }.toSet()
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = if (selectedAlbumUrls.size == discoveredAlbums.size) "Unselect All" else "Select All",
                                                color = GhAccentBlue,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(discoveredAlbums, key = { it.id }) { album ->
                                        val isChecked = album.albumUrl in selectedAlbumUrls
                                        val canDrillIntoSubfolder = album.isFolder && !album.id.startsWith("onedrive_current_folder")

                                        Surface(
                                            color = if (isChecked) GhTileGreenActive else GhTileSurfaceElevated.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isChecked) GhAccentGreen else GhTileSurfaceElevated
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    // Clicking a folder row opens its subfolders; clicking a photo-set row toggles selection
                                                    if (canDrillIntoSubfolder) {
                                                        openSubfolderInWebView(webViewRef, album)
                                                    } else {
                                                        val next = selectedAlbumUrls.toMutableSet()
                                                        if (!next.add(album.albumUrl)) {
                                                            next.remove(album.albumUrl)
                                                        }
                                                        selectedAlbumUrls = next
                                                    }
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Checkbox(
                                                        checked = isChecked,
                                                        onCheckedChange = { checked ->
                                                            val next = selectedAlbumUrls.toMutableSet()
                                                            if (checked) next.add(album.albumUrl) else next.remove(album.albumUrl)
                                                            selectedAlbumUrls = next
                                                        }
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = if (album.isFolder) Icons.Outlined.Folder else Icons.Outlined.PhotoAlbum,
                                                        contentDescription = null,
                                                        tint = if (isChecked) GhAccentGreen else if (album.isFolder) GhAccentBlue else GhTextSecondary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column {
                                                        Text(
                                                            text = album.title,
                                                            color = GhTextPrimary,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (album.subtitle.isNotBlank()) {
                                                            Text(
                                                                text = album.subtitle,
                                                                color = GhTextSecondary,
                                                                fontSize = 11.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                                if (canDrillIntoSubfolder) {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            openSubfolderInWebView(webViewRef, album)
                                                        },
                                                        shape = RoundedCornerShape(50),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.AccountTree,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Open Subfolders", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!showRawWebViewBrowser && (isSignedInAndScanning || isExtractingSelectedAlbums)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(GhTileSurface)
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(color = GhAccentGreen)
                                    Text(
                                        text = statusHeadline,
                                        color = GhTextPrimary,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = statusSubtext,
                                        color = GhAccentGreen,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
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
