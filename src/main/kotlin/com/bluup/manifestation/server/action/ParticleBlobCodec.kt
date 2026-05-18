package com.bluup.manifestation.server.action

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.roundToInt

object ParticleBlobCodec {
    const val MAX_POINTS = 2500
    const val MAX_BLOB_BYTES = 128 * 1024
    private const val VERSION = 1
    private const val QUANT = 1000.0

    data class Point(val offset: Vec3, val color: Vec3)

    enum class GradientMode { X, Y, Z, DISTANCE }

    data class GradientStop(val t: Double, val color: Vec3)

    sealed class ColorSpec {
        object PerPoint : ColorSpec()
        data class Single(val color: Vec3) : ColorSpec()
        data class Gradient(val mode: GradientMode, val stops: List<GradientStop>) : ColorSpec()
    }

    private data class PositionPayload(val mode: Int, val bytes: ByteArray)

    private data class Run(val start: IntArray, val length: Int, val step: Int)

    private val AXES_ALL = booleanArrayOf(true, true, true)

    private const val POS_MODE_GRID_RLE = 0
    private const val POS_MODE_STEP_RLE = 1
    private const val POS_MODE_DELTA = 2
    private const val POS_MODE_RAW = 3

    private const val GRID_TOLERANCE = 0.0015

    private const val COLOR_MODE_PER_POINT = 0
    private const val COLOR_MODE_SINGLE = 1
    private const val COLOR_MODE_GRADIENT = 2

    /**
     * Axis-locked encoding: If all points are locked to a plane or line (i.e., one or two coordinates are constant),
     * only store the varying axes. Otherwise, store all three axes as before.
     * The axis lock is inferred automatically, or can be hinted via the optional [axisHint] ("x", "y", "z", "xy", "xz", "yz").
     * If axis-locked, the constant coordinate(s) are stored once, and only the varying coordinates are delta-encoded.
     * RLE is probably more efficient, but should keep this as a fallback!
     */
    fun encode(points: List<Point>, axisHint: String? = null, colorSpec: ColorSpec? = null): ByteArray {
        if (points.isEmpty()) {
            throw IllegalArgumentException("empty")
        }
        if (points.size > MAX_POINTS) {
            throw IllegalArgumentException("too_many_points")
        }

        val quantized = points.map { intArrayOf(q(it.offset.x), q(it.offset.y), q(it.offset.z)) }
        val axes = inferAxisLock(points, axisHint)
        val rawPayload = encodeRawPayload(quantized)
        val deltaPayload = encodeAxisDeltaPayload(quantized, axes)
        val gridRlePayload = encodeGridNormalizedRlePayload(points)
        val stepRlePayload = encodeStepAwareRlePayload(quantized)

        val posPayload = when {
            gridRlePayload != null && gridRlePayload.bytes.size < rawPayload.bytes.size -> gridRlePayload
            stepRlePayload != null && stepRlePayload.bytes.size < rawPayload.bytes.size -> stepRlePayload
            deltaPayload.bytes.size < rawPayload.bytes.size -> deltaPayload
            else -> rawPayload
        }

        val resolvedColorSpec = resolveColorSpec(points, colorSpec)

        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeByte(VERSION)
        buf.writeVarInt(points.size)
        buf.writeByte(posPayload.mode)
        buf.writeVarInt(posPayload.bytes.size)
        buf.writeBytes(posPayload.bytes)

        when (resolvedColorSpec) {
            is ColorSpec.PerPoint -> {
                buf.writeByte(COLOR_MODE_PER_POINT)
                for (point in points) {
                    buf.writeByte(Mth.clamp((point.color.x * 255.0).toInt(), 0, 255))
                    buf.writeByte(Mth.clamp((point.color.y * 255.0).toInt(), 0, 255))
                    buf.writeByte(Mth.clamp((point.color.z * 255.0).toInt(), 0, 255))
                }
            }

            is ColorSpec.Single -> {
                buf.writeByte(COLOR_MODE_SINGLE)
                buf.writeByte(Mth.clamp((resolvedColorSpec.color.x * 255.0).toInt(), 0, 255))
                buf.writeByte(Mth.clamp((resolvedColorSpec.color.y * 255.0).toInt(), 0, 255))
                buf.writeByte(Mth.clamp((resolvedColorSpec.color.z * 255.0).toInt(), 0, 255))
            }

            is ColorSpec.Gradient -> {
                val stops = normalizeStops(resolvedColorSpec.stops)
                if (stops.size < 2) {
                    throw IllegalArgumentException("gradient_stops")
                }

                buf.writeByte(COLOR_MODE_GRADIENT)
                buf.writeByte(gradientModeToByte(resolvedColorSpec.mode))
                buf.writeVarInt(stops.size)
                for (stop in stops) {
                    buf.writeByte(Mth.clamp((stop.t * 255.0).toInt(), 0, 255))
                    buf.writeByte(Mth.clamp((stop.color.x * 255.0).toInt(), 0, 255))
                    buf.writeByte(Mth.clamp((stop.color.y * 255.0).toInt(), 0, 255))
                    buf.writeByte(Mth.clamp((stop.color.z * 255.0).toInt(), 0, 255))
                }
            }
        }

        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        if (out.size > MAX_BLOB_BYTES) {
            throw IllegalArgumentException("blob_too_large")
        }
        return out
    }

    /**
     * Decodes axis-locked blobs. See [encode] for format.
     */
    fun decode(blob: ByteArray): List<Point> {
        if (blob.isEmpty() || blob.size > MAX_BLOB_BYTES) {
            throw IllegalArgumentException("blob_size")
        }

        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(blob))
        val version = buf.readUnsignedByte().toInt()
        if (version != VERSION) {
            throw IllegalArgumentException("blob_version")
        }

        val count = buf.readVarInt()
        if (count <= 0 || count > MAX_POINTS) {
            throw IllegalArgumentException("blob_points")
        }
        val posMode = buf.readUnsignedByte().toInt()
        val posByteLen = buf.readVarInt()
        if (posByteLen < 0 || posByteLen > buf.readableBytes()) {
            throw IllegalArgumentException("blob_positions")
        }
        val posBytes = ByteArray(posByteLen)
        buf.readBytes(posBytes)
        val coords = decodePositions(count, posMode, posBytes)

        val points = ArrayList<Point>(count)
        val colorMode = buf.readUnsignedByte().toInt()
        when (colorMode) {
            COLOR_MODE_PER_POINT -> {
                for (i in 0 until count) {
                    if (!buf.isReadable(3)) {
                        throw IllegalArgumentException("blob_colors")
                    }
                    val r = (buf.readUnsignedByte().toInt() / 255.0)
                    val g = (buf.readUnsignedByte().toInt() / 255.0)
                    val b = (buf.readUnsignedByte().toInt() / 255.0)
                    points.add(Point(coords[i], Vec3(r, g, b)))
                }
            }

            COLOR_MODE_SINGLE -> {
                if (!buf.isReadable(3)) {
                    throw IllegalArgumentException("blob_colors")
                }
                val r = (buf.readUnsignedByte().toInt() / 255.0)
                val g = (buf.readUnsignedByte().toInt() / 255.0)
                val b = (buf.readUnsignedByte().toInt() / 255.0)
                val c = Vec3(r, g, b)
                for (i in 0 until count) {
                    points.add(Point(coords[i], c))
                }
            }

            COLOR_MODE_GRADIENT -> {
                val mode = byteToGradientMode(buf.readByte())
                val stopCount = buf.readVarInt()
                if (stopCount < 2) {
                    throw IllegalArgumentException("blob_colors")
                }
                val stops = ArrayList<GradientStop>(stopCount)
                for (i in 0 until stopCount) {
                    if (!buf.isReadable(4)) {
                        throw IllegalArgumentException("blob_colors")
                    }
                    val t = (buf.readUnsignedByte().toInt() / 255.0)
                    val r = (buf.readUnsignedByte().toInt() / 255.0)
                    val g = (buf.readUnsignedByte().toInt() / 255.0)
                    val b = (buf.readUnsignedByte().toInt() / 255.0)
                    stops.add(GradientStop(t, Vec3(r, g, b)))
                }
                val normalizedStops = normalizeStops(stops)
                for (coord in coords) {
                    val t = computeGradientT(coord, coords, mode)
                    val c = sampleGradient(t, normalizedStops)
                    points.add(Point(coord, c))
                }
            }

            else -> throw IllegalArgumentException("blob_colors")
        }

        return points
    }

    // Eh, not gonna go down this route any more, it's brittle to try and get this working with iota count. Leave for now though.
    fun computeVirtualWeight(pointCount: Int, blobBytes: Int): Int {
        val fromPoints = (pointCount + 127) / 128
        val fromBytes = (blobBytes + 4095) / 4096
        return 1 + fromPoints + fromBytes
    }

    private fun q(v: Double): Int = (v * QUANT).toInt()
    private fun zz(v: Int): Int = (v shl 1) xor (v shr 31)
    private fun unzz(v: Int): Int = (v ushr 1) xor -(v and 1)

    private fun encodeAxisDeltaPayload(quantized: List<IntArray>, axes: BooleanArray): PositionPayload {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeByte(axesToByte(axes))

        val first = quantized[0]
        if (!axes[0]) buf.writeVarInt(zz(first[0]))
        if (!axes[1]) buf.writeVarInt(zz(first[1]))
        if (!axes[2]) buf.writeVarInt(zz(first[2]))

        var prev = intArrayOf(first[0], first[1], first[2])
        for (i in quantized.indices) {
            val qv = quantized[i]
            for (j in 0..2) {
                if (axes[j]) {
                    val delta = if (i == 0) qv[j] else qv[j] - prev[j]
                    buf.writeVarInt(zz(delta))
                }
            }
            prev = qv
        }

        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return PositionPayload(POS_MODE_DELTA, out)
    }

    private fun encodeRawPayload(quantized: List<IntArray>): PositionPayload {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        for (qv in quantized) {
            buf.writeVarInt(zz(qv[0]))
            buf.writeVarInt(zz(qv[1]))
            buf.writeVarInt(zz(qv[2]))
        }
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return PositionPayload(POS_MODE_RAW, out)
    }

    private fun encodeGridNormalizedRlePayload(points: List<Point>): PositionPayload? {
        val grid = inferGrid(points) ?: return null
        val indexed = points.map {
            intArrayOf(
                (it.offset.x * grid[0]).roundToInt(),
                (it.offset.y * grid[1]).roundToInt(),
                (it.offset.z * grid[2]).roundToInt()
            )
        }

        var bestBytes: ByteArray? = null
        for (runAxis in 0..2) {
            val runs = buildUnitRuns(indexed, runAxis)
            if (runs.size >= indexed.size) {
                continue
            }

            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeVarInt(grid[0])
            buf.writeVarInt(grid[1])
            buf.writeVarInt(grid[2])
            buf.writeByte(runAxis)
            buf.writeVarInt(runs.size)
            for (run in runs) {
                buf.writeVarInt(zz(run.start[0]))
                buf.writeVarInt(zz(run.start[1]))
                buf.writeVarInt(zz(run.start[2]))
                buf.writeVarInt(run.length)
            }

            val out = ByteArray(buf.readableBytes())
            buf.readBytes(out)
            if (bestBytes == null || out.size < bestBytes.size) {
                bestBytes = out
            }
        }

        return if (bestBytes == null) null else PositionPayload(POS_MODE_GRID_RLE, bestBytes)
    }

    private fun encodeStepAwareRlePayload(quantized: List<IntArray>): PositionPayload? {
        var bestBytes: ByteArray? = null
        for (runAxis in 0..2) {
            val runs = buildStepRuns(quantized, runAxis)
            if (runs.size >= quantized.size) {
                continue
            }

            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeByte(runAxis)
            buf.writeVarInt(runs.size)
            for (run in runs) {
                buf.writeVarInt(zz(run.start[0]))
                buf.writeVarInt(zz(run.start[1]))
                buf.writeVarInt(zz(run.start[2]))
                buf.writeVarInt(zz(run.step))
                buf.writeVarInt(run.length)
            }

            val out = ByteArray(buf.readableBytes())
            buf.readBytes(out)
            if (bestBytes == null || out.size < bestBytes.size) {
                bestBytes = out
            }
        }

        return if (bestBytes == null) null else PositionPayload(POS_MODE_STEP_RLE, bestBytes)
    }

    private fun buildUnitRuns(quantized: List<IntArray>, runAxis: Int): List<Run> {
        val runs = ArrayList<Run>()
        var i = 0
        while (i < quantized.size) {
            val start = quantized[i]
            var len = 1
            while (i + len < quantized.size) {
                val prev = quantized[i + len - 1]
                val cur = quantized[i + len]
                var ok = true
                for (axis in 0..2) {
                    if (axis == runAxis) {
                        if (cur[axis] != prev[axis] + 1) {
                            ok = false
                        }
                    } else if (cur[axis] != start[axis]) {
                        ok = false
                    }
                    if (!ok) break
                }
                if (!ok) break
                len++
            }
            runs.add(Run(intArrayOf(start[0], start[1], start[2]), len, 1))
            i += len
        }
        return runs
    }

    private fun buildStepRuns(quantized: List<IntArray>, runAxis: Int): List<Run> {
        val runs = ArrayList<Run>()
        var i = 0
        while (i < quantized.size) {
            val start = quantized[i]
            var len = 1
            var step = 0
            while (i + len < quantized.size) {
                val prev = quantized[i + len - 1]
                val cur = quantized[i + len]
                if (cur[(runAxis + 1) % 3] != start[(runAxis + 1) % 3] || cur[(runAxis + 2) % 3] != start[(runAxis + 2) % 3]) {
                    break
                }

                val dz = cur[runAxis] - prev[runAxis]
                if (step == 0) {
                    if (dz == 0) break
                    step = dz
                    len++
                    continue
                }
                if (dz != step) {
                    break
                }
                len++
            }
            runs.add(Run(intArrayOf(start[0], start[1], start[2]), len, step))
            i += len
        }
        return runs
    }

    private fun inferGrid(points: List<Point>): IntArray? {
        val gx = inferAxisGrid(points.map { it.offset.x }) ?: return null
        val gy = inferAxisGrid(points.map { it.offset.y }) ?: return null
        val gz = inferAxisGrid(points.map { it.offset.z }) ?: return null
        return intArrayOf(gx, gy, gz)
    }

    private fun inferAxisGrid(values: List<Double>): Int? {
        if (values.isEmpty()) return 1
        val qVals = values.map { q(it) }.distinct().sorted()
        if (qVals.size <= 1) return 1

        var minStep = Int.MAX_VALUE
        for (i in 1 until qVals.size) {
            val d = abs(qVals[i] - qVals[i - 1])
            if (d > 0 && d < minStep) {
                minStep = d
            }
        }
        if (minStep == Int.MAX_VALUE) return 1

        val grid = (QUANT / minStep.toDouble()).roundToInt().coerceAtLeast(1)
        for (v in values) {
            val idx = (v * grid).roundToInt()
            val reconstructed = idx.toDouble() / grid.toDouble()
            if (abs(reconstructed - v) > GRID_TOLERANCE) {
                return null
            }
        }
        return grid
    }

    private fun decodePositions(count: Int, posMode: Int, bytes: ByteArray): List<Vec3> {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        return when (posMode) {
            POS_MODE_GRID_RLE -> decodeGridRlePositions(count, buf)
            POS_MODE_STEP_RLE -> decodeStepRlePositions(count, buf)
            POS_MODE_DELTA -> decodeAxisDeltaPositions(count, buf)
            POS_MODE_RAW -> decodeRawPositions(count, buf)
            else -> throw IllegalArgumentException("blob_positions")
        }
    }

    private fun decodeAxisDeltaPositions(count: Int, buf: FriendlyByteBuf): List<Vec3> {
        val axes = byteToAxes(buf.readByte())
        val consts = IntArray(3)
        val cur = IntArray(3)
        for (j in 0..2) {
            if (!axes[j]) {
                consts[j] = unzz(buf.readVarInt())
            }
        }

        val coords = ArrayList<Vec3>(count)
        for (i in 0 until count) {
            for (j in 0..2) {
                if (axes[j]) {
                    val delta = unzz(buf.readVarInt())
                    cur[j] = if (i == 0) delta else cur[j] + delta
                } else {
                    cur[j] = consts[j]
                }
            }
            coords.add(Vec3(cur[0] / QUANT, cur[1] / QUANT, cur[2] / QUANT))
        }
        return coords
    }

    private fun decodeGridRlePositions(count: Int, buf: FriendlyByteBuf): List<Vec3> {
        val gx = buf.readVarInt()
        val gy = buf.readVarInt()
        val gz = buf.readVarInt()
        if (gx <= 0 || gy <= 0 || gz <= 0) {
            throw IllegalArgumentException("blob_positions")
        }

        val runAxis = buf.readUnsignedByte().toInt()
        if (runAxis !in 0..2) {
            throw IllegalArgumentException("blob_positions")
        }
        val runCount = buf.readVarInt()
        if (runCount <= 0) {
            throw IllegalArgumentException("blob_positions")
        }

        val out = ArrayList<Vec3>(count)
        for (i in 0 until runCount) {
            val x = unzz(buf.readVarInt())
            val y = unzz(buf.readVarInt())
            val z = unzz(buf.readVarInt())
            val len = buf.readVarInt()
            if (len <= 0) {
                throw IllegalArgumentException("blob_positions")
            }

            for (j in 0 until len) {
                val coords = intArrayOf(x, y, z)
                coords[runAxis] = coords[runAxis] + j
                out.add(Vec3(coords[0] / gx.toDouble(), coords[1] / gy.toDouble(), coords[2] / gz.toDouble()))
            }
            if (out.size > count) {
                throw IllegalArgumentException("blob_positions")
            }
        }

        if (out.size != count) {
            throw IllegalArgumentException("blob_positions")
        }
        return out
    }

    private fun decodeStepRlePositions(count: Int, buf: FriendlyByteBuf): List<Vec3> {
        val runAxis = buf.readUnsignedByte().toInt()
        if (runAxis !in 0..2) {
            throw IllegalArgumentException("blob_positions")
        }
        val runCount = buf.readVarInt()
        if (runCount <= 0) {
            throw IllegalArgumentException("blob_positions")
        }

        val out = ArrayList<Vec3>(count)
        for (i in 0 until runCount) {
            val x = unzz(buf.readVarInt())
            val y = unzz(buf.readVarInt())
            val z = unzz(buf.readVarInt())
            val step = unzz(buf.readVarInt())
            val len = buf.readVarInt()
            if (len <= 0) {
                throw IllegalArgumentException("blob_positions")
            }

            for (j in 0 until len) {
                val coords = intArrayOf(x, y, z)
                coords[runAxis] = coords[runAxis] + (step * j)
                out.add(Vec3(coords[0] / QUANT, coords[1] / QUANT, coords[2] / QUANT))
            }
            if (out.size > count) {
                throw IllegalArgumentException("blob_positions")
            }
        }

        if (out.size != count) {
            throw IllegalArgumentException("blob_positions")
        }
        return out
    }

    private fun decodeRawPositions(count: Int, buf: FriendlyByteBuf): List<Vec3> {
        val out = ArrayList<Vec3>(count)
        for (i in 0 until count) {
            if (!buf.isReadable) {
                throw IllegalArgumentException("blob_positions")
            }
            val x = unzz(buf.readVarInt())
            val y = unzz(buf.readVarInt())
            val z = unzz(buf.readVarInt())
            out.add(Vec3(x / QUANT, y / QUANT, z / QUANT))
        }
        return out
    }

    private fun resolveColorSpec(points: List<Point>, requested: ColorSpec?): ColorSpec {
        if (requested != null) {
            return when (requested) {
                is ColorSpec.PerPoint -> ColorSpec.PerPoint
                is ColorSpec.Single -> ColorSpec.Single(clampColor(requested.color))
                is ColorSpec.Gradient -> ColorSpec.Gradient(requested.mode, normalizeStops(requested.stops))
            }
        }

        val first = points[0].color
        val allSame = points.all { sameColor(it.color, first) }
        return if (allSame) ColorSpec.Single(clampColor(first)) else ColorSpec.PerPoint
    }

    private fun normalizeStops(stops: List<GradientStop>): List<GradientStop> {
        return stops
            .map { GradientStop(it.t.coerceIn(0.0, 1.0), clampColor(it.color)) }
            .sortedBy { it.t }
    }

    private fun clampColor(color: Vec3): Vec3 {
        return Vec3(color.x.coerceIn(0.0, 1.0), color.y.coerceIn(0.0, 1.0), color.z.coerceIn(0.0, 1.0))
    }

    private fun sameColor(a: Vec3, b: Vec3): Boolean {
        return q(a.x) == q(b.x) && q(a.y) == q(b.y) && q(a.z) == q(b.z)
    }

    private fun computeGradientT(point: Vec3, all: List<Vec3>, mode: GradientMode): Double {
        return when (mode) {
            GradientMode.X -> {
                val min = all.minOf { it.x }
                val max = all.maxOf { it.x }
                normalize(point.x, min, max)
            }

            GradientMode.Y -> {
                val min = all.minOf { it.y }
                val max = all.maxOf { it.y }
                normalize(point.y, min, max)
            }

            GradientMode.Z -> {
                val min = all.minOf { it.z }
                val max = all.maxOf { it.z }
                normalize(point.z, min, max)
            }

            GradientMode.DISTANCE -> {
                val d = point.length()
                val maxD = all.maxOf { it.length() }
                normalize(d, 0.0, maxD)
            }
        }
    }

    private fun normalize(v: Double, min: Double, max: Double): Double {
        if (max <= min) return 0.0
        return ((v - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    private fun sampleGradient(tIn: Double, stops: List<GradientStop>): Vec3 {
        val t = tIn.coerceIn(0.0, 1.0)
        if (t <= stops.first().t) return stops.first().color
        if (t >= stops.last().t) return stops.last().color

        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (t >= a.t && t <= b.t) {
                val local = normalize(t, a.t, b.t)
                return Vec3(
                    lerp(a.color.x, b.color.x, local),
                    lerp(a.color.y, b.color.y, local),
                    lerp(a.color.z, b.color.z, local)
                )
            }
        }
        return stops.last().color
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun gradientModeToByte(mode: GradientMode): Int {
        return when (mode) {
            GradientMode.X -> 0
            GradientMode.Y -> 1
            GradientMode.Z -> 2
            GradientMode.DISTANCE -> 3
        }
    }

    private fun byteToGradientMode(raw: Byte): GradientMode {
        return when (raw.toInt()) {
            0 -> GradientMode.X
            1 -> GradientMode.Y
            2 -> GradientMode.Z
            3 -> GradientMode.DISTANCE
            else -> throw IllegalArgumentException("blob_colors")
        }
    }

    /**
     * Returns a boolean array [x, y, z] where true means the axis varies, false means it is constant.
     * If [axisHint] is provided, it is used ("x", "y", "z", "xy", "xz", "yz"). Otherwise, inferred from data.
     * Boolean used for decision on how to encode.
     */
    private fun inferAxisLock(points: List<Point>, axisHint: String?): BooleanArray {
        if (axisHint != null) {
            val axes = BooleanArray(3)
            for (c in axisHint.lowercase()) {
                when (c) {
                    'x' -> axes[0] = true
                    'y' -> axes[1] = true
                    'z' -> axes[2] = true
                }
            }
            if (!axes[0] && !axes[1] && !axes[2]) {
                return AXES_ALL
            }
            return axes
        }
        val first = points[0].offset
        var xVar = false
        var yVar = false
        var zVar = false
        for (p in points) {
            if (p.offset.x != first.x) xVar = true
            if (p.offset.y != first.y) yVar = true
            if (p.offset.z != first.z) zVar = true
        }
        return booleanArrayOf(xVar, yVar, zVar)
    }

    private fun axesToByte(axes: BooleanArray): Int {
        // [x, y, z] -> bits 0,1,2
        var b = 0
        if (axes[0]) b = b or 1
        if (axes[1]) b = b or 2
        if (axes[2]) b = b or 4
        return b
    }
    private fun byteToAxes(b: Byte): BooleanArray {
        val v = b.toInt()
        return booleanArrayOf((v and 1) != 0, (v and 2) != 0, (v and 4) != 0)
    }
}