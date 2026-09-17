package com.example.checkboxticker

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Reads whatever words are in a picture, on the phone itself. */
object Ocr {

    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun read(bitmap: Bitmap, done: (String?) -> Unit) {
        try {
            reader.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val text = result.text.replace('\n', ' ').trim()
                    done(if (text.isEmpty()) null else text)
                }
                .addOnFailureListener { error ->
                    Log.e(ScreenService.TAG, "could not read the text", error)
                    done(null)
                }
        } catch (t: Throwable) {
            Log.e(ScreenService.TAG, "text reading failed", t)
            done(null)
        }
    }
}
