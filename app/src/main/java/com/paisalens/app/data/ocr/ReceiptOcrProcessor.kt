package com.paisalens.app.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class ReceiptOcrProcessor {
    suspend fun recognize(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result -> continuation.resumeWith(Result.success(result.text)) }
                    .addOnFailureListener(continuation::resumeWithException)
            }
        } finally {
            recognizer.close()
        }
    }
}
