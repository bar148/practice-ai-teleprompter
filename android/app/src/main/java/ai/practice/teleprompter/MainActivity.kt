package ai.practice.teleprompter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * מסך אחד: המצלמה הקדמית מאחור, ומעליה WebView שקוף עם הטלפרומפטר (אותו index.html של המחשב).
 * הממשק מדבר עם הטלפון דרך window.AndroidTP, והטלפון עונה דרך window.tpNative.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var preview: PreviewView
    private lateinit var speech: SpeechEngine
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraWanted = false
    private var videoQuality = "fhd"
    private var afterPermission: (() -> Unit)? = null
    private var insetTop = 0f
    private var insetBottom = 0f
    private fun sendInsets() = js("setInsets($insetTop, $insetBottom)")
    private val logFile by lazy { File(filesDir, "teleprompter.log").also { if (it.length() > 400_000) it.delete() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)   // מסך מלא, גם מתחת לשורת הסטטוס
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        log("app start ${BuildConfig.VERSION_NAME} android ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // מאפשר WebView שקוף מעל
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.INVISIBLE
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { sendInsets() }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) log("js error: ${m.message()} @${m.lineNumber()}")
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "AndroidTP")
        }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(preview, match)
        root.addView(web, FrameLayout.LayoutParams(match))
        setContentView(root)

        // מתחת לשורת הסטטוס ולמעלה עד המצלמה: הממשק מקבל את הגבולות כמשתני CSS
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val d = resources.displayMetrics.density
            insetTop = bars.top / d; insetBottom = bars.bottom / d
            sendInsets()
            insets
        }

        speech = SpeechEngine(this,
            onText = { t -> js("onText(${JSONObject.quote(t)})") },
            onStatus = { s, m -> js("onStatus(${JSONObject.quote(s)}, ${JSONObject.quote(m)})") },
            log = ::log)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { js("onBack()") }
        })

        web.loadUrl("file:///android_asset/index.html")
    }

    private fun js(call: String) {
        runOnUiThread { web.evaluateJavascript("window.tpNative && window.tpNative.$call", null) }
    }

    fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        try { logFile.appendText("$ts $line\n") } catch (_: Exception) {}
    }

    // ---------------- הרשאות ----------------
    private fun hasPerms() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun withPerms(then: () -> Unit) {
        if (hasPerms()) return then()
        afterPermission = then
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        log("permissions: ${permissions.zip(grantResults.toTypedArray()).joinToString { "${it.first.substringAfterLast('.')}=${it.second == 0}" }}")
        val then = afterPermission
        afterPermission = null
        if (hasPerms()) then?.invoke()
        else js("onStatus('error', ${JSONObject.quote("צריך לאשר גישה למצלמה ולמיקרופון (הגדרות > אפליקציות > טלפרומפטר > הרשאות)")})")
    }

    // ---------------- מצלמה והקלטה ----------------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!cameraWanted) return@addListener
            val provider = future.get()
            cameraProvider = provider
            val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            // האיכות הכי גבוהה שהמצלמה הקדמית באמת תומכת בה, עם קצב נתונים גבוה (ברירת המחדל של הטלפון לפעמים נמוכה)
            val front = CameraSelector.DEFAULT_FRONT_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
            val supported = front?.let { Recorder.getVideoCapabilities(it).getSupportedQualities(DynamicRange.SDR) } ?: emptyList()
            val wanted = if (videoQuality == "uhd") listOf(Quality.UHD, Quality.FHD, Quality.HD) else listOf(Quality.FHD, Quality.HD)
            val pick = wanted.firstOrNull { it in supported } ?: Quality.HIGHEST
            val bitrate = if (pick == Quality.UHD) 35_000_000 else 16_000_000
            log("front camera qualities: ${supported.joinToString { qName(it) }} -> using ${qName(pick)} @ ${bitrate / 1_000_000}Mbps")
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(pick, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(bitrate)
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, prev, vc)
                preview.visibility = View.VISIBLE
                log("camera bound")
            } catch (e: Exception) {
                log("camera bind failed: $e")
                js("onStatus('error', ${JSONObject.quote("לא הצלחתי לפתוח את המצלמה")})")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        recording?.stop()
        recording = null
        cameraProvider?.unbindAll()
        videoCapture = null
        preview.visibility = View.INVISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: run { js("onRecording('error', ${JSONObject.quote("המצלמה עוד לא מוכנה")})"); return }
        if (recording != null) return
        val name = "PracticeAI_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Practice AI")
        }
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, out)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> { log("recording started $name"); js("onRecording('started', '')") }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (ev.hasError()) {
                        log("recording error ${ev.error} ${ev.cause}")
                        js("onRecording('error', ${JSONObject.quote("שגיאה בשמירת הסרטון (${ev.error})")})")
                    } else {
                        val info = videoInfo(ev.outputResults.outputUri)
                        log("recording saved ${ev.outputResults.outputUri} $info")
                        js("onRecording('saved', ${JSONObject.quote(info)})")
                    }
                }
                else -> {}
            }
        }
    }

    private fun qName(q: Quality) = when (q) {
        Quality.UHD -> "4K"; Quality.FHD -> "FullHD"; Quality.HD -> "HD"; Quality.SD -> "SD"; else -> q.toString()
    }

    // רזולוציה וקצב נתונים של הסרטון שנשמר, למשל "1920x1080 15.8Mbps"
    private fun videoInfo(uri: Uri): String = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
        r.release()
        val (vw, vh) = if (rot % 180 != 0) h to w else w to h
        "${vw}x${vh} ${"%.1f".format(br / 1_000_000.0)}Mbps"
    } catch (e: Exception) { "unknown ($e)" }

    private fun saveText(fileName: String, content: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Practice AI")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            log("saved text $fileName")
            true
        } catch (e: Exception) {
            log("save text failed: $e"); false
        }
    }

    private fun shareLog() {
        val text = try { logFile.readText().takeLast(60_000) } catch (_: Exception) { "no log" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Teleprompter log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "שליחת לוג"))
    }

    override fun onPause() {
        super.onPause()
        if (recording == null) speech.stop()
    }

    override fun onDestroy() {
        speech.stop(notify = false)
        super.onDestroy()
    }

    // ---------------- הגשר לממשק ----------------
    inner class Bridge {
        @JavascriptInterface
        fun info(): String = JSONObject().apply {
            put("platform", "android")
            put("sdk", Build.VERSION.SDK_INT)
            put("sharedAudio", Build.VERSION.SDK_INT >= 33)
            put("recognizer", SpeechRecognizer.isRecognitionAvailable(this@MainActivity))
            put("version", BuildConfig.VERSION_NAME)
        }.toString()

        @JavascriptInterface
        fun camera(on: Boolean, quality: String) = runOnUiThread {
            cameraWanted = on
            videoQuality = quality
            if (on) withPerms { startCamera() } else stopCamera()
        }

        @JavascriptInterface
        fun startListening(lang: String, mode: String) = runOnUiThread { withPerms { speech.start(lang, mode) } }

        @JavascriptInterface
        fun stopListening() = runOnUiThread { speech.stop() }

        @JavascriptInterface
        fun startRecording() = runOnUiThread { withPerms { this@MainActivity.startRecording() } }

        @JavascriptInterface
        fun stopRecording() = runOnUiThread { recording?.stop() }

        @JavascriptInterface
        fun saveText(name: String, content: String): Boolean = this@MainActivity.saveText(name, content)

        @JavascriptInterface
        fun log(line: String) = this@MainActivity.log("ui: $line")

        @JavascriptInterface
        fun shareLog() = runOnUiThread { this@MainActivity.shareLog() }

        @JavascriptInterface
        fun exitApp() = runOnUiThread { finish() }
    }
}
