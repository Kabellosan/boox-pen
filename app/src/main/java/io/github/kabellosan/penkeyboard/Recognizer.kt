package io.github.kabellosan.penkeyboard

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea

/** One pen stroke: x, y in view pixels, t in milliseconds. */
class Stroke {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val ts = ArrayList<Long>()
    fun add(x: Float, y: Float, t: Long) { xs += x; ys += y; ts += t }
    val size get() = xs.size
}

/**
 * ML Kit Digital Ink, on device. Holds one recognizer per language
 * ("da" and "en") so a word can be read by both and the user can pick.
 */
class Recognizer {

    val languages = listOf("da", "en")

    private val models = languages.associateWith { tag ->
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
            ?: error("ML Kit has no handwriting model for '$tag'")
        DigitalInkRecognitionModel.builder(id).build()
    }
    private val recognizers = HashMap<String, DigitalInkRecognizer>()
    private val manager = RemoteModelManager.getInstance()

    /** Downloads any missing model (any network, not only Wi-Fi). */
    fun downloadAll(): Task<Void> = Tasks.whenAll(models.values.map {
        manager.download(it, DownloadConditions.Builder().build())
    })

    fun isDownloaded(tag: String): Task<Boolean> = manager.isModelDownloaded(models.getValue(tag))

    private fun recognizer(tag: String): DigitalInkRecognizer =
        recognizers.getOrPut(tag) {
            DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(models.getValue(tag)).build()
            )
        }

    /**
     * Recognizes [strokes] with one language. [preContext] is the text just before
     * the cursor; ML Kit uses it to guess spacing and the next word better.
     * Calls [onResult] on the main thread with the candidates, best first.
     */
    fun recognize(
        tag: String,
        strokes: List<Stroke>,
        width: Float,
        height: Float,
        preContext: String,
        onResult: (List<String>) -> Unit
    ) {
        val ink = Ink.builder().apply {
            for (s in strokes) {
                if (s.size == 0) continue
                val b = Ink.Stroke.builder()
                for (i in 0 until s.size) b.addPoint(Ink.Point.create(s.xs[i], s.ys[i], s.ts[i]))
                addStroke(b.build())
            }
        }.build()
        val context = RecognitionContext.builder()
            .setPreContext(preContext)
            .setWritingArea(WritingArea(width, height))
            .build()
        recognizer(tag).recognize(ink, context)
            .addOnSuccessListener { r -> onResult(r.candidates.map { it.text }) }
            .addOnFailureListener { e ->
                Log.w(PenApp.TAG, "Recognition ($tag) failed: ${e.message}")
                onResult(emptyList())
            }
    }

    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}
