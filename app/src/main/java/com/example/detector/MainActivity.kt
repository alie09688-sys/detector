package com.example.detector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.Executors

class OverlayView(c: Context) : View(c) {
    private val p = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f }
    @Volatile private var data: Triple<List<Pair<RectF, Int>>, Int, Int>? = null

    fun set(list: List<Pair<RectF, Int>>, w: Int, h: Int) {
        data = Triple(list, w, h)
        postInvalidate()
    }

    override fun onDraw(cv: Canvas) {
        val d = data ?: return
        val (list, w, h) = d
        val s = maxOf(width.toFloat() / w, height.toFloat() / h)
        val ox = (width - w * s) / 2f
        val oy = (height - h * s) / 2f
        for ((r, col) in list) {
            p.color = col
            cv.drawRect(r.left * s + ox, r.top * s + oy, r.right * s + ox, r.bottom * s + oy, p)
        }
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
    private val colors = HashMap<String, Int>()

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) start()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        listOf("person").forEach { colors[it] = 0xFF1E90FF.toInt() }
        listOf("bicycle", "motorcycle").forEach { colors[it] = 0xFFFFD400.toInt() }
        listOf("car", "truck", "bus").forEach { colors[it] = 0xFF00E000.toInt() }
        listOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
            .forEach { colors[it] = 0xFFFF2020.toInt() }

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
            .setCategoryAllowlist(colors.keys.toList())
            .setResultListener { res, _ -> onResult(res) }
            .build()
        detector = ObjectDetector.createFromOptions(this, opts)

        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val prov = fut.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val ia = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
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
        val bmp = img.toBitmap()
        val rot = img.imageInfo.rotationDegrees
        img.close()
        iw = if (rot % 180 == 0) bmp.width else bmp.height
        ih = if (rot % 180 == 0) bmp.height else bmp.width
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts
        val opt = ImageProcessingOptions.builder().setRotationDegrees(rot).build()
        det.detectAsync(BitmapImageBuilder(bmp).build(), opt, ts)
    }

    private fun onResult(res: ObjectDetectorResult) {
        val list = ArrayList<Pair<RectF, Int>>()
        for (d in res.detections()) {
            val name = d.categories()[0].categoryName()
            val col = colors[name] ?: continue
            list.add(Pair(d.boundingBox(), col))
        }
        overlay.set(list, iw, ih)
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        exec.shutdown()
    }
}
