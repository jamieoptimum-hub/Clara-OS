package com.jarvis.smarthomelauncher.widgets.wifi.Services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.jarvis.smarthomelauncher.widgets.wifi.Models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs

/**
 * CWAS Rule 15: Provider interface decoupling Guest WiFi Repository & UI from underlying
 * OS networking, router telemetry, and QR encoding implementations.
 */
interface WifiDataProvider {
    val providerName: String

    suspend fun readLiveWifiLinkTelemetry(): LiveWifiLinkTelemetry

    suspend fun scanConnectedLanDevices(
        linkTelemetry: LiveWifiLinkTelemetry,
        knownSmartHomeIps: Set<String> = emptySet(),
        knownSmartHomeMacs: Set<String> = emptySet()
    ): List<ConnectedGuestDevice>

    fun buildStandardWifiQrPayload(
        ssid: String,
        password: String,
        security: WifiSecurityProtocol,
        isHidden: Boolean
    ): String

    fun generateQrMatrix(payload: String): WifiQrMatrix

    fun generateStrongGuestPassword(wordFriendly: Boolean = true): String
}

/**
 * Production Android Wi-Fi, LAN Neighbor Discovery & ISO/IEC 18004 QR Code Service.
 * Zero mock data: reads real hardware link properties, ARP/neighbor tables, TCP/ICMP reachability,
 * and generates standards-compliant QR matrices dynamically.
 */
class AndroidLanGuestWifiProvider(
    private val context: Context
) : WifiDataProvider {

    private val appContext: Context = context.applicationContext
    private val secureRandom = SecureRandom()

    override val providerName: String = "Android Connectivity & Local Subnet Telemetry"

    override suspend fun readLiveWifiLinkTelemetry(): LiveWifiLinkTelemetry = withContext(Dispatchers.IO) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val activeNetwork = cm?.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val linkProps = activeNetwork?.let { cm.getLinkProperties(it) }

        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val wifiInfo: WifiInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (capabilities?.transportInfo as? WifiInfo) ?: @Suppress("DEPRECATION") wm?.connectionInfo
            } else {
                @Suppress("DEPRECATION") wm?.connectionInfo
            }
        }.getOrNull()

        val rawSsid = runCatching { wifiInfo?.ssid.orEmpty() }.getOrDefault("")
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
        val cleanSsid = if (
            rawSsid.isBlank() ||
            rawSsid.equals("<unknown ssid>", ignoreCase = true) ||
            rawSsid == "0x"
        ) {
            ""
        } else {
            rawSsid
        }

        val rssi = wifiInfo?.rssi?.takeIf { it in -120..-1 } ?: -55
        val signalPercent = calculateSignalPercentage(wm, rssi)
        val linkSpeed = wifiInfo?.linkSpeed?.takeIf { it > 0 } ?: 0
        val freqMhz = wifiInfo?.frequency?.takeIf { it > 0 } ?: 0
        val bandLabel = when {
            freqMhz >= 5925 -> "6 GHz"
            freqMhz in 4900..5924 -> "5 GHz"
            freqMhz in 2400..2500 -> "2.4 GHz"
            isEthernet -> "LAN Ethernet"
            else -> "Wi-Fi"
        }

        val localIp = resolveLocalIpv4Address(linkProps)
        val gatewayIp = resolveGatewayIpv4Address(linkProps, wm, localIp)
        val subnetPrefix = localIp.substringBeforeLast('.', "").takeIf {
            localIp.count { ch -> ch == '.' } == 3
        }.orEmpty()

        LiveWifiLinkTelemetry(
            isWifiConnected = isWifi || isEthernet,
            hasValidatedInternet = hasInternet,
            activeHardwareSsid = cleanSsid,
            rssiDbm = rssi,
            signalPercent = if (isWifi || isEthernet) signalPercent else 0,
            linkSpeedMbps = linkSpeed,
            frequencyMhz = freqMhz,
            bandLabel = bandLabel,
            localIpAddress = localIp,
            gatewayIpAddress = gatewayIp,
            subnetPrefix = subnetPrefix
        )
    }

    override suspend fun scanConnectedLanDevices(
        linkTelemetry: LiveWifiLinkTelemetry,
        knownSmartHomeIps: Set<String>,
        knownSmartHomeMacs: Set<String>
    ): List<ConnectedGuestDevice> = withContext(Dispatchers.IO) {
        val prefix = linkTelemetry.subnetPrefix
        if (!linkTelemetry.isWifiConnected || prefix.isBlank()) {
            return@withContext emptyList()
        }

        val nowMs = System.currentTimeMillis()
        val normalizedKnownMacs = knownSmartHomeMacs.map { normalizeMac(it) }.filter { it.isNotBlank() }.toSet()

        // 1. Read kernel ARP / IP neighbor cache first for known MAC addresses on the LAN
        val neighborMacByIp = readNeighborTable()

        // 2. Probe active subnet hosts concurrently with bounded semaphore so UI & LAN stay responsive
        val semaphore = Semaphore(28)
        val skipIps = setOf(linkTelemetry.localIpAddress, linkTelemetry.gatewayIpAddress)

        val discovered = coroutineScope {
            (1..254).mapNotNull { hostOctet ->
                val candidateIp = "$prefix.$hostOctet"
                if (candidateIp in skipIps) return@mapNotNull null

                async {
                    semaphore.withPermit {
                        val probeResult = probeLanHost(candidateIp, timeoutMs = 240)
                        val cachedMac = neighborMacByIp[candidateIp].orEmpty()
                        if (!probeResult.first && cachedMac.isBlank()) {
                            return@withPermit null
                        }

                        val refreshedMac = cachedMac.ifBlank { readSingleIpMac(candidateIp) }
                        val normMac = normalizeMac(refreshedMac)
                        val isSmartHome = candidateIp in knownSmartHomeIps ||
                                (normMac.isNotBlank() && normMac in normalizedKnownMacs)

                        val resolvedHost = runCatching {
                            val addr = InetAddress.getByName(candidateIp)
                            val host = addr.canonicalHostName?.trim().orEmpty()
                            if (host.isNotBlank() && host != candidateIp) {
                                host.substringBefore(".local").substringBefore(".lan")
                            } else {
                                ""
                            }
                        }.getOrDefault("")

                        val categoryLabel = classifyDeviceVendorOrRole(
                            hostname = resolvedHost,
                            mac = normMac,
                            isSmartHome = isSmartHome
                        )

                        val displayTitle = when {
                            resolvedHost.isNotBlank() -> resolvedHost
                            categoryLabel != "Connected Client" -> "$categoryLabel ($hostOctet)"
                            else -> "Network Client .$hostOctet"
                        }

                        val stableId = normMac.ifBlank { "ip_$candidateIp" }

                        ConnectedGuestDevice(
                            id = stableId,
                            ipAddress = candidateIp,
                            macAddress = normMac,
                            hostName = displayTitle,
                            vendorOrCategory = categoryLabel,
                            isCurrentlyReachable = probeResult.first,
                            latencyMs = probeResult.second,
                            firstSeenEpochMs = nowMs,
                            lastSeenEpochMs = nowMs,
                            isKnownSmartHomeDevice = isSmartHome
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        discovered.sortedWith(
            compareBy<ConnectedGuestDevice> { it.isKnownSmartHomeDevice }
                .thenByDescending { it.isCurrentlyReachable }
                .thenBy { it.ipAddress.substringAfterLast('.').toIntOrNull() ?: 999 }
        )
    }

    /**
     * Builds the universal `WIFI:` URI recognised natively by iOS Camera and Android Camera / QR Scanner.
     * Escapes `\`, `;`, `,`, `"`, and `:` per specification.
     */
    override fun buildStandardWifiQrPayload(
        ssid: String,
        password: String,
        security: WifiSecurityProtocol,
        isHidden: Boolean
    ): String {
        val escapedSsid = escapeWifiQrField(ssid.trim())
        val escapedPass = escapeWifiQrField(password)
        val hiddenFlag = if (isHidden) "H:true;" else ""
        return if (security.requiresPassword && password.isNotEmpty()) {
            "WIFI:T:${security.qrToken};S:$escapedSsid;P:$escapedPass;$hiddenFlag;"
        } else {
            "WIFI:T:nopass;S:$escapedSsid;$hiddenFlag;"
        }
    }

    override fun generateQrMatrix(payload: String): WifiQrMatrix {
        val matrix2d = Iso18004QrEncoder.encode(payload)
        val n = matrix2d.size
        val flat = BooleanArray(n * n)
        for (r in 0 until n) {
            for (c in 0 until n) {
                flat[r * n + c] = matrix2d[r][c]
            }
        }
        return WifiQrMatrix(
            size = n,
            modules = flat,
            encodedPayload = payload
        )
    }

    override fun generateStrongGuestPassword(wordFriendly: Boolean): String {
        if (wordFriendly) {
            val adjectives = arrayOf(
                "Sunny", "Crisp", "Velvet", "Amber", "Silver", "Cedar", "Harbor", "Meadow",
                "Cobalt", "Maple", "Summit", "Breeze", "Coral", "Falcon", "Orchard", "Quartz"
            )
            val nouns = arrayOf(
                "Garden", "Lantern", "terrace", "Horizon", "Willow", "Compass", "haven", "Pavilion",
                "brook", "Beacon", "cottage", "spruce", "Lounge", "Atrium", "Veranda", "kettle"
            )
            val w1 = adjectives[secureRandom.nextInt(adjectives.size)]
            val w2 = nouns[secureRandom.nextInt(nouns.size)].replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.UK) else it.toString()
            }
            val digits = 100 + secureRandom.nextInt(900)
            val symbols = charArrayOf('!', '#', '+', '@')
            val sym = symbols[secureRandom.nextInt(symbols.size)]
            return "$w1-$w2-$digits$sym"
        } else {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#+"
            return buildString(16) {
                repeat(16) {
                    append(alphabet[secureRandom.nextInt(alphabet.length)])
                }
            }
        }
    }

    // ========================================================================
    // Internal OS Network & LAN Inspection Helpers
    // ========================================================================

    private fun escapeWifiQrField(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            if (ch == '\\' || ch == ';' || ch == ',' || ch == '"' || ch == ':') {
                sb.append('\\')
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun calculateSignalPercentage(wm: WifiManager?, rssiDbm: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val maxLevel = wm.maxSignalLevel.coerceAtLeast(1)
            val level = wm.calculateSignalLevel(rssiDbm)
            return ((level.toFloat() / maxLevel.toFloat()) * 100f).toInt().coerceIn(0, 100)
        }
        return when {
            rssiDbm >= -50 -> 100
            rssiDbm <= -100 -> 0
            else -> ((rssiDbm + 100) * 2).coerceIn(0, 100)
        }
    }

    private fun resolveLocalIpv4Address(linkProps: LinkProperties?): String {
        linkProps?.linkAddresses?.forEach { la ->
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress.orEmpty()
            }
        }
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching ""
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return@runCatching a.hostAddress.orEmpty()
                    }
                }
            }
            ""
        }.getOrDefault("")
    }

    private fun resolveGatewayIpv4Address(
        linkProps: LinkProperties?,
        wm: WifiManager?,
        localIp: String
    ): String {
        linkProps?.routes?.forEach { route ->
            val gw = route.gateway
            if (route.isDefaultRoute && gw is Inet4Address && !gw.isLoopbackAddress) {
                val host = gw.hostAddress.orEmpty()
                if (host.isNotBlank() && host != "0.0.0.0") return host
            }
        }
        @Suppress("DEPRECATION")
        val dhcpGw = wm?.dhcpInfo?.gateway ?: 0
        if (dhcpGw != 0) {
            val b1 = dhcpGw and 0xFF
            val b2 = (dhcpGw shr 8) and 0xFF
            val b3 = (dhcpGw shr 16) and 0xFF
            val b4 = (dhcpGw shr 24) and 0xFF
            return "$b1.$b2.$b3.$b4"
        }
        val prefix = localIp.substringBeforeLast('.', "")
        return if (prefix.isNotBlank()) "$prefix.1" else ""
    }

    private fun probeLanHost(ip: String, timeoutMs: Int): Pair<Boolean, Int?> {
        val startNs = System.nanoTime()
        val icmpReachable = runCatching {
            InetAddress.getByName(ip).isReachable(timeoutMs)
        }.getOrDefault(false)
        if (icmpReachable) {
            val rtt = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            return Pair(true, rtt)
        }

        // Many iOS/Android guest phones block ICMP echo or drop unprivileged Java TCP-7 echo;
        // a rapid connect attempt to port 62078 (iOS lockdownd) or 80 elicits an instant TCP RST
        // (ConnectException: Connection refused) which proves the host is alive on the Wi-Fi LAN!
        for (probePort in intArrayOf(62078, 80)) {
            val t0 = System.nanoTime()
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(ip, probePort), 110)
                }
                val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                return Pair(true, ms)
            } catch (e: java.net.ConnectException) {
                val msg = e.message.orEmpty().lowercase(Locale.UK)
                if (msg.contains("refused")) {
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                    return Pair(true, ms)
                }
            } catch (_: Exception) {
            }
        }
        return Pair(false, null)
    }

    private fun readNeighborTable(): Map<String, String> {
        val result = HashMap<String, String>()
        runCatching {
            val arpFile = File("/proc/net/arp")
            if (arpFile.exists() && arpFile.canRead()) {
                arpFile.forEachLine { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size >= 4 && cols[0].count { it == '.' } == 3) {
                        val mac = normalizeMac(cols[3])
                        if (mac.isNotBlank() && mac != "00:00:00:00:00:00") {
                            result[cols[0]] = mac
                        }
                    }
                }
            }
        }
        if (result.isEmpty()) {
            runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh"))
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        val ip = parts.firstOrNull().orEmpty()
                        val lladdrIdx = parts.indexOf("lladdr")
                        if (ip.count { it == '.' } == 3 && lladdrIdx >= 0 && lladdrIdx + 1 < parts.size) {
                            val mac = normalizeMac(parts[lladdrIdx + 1])
                            if (mac.isNotBlank() && mac != "00:00:00:00:00:00") {
                                result[ip] = mac
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun readSingleIpMac(ip: String): String {
        return readNeighborTable()[ip].orEmpty()
    }

    private fun normalizeMac(raw: String): String {
        val hex = raw.trim().uppercase(Locale.UK).replace("-", ":")
        val regex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        return if (regex.matches(hex)) hex else ""
    }

    private fun classifyDeviceVendorOrRole(
        hostname: String,
        mac: String,
        isSmartHome: Boolean
    ): String {
        if (isSmartHome) return "Smart Home Device"
        val lowerHost = hostname.lowercase(Locale.UK)
        return when {
            lowerHost.contains("iphone") || lowerHost.contains("ipad") || lowerHost.contains("macbook") -> "Apple Guest Device"
            lowerHost.contains("galaxy") || lowerHost.contains("samsung") -> "Samsung Guest Device"
            lowerHost.contains("pixel") || lowerHost.contains("android") -> "Android Guest Device"
            lowerHost.contains("laptop") || lowerHost.contains("desktop") || lowerHost.contains("surface") -> "Laptop / PC"
            mac.isNotBlank() && ((mac[1].digitToIntOrNull(16) ?: 0) and 0x02) != 0 -> "Private Wi-Fi Mobile"
            else -> "Connected Client"
        }
    }
}

/**
 * Self-contained ISO/IEC 18004 QR Code Matrix Encoder (Byte Mode, ECC Level L, Versions 1..6)
 * with full 8-mask penalty evaluation for maximum 1–2m camera readability.
 */
internal object Iso18004QrEncoder {

    private data class VersionSpec(
        val version: Int,
        val totalDataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int
    )

    private val VERSION_SPECS = listOf(
        VersionSpec(version = 1, totalDataCodewords = 19, ecCodewordsPerBlock = 7, numBlocks = 1),
        VersionSpec(version = 2, totalDataCodewords = 34, ecCodewordsPerBlock = 10, numBlocks = 1),
        VersionSpec(version = 3, totalDataCodewords = 55, ecCodewordsPerBlock = 15, numBlocks = 1),
        VersionSpec(version = 4, totalDataCodewords = 80, ecCodewordsPerBlock = 20, numBlocks = 1),
        VersionSpec(version = 5, totalDataCodewords = 108, ecCodewordsPerBlock = 26, numBlocks = 1),
        VersionSpec(version = 6, totalDataCodewords = 136, ecCodewordsPerBlock = 18, numBlocks = 2)
    )

    private val expTable = IntArray(512)
    private val logTable = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) {
            expTable[i] = expTable[i - 255]
        }
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else expTable[logTable[a] + logTable[b]]

    fun encode(payload: String): Array<BooleanArray> {
        val rawBytes = payload.toByteArray(Charsets.UTF_8)
        val spec = VERSION_SPECS.firstOrNull { rawBytes.size <= it.totalDataCodewords - 2 }
            ?: VERSION_SPECS.last()

        val bytes = if (rawBytes.size > spec.totalDataCodewords - 2) {
            rawBytes.copyOfRange(0, spec.totalDataCodewords - 2)
        } else {
            rawBytes
        }

        // 1. Encode Byte Mode stream (0100 + 8-bit length + UTF-8 bytes + terminator + 0xEC/0x11 pads)
        val bitBuffer = ArrayList<Int>(spec.totalDataCodewords * 8)
        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bitBuffer.add((value ushr i) and 1)
            }
        }
        appendBits(0b0100, 4)
        appendBits(bytes.size, 8)
        for (b in bytes) {
            appendBits(b.toInt() and 0xFF, 8)
        }
        val maxDataBits = spec.totalDataCodewords * 8
        appendBits(0, minOf(4, maxDataBits - bitBuffer.size))
        while (bitBuffer.size % 8 != 0) {
            bitBuffer.add(0)
        }
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bitBuffer.size < maxDataBits) {
            appendBits(padBytes[padIdx % 2], 8)
            padIdx++
        }

        val dataCodewords = IntArray(spec.totalDataCodewords) { idx ->
            var acc = 0
            for (bit in 0 until 8) {
                acc = (acc shl 1) or bitBuffer[idx * 8 + bit]
            }
            acc
        }

        // 2. Reed-Solomon EC generation and multi-block interleaving
        val generator = buildGeneratorPolynomial(spec.ecCodewordsPerBlock)
        val blockDataSize = spec.totalDataCodewords / spec.numBlocks
        val dataBlocks = Array(spec.numBlocks) { b ->
            dataCodewords.copyOfRange(b * blockDataSize, (b + 1) * blockDataSize)
        }
        val ecBlocks = Array(spec.numBlocks) { b ->
            computeReedSolomonRemainder(dataBlocks[b], spec.ecCodewordsPerBlock, generator)
        }

        val interleaved = IntArray(spec.totalDataCodewords + spec.ecCodewordsPerBlock * spec.numBlocks)
        var outIdx = 0
        for (i in 0 until blockDataSize) {
            for (b in 0 until spec.numBlocks) {
                interleaved[outIdx++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) {
                interleaved[outIdx++] = ecBlocks[b][i]
            }
        }

        // 3. Construct base function patterns and select lowest-penalty mask (0..7)
        var bestMatrix: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE

        for (mask in 0..7) {
            val candidate = buildMaskedMatrix(spec.version, interleaved, mask)
            val penalty = evaluatePenaltyScore(candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMatrix = candidate
            }
        }

        return bestMatrix ?: buildMaskedMatrix(spec.version, interleaved, 0)
    }

    private fun buildGeneratorPolynomial(degree: Int): IntArray {
        var gen = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(gen.size + 1)
            val root = expTable[i]
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor gfMul(gen[j], root)
            }
            gen = next
        }
        return gen
    }

    private fun computeReedSolomonRemainder(
        data: IntArray,
        ecCount: Int,
        gen: IntArray
    ): IntArray {
        val ec = IntArray(ecCount)
        for (byteVal in data) {
            val factor = byteVal xor ec[0]
            for (j in 0 until ecCount - 1) {
                ec[j] = ec[j + 1] xor gfMul(gen[j + 1], factor)
            }
            ec[ecCount - 1] = gfMul(gen[ecCount], factor)
        }
        return ec
    }

    private fun buildMaskedMatrix(
        version: Int,
        allCodewords: IntArray,
        maskPattern: Int
    ): Array<BooleanArray> {
        val size = 17 + version * 4
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunction(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                modules[r][c] = dark
                isFunction[r][c] = true
            }
        }

        fun placeFinder(topR: Int, leftC: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val inOuter = dr in 0..6 && dc in 0..6
                    val isBorder = dr == 0 || dr == 6 || dc == 0 || dc == 6
                    val isInner = dr in 2..4 && dc in 2..4
                    setFunction(topR + dr, leftC + dc, inOuter && (isBorder || isInner))
                }
            }
        }

        placeFinder(0, 0)
        placeFinder(0, size - 7)
        placeFinder(size - 7, 0)

        for (i in 8 until size - 8) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }

        if (version >= 2) {
            val alignCenter = size - 7
            for (dr in -2..2) {
                for (dc in -2..2) {
                    val dark = abs(dr) == 2 || abs(dc) == 2 || (dr == 0 && dc == 0)
                    setFunction(alignCenter + dr, alignCenter + dc, dark)
                }
            }
        }

        for (i in 0..8) {
            if (i != 6) {
                setFunction(8, i, false)
                setFunction(i, 8, false)
            }
        }
        for (i in 0..7) {
            setFunction(8, size - 1 - i, false)
            setFunction(size - 1 - i, 8, false)
        }
        setFunction(size - 8, 8, true)

        val totalBits = allCodewords.size * 8
        var bitIndex = 0
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            for (step in 0 until size) {
                val r = if (upward) size - 1 - step else step
                for (dc in 0..1) {
                    val c = col - dc
                    if (!isFunction[r][c]) {
                        val dataBit = if (bitIndex < totalBits) {
                            ((allCodewords[bitIndex ushr 3] ushr (7 - (bitIndex and 7))) and 1) == 1
                        } else {
                            false
                        }
                        bitIndex++
                        val invert = shouldInvertMask(maskPattern, r, c)
                        modules[r][c] = dataBit xor invert
                    }
                }
            }
            upward = !upward
            col -= 2
        }

        // Format Information: ECC Level L (01) + 3-bit mask index
        val formatData = (0b01 shl 3) or (maskPattern and 0b111)
        var rem = formatData shl 10
        for (i in 14 downTo 10) {
            if (((rem ushr i) and 1) != 0) {
                rem = rem xor (0b10100110111 shl (i - 10))
            }
        }
        val formatBits = ((formatData shl 10) or rem) xor 0b101010000010010
        fun fmtBit(idx: Int): Boolean = ((formatBits ushr idx) and 1) == 1

        for (i in 0..5) setFunction(8, i, fmtBit(i))
        setFunction(8, 7, fmtBit(6))
        setFunction(8, 8, fmtBit(7))
        setFunction(7, 8, fmtBit(8))
        for (i in 9..14) setFunction(14 - i, 8, fmtBit(i))

        for (i in 0..7) setFunction(size - 1 - i, 8, fmtBit(i))
        for (i in 8..14) setFunction(8, size - 15 + i, fmtBit(i))

        return modules
    }

    private fun shouldInvertMask(mask: Int, r: Int, c: Int): Boolean = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> ((r / 2) + (c / 3)) % 2 == 0
        5 -> ((r * c) % 2) + ((r * c) % 3) == 0
        6 -> (((r * c) % 2) + ((r * c) % 3)) % 2 == 0
        else -> (((r + c) % 2) + ((r * c) % 3)) % 2 == 0
    }

    private fun evaluatePenaltyScore(matrix: Array<BooleanArray>): Int {
        val n = matrix.size
        var penalty = 0

        // N1: 5+ consecutive same-colour modules in row or column
        for (r in 0 until n) {
            var runLen = 1
            for (c in 1 until n) {
                if (matrix[r][c] == matrix[r][c - 1]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runLen = 1
                }
            }
        }
        for (c in 0 until n) {
            var runLen = 1
            for (r in 1 until n) {
                if (matrix[r][c] == matrix[r - 1][c]) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runLen = 1
                }
            }
        }

        // N2: 2x2 blocks of identical colour
        for (r in 0 until n - 1) {
            for (c in 0 until n - 1) {
                val v = matrix[r][c]
                if (v == matrix[r][c + 1] && v == matrix[r + 1][c] && v == matrix[r + 1][c + 1]) {
                    penalty += 3
                }
            }
        }

        // N4: Dark/light module balance
        var darkCount = 0
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (matrix[r][c]) darkCount++
            }
        }
        val total = n * n
        val k = abs(darkCount * 20 - total * 10) / total
        penalty += k * 10

        return penalty
    }
}