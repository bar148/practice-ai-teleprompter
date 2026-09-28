package ai.practice.teleprompter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.sqrt

/**
 * זיהוי דיבור באנדרואיד עם המנוע של גוגל שמובנה בטלפון.
 *
 * שני מצבים:
 *  - shared (אנדרואיד 13 ומעלה): האפליקציה פותחת את המיקרופון בעצמה ומזרימה את הקול לזיהוי
 *    (EXTRA_AUDIO_SOURCE). ככה הזיהוי לא "מתחרה" על המיקרופון עם הקלטת הווידאו.
 *  - direct: שירות הזיהוי פותח את המיקרופון בעצמו (הדרך הרגילה). משמש גם כגיבוי אוטומטי.
 */
class SpeechEngine(
    private val act: Activity,
    private val onText: (String) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lang = "he-IL"
    private var shared = false
    private var lastFinal = ""
    private var sessionStartedAt = 0L
    private var lastResultAt = 0L
    private var loudSince = 0L

    // מיקרופון משותף
    private var record: AudioRecord? = null
    private var pump: Thread? = null
    @Volatile private var pipeOut: FileOutputStream? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var pipeRead: ParcelFileDescriptor? = null
    @Volatile private var level = 0.0

    private val muted = mutableListOf<Int>()

    fun start(language: String, mode: String) {
        stop(notify = false)
        lang = language
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            onStatus("error", "אין בטלפון שירות זיהוי דיבור - צריך את אפליקציית Google")
            return
        }
        shared = mode != "direct" && Build.VERSION.SDK_INT >= 33
        listening = true
        lastFinal = ""
        muteBeeps(true)
        if (shared && !startSharedAudio()) shared = false
        log("speech start lang=$lang shared=$shared sdk=${Build.VERSION.SDK_INT}")
        startSession()
        onStatus("listening", if (shared) "מקשיב (מיקרופון משותף)" else "מקשיב")
        main.postDelayed(watchdog, 4000)
    }

    fun stop(notify: Boolean = true) {
        val was = listening
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { try { it.cancel() } catch (_: Exception) {}; try { it.destroy() } catch (_: Exception) {} }
        recognizer = null
        stopSharedAudio()
        muteBeeps(false)
        if (was) log("speech stop")
        if (was && notify) onStatus("ready", "מושהה")
    }

    // ---------------- סשן זיהוי ----------------
    private fun startSession() {
        if (!listening) return
        recognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        val r = SpeechRecognizer.createSpeechRecognizer(act)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }
        if (shared && Build.VERSION.SDK_INT >= 33) {
            val pipe = ParcelFileDescriptor.createPipe()
            closePipe()
            pipeRead = pipe[0]
            pipeWrite = pipe[1]
            pipeOut = FileOutputStream(pipe[1].fileDescriptor)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        sessionStartedAt = System.currentTimeMillis()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            log("startListening failed: $e")
            restart(500)
        }
    }

    private fun restart(delayMs: Long) {
        if (!listening) return
        main.postDelayed({ if (listening) startSession() }, delayMs)
    }

    // אם במצב משותף יש קול אבל אין תוצאות - כנראה שהשירות בטלפון לא תומך בזה. עוברים למצב הרגיל.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!listening) return
            val now = System.currentTimeMillis()
            if (shared) {
                if (level > 0.02) { if (loudSince == 0L) loudSince = now } else if (level < 0.005) loudSince = 0L
                if (loudSince > 0 && now - loudSince > 7000 && now - lastResultAt > 7000) {
                    log("shared mode: audio but no results for 7s -> fallback to direct")
                    fallbackToDirect()
                    return
                }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun fallbackToDirect() {
        stopSharedAudio()
        shared = false
        onStatus("listening", "מקשיב (מיקרופון רגיל)")
        startSession()
        main.postDelayed(watchdog, 1000)
    }

    private fun emit(partial: String) {
        lastResultAt = System.currentTimeMillis()
        val text = (lastFinal + " " + partial).trim()
        if (text.isNotEmpty()) onText(text)
    }

    private fun remember(final: String) {
        // שומרים רק את סוף המשפט הקודם - מספיק להתאמה ברצף
        val words = (lastFinal + " " + final).trim().split(Regex("\\s+"))
        lastFinal = words.takeLast(12).joinToString(" ")
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (!shared) level = if (rmsdB > 2f) 0.05 else 0.0 }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = first(partialResults)
            if (t.isNotEmpty()) emit(t)
        }

        override fun onResults(results: Bundle?) {
            val t = first(results)
            if (t.isNotEmpty()) { emit(t); remember(t) }
            restart(60)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val t = first(segmentResults)
            if (t.isNotEmpty()) { emit(t); remember(t) }
        }

        override fun onEndOfSegmentedSession() { restart(60) }

        override fun onError(error: Int) {
            if (!listening) return
            val age = System.currentTimeMillis() - sessionStartedAt
            log("recognizer error $error after ${age}ms (shared=$shared)")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(400)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onStatus("error", "אין הרשאה למיקרופון")
                    stop(notify = false)
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    onStatus("error", "אין חיבור לאינטרנט - זיהוי הדיבור צריך רשת")
                    restart(2000)
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (shared) fallbackToDirect()
                    else { onStatus("error", "השפה $lang לא נתמכת בזיהוי הדיבור בטלפון הזה"); stop(notify = false) }
                }
                else -> {
                    // שגיאה מיידית במצב משותף = כנראה שהשירות לא תומך בהזרמת קול מהאפליקציה
                    if (shared && age < 1500) fallbackToDirect() else restart(300)
                }
            }
        }
    }

    // ---------------- מיקרופון משותף ----------------
    @SuppressLint("MissingPermission")
    private fun startSharedAudio(): Boolean {
        return try {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); log("AudioRecord init failed"); return false }
            rec.startRecording()
            record = rec
            pump = Thread {
                val buf = ShortArray(1600)
                val bytes = ByteArray(3200)
                while (record === rec && !Thread.currentThread().isInterrupted) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        sum += (s * s).toDouble()
                        bytes[2 * i] = (s and 0xff).toByte()
                        bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                    }
                    level = sqrt(sum / n) / 32768.0
                    try { pipeOut?.write(bytes, 0, 2 * n) } catch (_: IOException) { /* הסשן התחלף - ממשיכים */ }
                }
            }.also { it.isDaemon = true; it.start() }
            true
        } catch (e: Exception) {
            log("shared audio failed: $e")
            false
        }
    }

    private fun stopSharedAudio() {
        val rec = record
        record = null
        pump?.interrupt()
        pump = null
        rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        closePipe()
        level = 0.0
    }

    private fun closePipe() {
        try { pipeOut?.close() } catch (_: Exception) {}
        try { pipeWrite?.close() } catch (_: Exception) {}
        try { pipeRead?.close() } catch (_: Exception) {}
        pipeOut = null; pipeWrite = null; pipeRead = null
    }

    // ---------------- השתקת הצפצוף של הזיהוי (שלא ייכנס להקלטה) ----------------
    private fun muteBeeps(on: Boolean) {
        val streams = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        if (on) {
            for (s in streams) try {
                if (!audio.isStreamMute(s)) { audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); muted.add(s) }
            } catch (_: Exception) {}
        } else {
            for (s in muted) try { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
            muted.clear()
        }
    }

    companion object { const val SAMPLE_RATE = 16000 }
}
