package com.example.detector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.Executors

val GROUP_COLORS = intArrayOf(0xFF1E90FF.toInt(), 0xFFFFD400.toInt(), 0xFFFF2020.toInt(), 0xFF00E000.toInt())
val GROUP_NAMES = arrayOf("أشخاص", "دراجات", "حيوانات", "سيارات")

class Box(
    val g: Int, val name: String, val score: Float,
    val tl: Float, val tt: Float, val tr: Float, val tb: Float
) {
    var l = tl
    var t = tt
    var r = tr
    var b = tb
}

class OverlayView(c: Context) : View(c) {
    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); textSize = 15 * dp; isFakeBoldText = true
    }
    private val hudTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    @Volatile private var boxes: List<Box> = emptyList()
    @Volatile private var iw = 1
    @Volatile private var ih = 1

    private fun iou(a: Box, n: Box): Float {
        val x1 = maxOf(a.l, n.tl)
        val y1 = maxOf(a.t, n.tt)
        val x2 = minOf(a.r, n.tr)
        val y2 = minOf(a.b, n.tb)
        val i = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val u = (a.r - a.l) * (a.b - a.t) + (n.tr - n.tl) * (n.tb - n.tt) - i
        return if (u <= 0f) 0f else i / u
    }

    fun set(news: List<Box>, w: Int, h: Int) {
        val old = boxes
        val used = BooleanArray(old.size)
        val out = ArrayList<Box>()
        for (n in news) {
            var bi = -1
            var best = 0.15f
            for (i in old.indices) {
                if (used[i] || old[i].g != n.g) continue
                val v = iou(old[i], n)
                if (v > best) { best = v; bi = i }
            }
            if (bi >= 0) {
                used[bi] = true
                n.l = old[bi].l; n.t = old[bi].t; n.r = old[bi].r; n.b = old[bi].b
            }
            out.add(n)
        }
        iw = w; ih = h
        boxes = out
        postInvalidateOnAnimation()
    }

    override fun onDraw(cv: Canvas) {
        val list = boxes
        val s = maxOf(width.toFloat() / iw, height.toFloat() / ih)
        val ox = (width - iw * s) / 2f
        val oy = (height - ih * s) / 2f
        val cnt = IntArray(4)

        for (bx in list) {
            bx.l += (bx.tl - bx.l) * 0.5f
            bx.t += (bx.tt - bx.t) * 0.5f
            bx.r += (bx.tr - bx.r) * 0.5f
            bx.b += (bx.tb - bx.b) * 0.5f
            cnt[bx.g]++

            val l = bx.l * s + ox
            val t = bx.t * s + oy
            val r = bx.r * s + ox
            val b = bx.b * s + oy
            val col = GROUP_COLORS[bx.g]

            line.color = col
            line.alpha = 110
            line.strokeWidth = 2 * dp
            cv.drawRect(l, t, r, b, line)

            line.alpha = 255
            line.strokeWidth = 5 * dp
            val k = minOf(r - l, b - t) * 0.25f
            path.reset()
            path.moveTo(l, t + k); path.lineTo(l, t); path.lineTo(l + k, t)
            path.moveTo(r - k, t); path.lineTo(r, t); path.lineTo(r, t + k)
            path.moveTo(r, b - k); path.lineTo(r, b); path.lineTo(r - k, b)
            path.moveTo(l + k, b); path.lineTo(l, b); path.lineTo(l, b - k)
            cv.drawPath(path, line)

            val label = bx.name + " " + (bx.score * 100).toInt() + "%"
            val tw = txt.measureText(label) + 12 * dp
            val th = 24 * dp
            val ly = if (t > th) t - th else t
            fill.color = col
            cv.drawRect(l, ly, l + tw, ly + th, fill)
            cv.drawText(label, l + 6 * dp, ly + th - 7 * dp, txt)
        }

        val hh = 44 * dp
        fill.color = 0xB3000000.toInt()
        cv.drawRect(0f, height - hh, width.toFloat(), height.toFloat(), fill)
        for (i in 0..3) {
            hudTxt.color = GROUP_COLORS[i]
            cv.drawText(
                GROUP_NAMES[i] + ": " + cnt[i],
                width * (i + 0.5f) / 4f, height - hh / 2 + 6 * dp, hudTxt
            )
        }

        if (list.isNotEmpty()) postInvalidateOnAnimation()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var overlay: OverlayView
    private lateinit var preview: PreviewView
    private var detector: ObjectDetector? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var lastTs = 0L
    @Volatile private var iw = 1
    @Volatile private var ih = 1
    private val group = HashMap<String, Int>()
    private val ar = mapOf(
        "person" to "إنسان", "bicycle" to "دراجة", "motorcycle" to "دراجة نارية",
        "car" to "سيارة", "truck" to "شاحنة", "bus" to "حافلة",
        "bird" to "طائر", "cat" to "قطة", "dog" to "كلب", "horse" to "حصان",
        "sheep" to "خروف", "cow" to "بقرة", "elephant" to "فيل", "bear" to "دب",
        "zebra" to "حمار وحشي", "giraffe" to "زرافة"
    )

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) start()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        listOf("person").forEach { group[it] = 0 }
        listOf("bicycle", "motorcycle").forEach { group[it] = 1 }
        listOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
            .forEach { group[it] = 2 }
        listOf("car", "truck", "bus").forEach { group[it] = 3 }

        preview = PreviewView(this)
        overlay = OverlayView(this)
        val root = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
        else request.launch(Manifest.permission.CAMERA)
    }

    private fun start() {
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setScoreThreshold(0.5f)
            .setMaxResults(10)
            .setCategoryAllowlist(group.keys.toList())
            .setResultListener { res, _ -> onResult(res) }
            .build()
        detector = ObjectDetector.createFromOptions(this, opts)

        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val prov = fut.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val ia = ImageAnalysis.Builder()
                .setTargetResolution(if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) Size(640, 480) else Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            ia.setAnalyzer(exec) { analyze(it) }
            prov.unbindAll()
            prov.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ia)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        val det = detector
        if (det == null) { img.close(); return }
        val raw = img.toBitmap()
        val rot = img.imageInfo.rotationDegrees
        img.close()
        val bmp: Bitmap = if (rot == 0) raw else {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
        iw = bmp.width
        ih = bmp.height
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        try { det.detectAsync(BitmapImageBuilder(bmp).build(), ts) } catch (e: Exception) {}
    }

    private fun onResult(res: ObjectDetectorResult) {
        val list = ArrayList<Box>()
        for (d in res.detections()) {
            val c = d.categories()[0]
            val g = group[c.categoryName()] ?: continue
            val r = d.boundingBox()
            list.add(Box(g, ar[c.categoryName()] ?: c.categoryName(), c.score(), r.left, r.top, r.right, r.bottom))
        }
        overlay.set(list, iw, ih)
    }

    override fun onDestroy() {
        super.onDestroy()
        val d = detector; detector = null; d?.close()
        exec.shutdown()
    }
}
