package com.jarvis.smarthomelauncher.ui.popups

import com.jarvis.smarthomelauncher.*
import com.jarvis.smarthomelauncher.core.*
import com.jarvis.smarthomelauncher.engines.*
import com.jarvis.smarthomelauncher.integrations.*
import com.jarvis.smarthomelauncher.ui.modals.*
import com.jarvis.smarthomelauncher.ui.screens.*
import com.jarvis.smarthomelauncher.ui.widgets.*

// ---------------------------------------------------------------------------
// ISO/IEC 18004 STANDARD-COMPLIANT QR MATRIX ENCODER (BYTE MODE, ECC LEVEL L)
// ---------------------------------------------------------------------------
internal fun encodeIso18004QrMatrix(payload: String): Array<BooleanArray> {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    // Single-block Byte-mode capacities for Versions 1..5 (ECC Level L):
    // V1: 19 data / 7 EC, V2: 34 data / 10 EC, V3: 55 data / 15 EC, V4: 80 data / 20 EC, V5: 108 data / 26 EC
    val version = when {
        bytes.size <= 17 -> 1
        bytes.size <= 32 -> 2
        bytes.size <= 53 -> 3
        bytes.size <= 78 -> 4
        else -> 5
    }
    val dataCodewordsCap = when (version) {
        1 -> 19; 2 -> 34; 3 -> 55; 4 -> 80; else -> 108
    }
    val ecCodewordsCount = when (version) {
        1 -> 7; 2 -> 10; 3 -> 15; 4 -> 20; else -> 26
    }
    val trimmedBytes = if (bytes.size > dataCodewordsCap - 2) {
        bytes.copyOfRange(0, dataCodewordsCap - 2)
    } else {
        bytes
    }

    // 1. Pack Mode Indicator (0100 = Byte Mode), 8-bit count, payload bytes, terminator, and pad bytes
    val bitBuffer = ArrayList<Int>(dataCodewordsCap * 8)
    fun appendBits(value: Int, length: Int) {
        for (i in length - 1 downTo 0) {
            bitBuffer.add((value ushr i) and 1)
        }
    }
    appendBits(0b0100, 4)
    appendBits(trimmedBytes.size, 8)
    for (b in trimmedBytes) {
        appendBits(b.toInt() and 0xFF, 8)
    }
    val maxDataBits = dataCodewordsCap * 8
    val terminatorBits = minOf(4, maxDataBits - bitBuffer.size)
    appendBits(0, terminatorBits)
    while (bitBuffer.size % 8 != 0) {
        bitBuffer.add(0)
    }
    val padBytes = intArrayOf(0xEC, 0x11)
    var padIdx = 0
    while (bitBuffer.size < maxDataBits) {
        appendBits(padBytes[padIdx % 2], 8)
        padIdx++
    }

    val dataCodewords = IntArray(dataCodewordsCap) { idx ->
        var b = 0
        for (bit in 0 until 8) {
            b = (b shl 1) or bitBuffer[idx * 8 + bit]
        }
        b
    }

    // 2. GF(256) Reed-Solomon Error Correction (primitive polynomial 0x11D)
    val expTable = IntArray(512)
    val logTable = IntArray(256)
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
    fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else expTable[logTable[a] + logTable[b]]

    var gen = intArrayOf(1)
    for (i in 0 until ecCodewordsCount) {
        val nextGen = IntArray(gen.size + 1)
        val root = expTable[i]
        for (j in gen.indices) {
            nextGen[j] = nextGen[j] xor gen[j]
            nextGen[j + 1] = nextGen[j + 1] xor gfMul(gen[j], root)
        }
        gen = nextGen
    }

    val ecCodewords = IntArray(ecCodewordsCount)
    for (byteVal in dataCodewords) {
        val factor = byteVal xor ecCodewords[0]
        for (j in 0 until ecCodewordsCount - 1) {
            ecCodewords[j] = ecCodewords[j + 1] xor gfMul(gen[j + 1], factor)
        }
        ecCodewords[ecCodewordsCount - 1] = gfMul(gen[ecCodewordsCount], factor)
    }

    // 3. Build Module Matrix (Finder, Separator, Timing, Alignment & Dark Module)
    val size = 17 + version * 4
    val modules = Array(size) { BooleanArray(size) }
    val isFunction = Array(size) { BooleanArray(size) }

    fun setFunctionModule(r: Int, c: Int, dark: Boolean) {
        if (r in 0 until size && c in 0 until size) {
            modules[r][c] = dark
            isFunction[r][c] = true
        }
    }

    fun placeFinderPattern(topR: Int, leftC: Int) {
        for (dr in -1..7) {
            for (dc in -1..7) {
                val r = topR + dr
                val c = leftC + dc
                val inOuter = dr in 0..6 && dc in 0..6
                val isBorder = dr == 0 || dr == 6 || dc == 0 || dc == 6
                val isInner = dr in 2..4 && dc in 2..4
                setFunctionModule(r, c, inOuter && (isBorder || isInner))
            }
        }
    }

    placeFinderPattern(0, 0)
    placeFinderPattern(0, size - 7)
    placeFinderPattern(size - 7, 0)

    for (i in 8 until size - 8) {
        setFunctionModule(6, i, i % 2 == 0)
        setFunctionModule(i, 6, i % 2 == 0)
    }

    if (version >= 2) {
        val alignCenter = size - 7
        for (dr in -2..2) {
            for (dc in -2..2) {
                val dark = kotlin.math.abs(dr) == 2 || kotlin.math.abs(dc) == 2 || (dr == 0 && dc == 0)
                setFunctionModule(alignCenter + dr, alignCenter + dc, dark)
            }
        }
    }

    // Reserve Format Info areas & set Dark Module
    for (i in 0..8) {
        if (i != 6) {
            setFunctionModule(8, i, false)
            setFunctionModule(i, 8, false)
        }
    }
    for (i in 0..7) {
        setFunctionModule(8, size - 1 - i, false)
        setFunctionModule(size - 1 - i, 8, false)
    }
    setFunctionModule(size - 8, 8, true)

    // 4. Zigzag Data + EC Bit Placement & Mask 0 ((r + c) % 2 == 0)
    val allCodewords = dataCodewords + ecCodewords
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
                    val maskBit = (r + c) % 2 == 0
                    modules[r][c] = dataBit xor maskBit
                }
            }
        }
        upward = !upward
        col -= 2
    }

    // 5. Compute & Place BCH(15,5) Format Information for ECC Level L (01) + Mask 0 (000)
    val formatData = (0b01 shl 3) or 0b000
    var rem = formatData shl 10
    for (i in 14 downTo 10) {
        if (((rem ushr i) and 1) != 0) {
            rem = rem xor (0b10100110111 shl (i - 10))
        }
    }
    val formatBits = ((formatData shl 10) or rem) xor 0b101010000010010
    fun fmtBit(idx: Int): Boolean = ((formatBits ushr idx) and 1) == 1

    for (i in 0..5) setFunctionModule(8, i, fmtBit(i))
    setFunctionModule(8, 7, fmtBit(6))
    setFunctionModule(8, 8, fmtBit(7))
    setFunctionModule(7, 8, fmtBit(8))
    for (i in 9..14) setFunctionModule(14 - i, 8, fmtBit(i))

    for (i in 0..7) setFunctionModule(size - 1 - i, 8, fmtBit(i))
    for (i in 8..14) setFunctionModule(8, size - 15 + i, fmtBit(i))

    return modules
}
