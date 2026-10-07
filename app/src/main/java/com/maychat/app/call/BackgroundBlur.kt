package com.maychat.app.call

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// =====================================================================
// "Làm mờ nền" of a video call.
//
// Sits between my camera and the call. For every camera picture it asks
// Google's on-phone person finder (ML Kit, no internet, nothing leaves the
// phone) which points belong to a person, and blurs everything else.
//
// The work is done on the picture's three colour planes directly (Y, U, V):
//   1. a small, strongly blurred copy of each plane is made;
//   2. each point of the result is the original where a person is, the
//      blurred copy elsewhere, and a mix of both along the edge.
// While one picture is being worked on, the camera pictures arriving in
// between are dropped (never sent unblurred).
// =====================================================================
class BackgroundBlur : VideoProcessor {

    @Volatile
    private var sink: VideoSink? = null

    @Volatile
    private var closed = false
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build(),
    )

    // Work buffers, kept between pictures (only used on the worker thread).
    private var yIn = ByteArray(0)
    private var uIn = ByteArray(0)
    private var vIn = ByteArray(0)
    private var nv21 = ByteArray(0)
    private var yOut = ByteArray(0)
    private var uOut = ByteArray(0)
    private var vOut = ByteArray(0)
    private var mask = FloatArray(0)
    private var smallY = IntArray(0)
    private var smallU = IntArray(0)
    private var smallV = IntArray(0)
    private var smallTmp = IntArray(0)

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {}

    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        if (closed) return
        // Still busy with the picture before: this one is left out.
        if (!busy.compareAndSet(false, true)) return
        val copy = try {
            frame.buffer.toI420()
        } catch (e: Throwable) {
            null
        }
        if (copy == null) {
            busy.set(false)
            return
        }
        val rotation = frame.rotation
        val time = frame.timestampNs
        try {
            worker.execute {
                try {
                    process(copy, rotation, time)
                } catch (e: Throwable) {
                    // This picture is lost; the next one is tried again.
                } finally {
                    copy.release()
                    busy.set(false)
                }
            }
        } catch (e: Throwable) {
            copy.release()
            busy.set(false)
        }
    }

    // Stops the work and frees the person finder.
    fun close() {
        closed = true
        sink = null
        runCatching { worker.shutdown() }
        runCatching { segmenter.close() }
    }

    private fun process(src: VideoFrame.I420Buffer, rotation: Int, timeNs: Long) {
        val w = src.width
        val h = src.height
        // The person finder needs even sizes (cameras always give them).
        if (w < 16 || h < 16 || w % 2 != 0 || h % 2 != 0) return
        val cw = w / 2
        val ch = h / 2

        if (yIn.size != w * h) {
            yIn = ByteArray(w * h)
            yOut = ByteArray(w * h)
            uIn = ByteArray(cw * ch)
            vIn = ByteArray(cw * ch)
            uOut = ByteArray(cw * ch)
            vOut = ByteArray(cw * ch)
            nv21 = ByteArray(w * h + 2 * cw * ch)
        }

        // 1. Copy the three planes out of the camera picture.
        readPlane(src.dataY, src.strideY, w, h, yIn)
        readPlane(src.dataU, src.strideU, cw, ch, uIn)
        readPlane(src.dataV, src.strideV, cw, ch, vIn)

        // 2. Ask where the person is. The finder wants the "NV21" layout:
        //    the Y plane, then V and U taking turns.
        System.arraycopy(yIn, 0, nv21, 0, w * h)
        var at = w * h
        for (i in 0 until cw * ch) {
            nv21[at++] = vIn[i]
            nv21[at++] = uIn[i]
        }
        val image = InputImage.fromByteArray(nv21, w, h, rotation, InputImage.IMAGE_FORMAT_NV21)
        val found = Tasks.await(segmenter.process(image), 800, TimeUnit.MILLISECONDS)
        val mw = found.width
        val mh = found.height
        if (mw <= 0 || mh <= 0) return
        if (mask.size != mw * mh) mask = FloatArray(mw * mh)
        val maskBuffer = found.buffer
        maskBuffer.rewind()
        maskBuffer.asFloatBuffer().get(mask, 0, mw * mh)

        // 3. Small blurred copies: 1/8 of the size for Y, and the same
        //    number of points for U and V (which are already half size).
        val sw = w / 8
        val sh = h / 8
        if (sw < 2 || sh < 2) return
        if (smallY.size != sw * sh) {
            smallY = IntArray(sw * sh)
            smallU = IntArray(sw * sh)
            smallV = IntArray(sw * sh)
            smallTmp = IntArray(sw * sh)
        }
        shrink(yIn, w, 8, smallY, sw, sh)
        shrink(uIn, cw, 4, smallU, sw, sh)
        shrink(vIn, cw, 4, smallV, sw, sh)
        repeat(2) {
            boxBlur(smallY, sw, sh)
            boxBlur(smallU, sw, sh)
            boxBlur(smallV, sw, sh)
        }

        // 4. The person finder sees the picture upright; the camera picture
        //    may lie on its side. For each column and row of the camera
        //    picture: which point of the finder's answer belongs to it.
        //    (index in the answer = colPart[x] + rowPart[y])
        val colPart = IntArray(w)
        val rowPart = IntArray(h)
        when (((rotation % 360) + 360) % 360) {
            90 -> {
                for (x in 0 until w) colPart[x] = (x * mh / w).coerceIn(0, mh - 1) * mw
                for (y in 0 until h) rowPart[y] = ((h - 1 - y) * mw / h).coerceIn(0, mw - 1)
            }
            180 -> {
                for (x in 0 until w) colPart[x] = ((w - 1 - x) * mw / w).coerceIn(0, mw - 1)
                for (y in 0 until h) rowPart[y] = ((h - 1 - y) * mh / h).coerceIn(0, mh - 1) * mw
            }
            270 -> {
                for (x in 0 until w) colPart[x] = ((w - 1 - x) * mh / w).coerceIn(0, mh - 1) * mw
                for (y in 0 until h) rowPart[y] = (y * mw / h).coerceIn(0, mw - 1)
            }
            else -> {
                for (x in 0 until w) colPart[x] = (x * mw / w).coerceIn(0, mw - 1)
                for (y in 0 until h) rowPart[y] = (y * mh / h).coerceIn(0, mh - 1) * mw
            }
        }

        // Where each point falls in the small copy, for a smooth enlargement:
        // the point to the left / above, and how far towards the next (0..256).
        val x0 = IntArray(w)
        val xw = IntArray(w)
        for (x in 0 until w) {
            val pos = (((x * 2 + 1) * 256) / 16 - 128).coerceIn(0, (sw - 1) * 256)
            x0[x] = (pos shr 8).coerceAtMost(sw - 2)
            xw[x] = pos - x0[x] * 256
        }
        val y0 = IntArray(h)
        val yw = IntArray(h)
        for (y in 0 until h) {
            val pos = (((y * 2 + 1) * 256) / 16 - 128).coerceIn(0, (sh - 1) * 256)
            y0[y] = (pos shr 8).coerceAtMost(sh - 2)
            yw[y] = pos - y0[y] * 256
        }

        // 5. Y plane: original on the person, blurred elsewhere.
        for (y in 0 until h) {
            val row = y * w
            val maskRow = rowPart[y]
            val top = y0[y] * sw
            val bottom = top + sw
            val wy = yw[y]
            for (x in 0 until w) {
                val person = personShare(mask[maskRow + colPart[x]])
                val original = yIn[row + x].toInt() and 0xFF
                if (person >= 256) {
                    yOut[row + x] = original.toByte()
                } else {
                    val left = x0[x]
                    val wx = xw[x]
                    val a = smallY[top + left] * (256 - wx) + smallY[top + left + 1] * wx
                    val b = smallY[bottom + left] * (256 - wx) + smallY[bottom + left + 1] * wx
                    val blurred = (a * (256 - wy) + b * wy) shr 16
                    yOut[row + x] = ((original * person + blurred * (256 - person)) shr 8).toByte()
                }
            }
        }

        // 6. U and V planes (half size): the nearest point of the small copy.
        for (cy in 0 until ch) {
            val row = cy * cw
            val maskRow = rowPart[cy * 2]
            val smallRow = (cy / 4).coerceAtMost(sh - 1) * sw
            for (cx in 0 until cw) {
                val person = personShare(mask[maskRow + colPart[cx * 2]])
                val i = row + cx
                if (person >= 256) {
                    uOut[i] = uIn[i]
                    vOut[i] = vIn[i]
                } else {
                    val s = smallRow + (cx / 4).coerceAtMost(sw - 1)
                    val u = uIn[i].toInt() and 0xFF
                    val v = vIn[i].toInt() and 0xFF
                    uOut[i] = ((u * person + smallU[s] * (256 - person)) shr 8).toByte()
                    vOut[i] = ((v * person + smallV[s] * (256 - person)) shr 8).toByte()
                }
            }
        }

        // 7. Hand the new picture to the call.
        val target = sink ?: return
        val out = JavaI420Buffer.allocate(w, h)
        writePlane(yOut, w, h, out.dataY, out.strideY)
        writePlane(uOut, cw, ch, out.dataU, out.strideU)
        writePlane(vOut, cw, ch, out.dataV, out.strideV)
        val result = VideoFrame(out, rotation, timeNs)
        try {
            if (!closed) target.onFrame(result)
        } finally {
            result.release()
        }
    }

    // How much of a point is "person", from the finder's certainty (0..1):
    // 0 = background, 256 = person, in between along the edge.
    private fun personShare(certainty: Float): Int = when {
        certainty >= 0.7f -> 256
        certainty <= 0.3f -> 0
        else -> ((certainty - 0.3f) * 640f).toInt()
    }

    private fun readPlane(from: java.nio.ByteBuffer, stride: Int, w: Int, h: Int, into: ByteArray) {
        val buffer = from.duplicate()
        for (row in 0 until h) {
            buffer.position(row * stride)
            buffer.get(into, row * w, w)
        }
    }

    private fun writePlane(from: ByteArray, w: Int, h: Int, into: java.nio.ByteBuffer, stride: Int) {
        val buffer = into.duplicate()
        for (row in 0 until h) {
            buffer.position(row * stride)
            buffer.put(from, row * w, w)
        }
    }

    // Makes a small copy: each point is the average of a block of
    // "factor" x "factor" points of the plane.
    private fun shrink(plane: ByteArray, planeWidth: Int, factor: Int, into: IntArray, sw: Int, sh: Int) {
        val area = factor * factor
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                var sum = 0
                val startX = sx * factor
                for (dy in 0 until factor) {
                    val row = (sy * factor + dy) * planeWidth + startX
                    for (dx in 0 until factor) sum += plane[row + dx].toInt() and 0xFF
                }
                into[sy * sw + sx] = sum / area
            }
        }
    }

    // Blurs the small copy in place: every point becomes the average of
    // its neighbours two steps away in each direction.
    private fun boxBlur(data: IntArray, w: Int, h: Int) {
        val tmp = smallTmp
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0
                for (d in -2..2) sum += data[row + (x + d).coerceIn(0, w - 1)]
                tmp[row + x] = sum / 5
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (d in -2..2) sum += tmp[(y + d).coerceIn(0, h - 1) * w + x]
                data[y * w + x] = sum / 5
            }
        }
    }
}
