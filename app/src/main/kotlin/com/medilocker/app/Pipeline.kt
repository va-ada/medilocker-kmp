package com.medilocker.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.medilocker.domain.DocumentExtraction
import com.medilocker.domain.DocumentType
import com.medilocker.domain.Extraction
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Capture → OCR → classify → extract, all on-device.
 *
 * OCR is ML Kit's *bundled* recognizers — the models ship inside the APK, so
 * this works with no Play Services and no network. Latin runs first; if the
 * image looks like it carries Devanagari, that recognizer runs too and the
 * results are concatenated. Two passes cost ~a second and keep code-mixed
 * prescriptions readable.
 */
object Pipeline {

    enum class Stage(val label: String) {
        ENCRYPTING("Encrypting"),
        READING("Reading the text"),
        UNDERSTANDING("Finding medicines and results"),
        DONE("Done")
    }

    suspend fun ocr(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latin = recognize(image, TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
        val deva = runCatching {
            recognize(image, TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()))
        }.getOrDefault("")
        // Keep the richer result as the base; append the other script's lines
        // that the base missed, so neither script is dropped.
        val base = if (latin.length >= deva.length) latin else deva
        val extra = (if (base === latin) deva else latin).lines()
            .filter { it.isNotBlank() && it !in base }
        return normaliseDigits((base.lines() + extra).joinToString("\n").trim())
    }

    /**
     * The Devanagari recognizer sometimes reads Western digits as Indic ones
     * (a real capture on the emulator produced U+09E6 BENGALI ZERO inside a
     * reference range). All Indic-script digits fold to ASCII before the text
     * reaches the extractor.
     */
    private fun normaliseDigits(text: String): String = buildString(text.length) {
        for (ch in text) {
            val v = Character.digit(ch, 10)
            append(if (v in 0..9 && ch.code > 0x7F) ('0' + v) else ch)
        }
    }

    private suspend fun recognize(
        image: InputImage,
        client: com.google.mlkit.vision.text.TextRecognizer
    ): String = suspendCoroutine { cont ->
        client.process(image)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun understand(text: String): DocumentExtraction {
        val type = Extraction.classify(text)
        return Extraction.extract("doc-" + UUID.randomUUID().toString().take(8), text, type)
    }

    /**
     * "Follow-up: after 2 weeks" → an actual date, anchored to the document's
     * own date. Returns null for SOS ("come if it gets worse") — a reminder
     * for "whenever" is noise.
     */
    fun followUpDate(rawText: String, documentDate: LocalDate?): LocalDate? {
        val anchor = documentDate ?: LocalDate.now()
        val m = Regex(
            """follow[\s-]?up\s*[:\-]?\s*(?:after|in)?\s*(\d+)\s*(day|week|month)s?""",
            RegexOption.IGNORE_CASE
        ).find(rawText) ?: return null
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].lowercase()) {
            "day" -> anchor.plusDays(n)
            "week" -> anchor.plusWeeks(n)
            else -> anchor.plusMonths(n)
        }
    }

    // ---- sample document ----------------------------------------------------

    /**
     * Renders a synthetic prescription to a bitmap and lets the REAL OCR read
     * it back. This exists so the full pipeline can be demonstrated and tested
     * on a device with no documents at hand — the text below follows the same
     * Indian prescription convention as the project's corpus.
     */
    fun samplePrescription(which: Int): Bitmap {
        val samples = listOf(
            """
            Dr. Anita Malhotra
            M.D. Medicine  Reg. No. MMC/26962
            City Care Polyclinic, Navi Mumbai
            Patient: Suresh Desai  Age/Sex: 62/M  Date: 12/07/2026
            Diagnosis: Type 2 Diabetes  [ICD-10: E119]

            Rx
            1. Tab. Glycomet (Metformin) 500mg - BD (1-0-1) x 30 days, after food
            2. Tab. Telma (Telmisartan) 40mg - OD (1-0-0) x 30 days, before food

            Advice: Regular exercise advised.
            Follow-up: after 2 weeks
            """,
            """
            Sunrise Diagnostics, Mumbai
            Patient: Suresh Desai  Age/Sex: 62/M  Date: 30/07/2026
            Result Report - specimen serum

            HbA1c 8.2 % (4.0-5.6)
            Fasting Glucose 148 mg/dL (70-100)
            Haemoglobin 13.4 g/dL (12.0-15.5)
            Creatinine 1.1 mg/dL (0.7-1.3)
            """,
            """
            Dr. Sunita Pillai
            General Physician  Reg. No. MMC/41085
            Sunrise Clinic, Thane
            Patient: Suresh Desai  Age/Sex: 62/M  Date: 05/08/2026
            Diagnosis: Viral Fever

            Rx
            1. Tab. Dolo 650 (Paracetamol) 650mg - TDS x 5 days, after food
            2. Tab. Calpol (Paracetamol) 650mg - SOS x 5 days

            Advice: Plenty of oral fluids.
            Follow-up: after 1 week
            """
        )
        val text = samples[which % samples.size].trimIndent().trim()
        // Sized so the longest Rx line fits: monospace advance is ~0.6em, and the
        // longest sample line is ~70 chars. 70 x 24 x 0.6 = ~1010px inside 1200.
        val w = 1200
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 24f
            typeface = Typeface.MONOSPACE
        }
        val lines = text.lines()
        val lineH = 40
        val bmp = Bitmap.createBitmap(w, lines.size * lineH + 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        lines.forEachIndexed { i, ln -> canvas.drawText(ln, 48f, 80f + i * lineH, paint) }
        return bmp
    }
}
