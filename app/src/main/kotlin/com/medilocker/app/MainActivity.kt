package com.medilocker.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import com.medilocker.app.ui.*
import com.medilocker.crypto.CryptoException
import com.medilocker.domain.Artifacts
import com.medilocker.domain.DocumentExtraction
import com.medilocker.domain.DocumentType
import com.medilocker.domain.Extraction
import com.medilocker.domain.HealthDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

private sealed interface Screen {
    data object FirstRun : Screen
    data class ShowRecoveryCode(val code: String) : Screen
    data object Locked : Screen
    data object OfferBiometric : Screen
    data object Recover : Screen
    data object Home : Screen
    data object Capture : Screen
    data object Processing : Screen
    data class Review(val extraction: DocumentExtraction, val jpeg: ByteArray) : Screen
    data object Records : Screen
    data class RecordDetail(val id: String) : Screen
    data object Cabinet : Screen
}

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private lateinit var vaultManager: VaultManager
    private var repo: Repo? = null
    private lateinit var indications: Indications
    private lateinit var biometrics: BiometricGate

    // Compose state is owned here so activity-result callbacks can drive it.
    private val screen = mutableStateOf<Screen>(Screen.Locked)
    private val busy = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val digest = mutableStateOf(HealthDigest())
    private val processingStage = mutableStateOf(Pipeline.Stage.ENCRYPTING)
    private val processingPreview = mutableStateOf<Bitmap?>(null)
    private val cabinet = mutableStateOf<List<Repo.CabinetItem>>(emptyList())
    private val followUps = mutableStateOf<List<FollowUpAlert>>(emptyList())

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
            if (bmp != null) runPipeline(bmp) else screen.value = Screen.Capture
        }

    /**
     * Picking an image is genuinely fragmented, so this uses its own contract.
     *
     * On this handset (Samsung, Android 16) the picker is served by Google
     * Photos in a MULTI-select presentation. When the user taps Done with one
     * image, the result arrives in `Intent.clipData` and `Intent.data` is null.
     * Both `GetContent` and `PickVisualMedia` read only `Intent.data`, so the
     * selection came back as null and the app silently returned to the capture
     * screen with no error and no explanation.
     *
     * Reading clipData as well as data fixes it, and costs nothing on devices
     * that populate data normally.
     */
    private class PickImage : androidx.activity.result.contract.ActivityResultContract<Unit, android.net.Uri?>() {
        override fun createIntent(context: android.content.Context, input: Unit) =
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "mlk.pick",
                "resultCode=" + resultCode + " intent=" + intent +
                    " data=" + intent?.data + " clipItems=" + (intent?.clipData?.itemCount ?: -1)
            )
            if (resultCode != RESULT_OK || intent == null) return null
            intent.data?.let { return it }
            val clip = intent.clipData ?: return null
            return if (clip.itemCount > 0) clip.getItemAt(0).uri else null
        }
    }

    private val pickImage =
        registerForActivityResult(PickImage()) { uri ->
            if (uri != null) loadAndRun(uri) else screen.value = Screen.Capture
        }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Screenshots of health data are blocked in release builds. Debug keeps
        // them possible so the app can be driven and verified over adb.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        vaultManager = VaultManager(applicationContext)
        indications = Indications(applicationContext)
        biometrics = BiometricGate(vaultManager.dir, vaultManager.primitives)
        screen.value = if (vaultManager.hasVault()) Screen.Locked else Screen.FirstRun

        setContent {
            MediLockerTheme {
                // Paints the themed ground behind every screen. Without it, screens
                // that are not built on a Scaffold show the WINDOW background from
                // themes.xml instead — which produced dark-theme components sitting
                // on a light ground on a phone set to dark mode.
                androidx.compose.material3.Surface(
                    // safeDrawingPadding keeps content clear of the status bar,
                    // navigation bar and any display cutout. Android 15+ draws
                    // edge-to-edge by default, which put the section label
                    // directly underneath the clock on a real handset.
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                when (val s = screen.value) {
                    Screen.FirstRun -> FirstRunScreen(busy = busy.value, error = error.value,
                        onCreate = { pw -> heavy({ vaultManager.create(pw) }) { code ->
                            onUnlocked(); screen.value = Screen.ShowRecoveryCode(code!!) } })

                    is Screen.ShowRecoveryCode -> RecoveryCodeScreen(s.code) {
                        screen.value = if (BiometricGate.available(this@MainActivity))
                            Screen.OfferBiometric else Screen.Home
                    }

                    Screen.OfferBiometric -> BiometricOfferScreen(
                        busy = busy.value, error = error.value,
                        onSkip = { error.value = null; screen.value = Screen.Home },
                        onEnable = {
                            busy.value = true; error.value = null
                            biometrics.enable(this@MainActivity, vaultManager.vault()) { ok, message ->
                                busy.value = false
                                error.value = message
                                if (ok) screen.value = Screen.Home
                            }
                        })

                    Screen.Locked -> UnlockScreen(busy = busy.value, error = error.value,
                        onForgot = { error.value = null; screen.value = Screen.Recover },
                        onBiometric = if (biometrics.isEnabled) ({ promptBiometricUnlock() }) else null,
                        onUnlock = { pw -> heavy({ vaultManager.unlock(pw); null }) {
                            onUnlocked(); screen.value = Screen.Home } })

                    Screen.Recover -> RecoveryUnlockScreen(
                        busy = busy.value, error = error.value,
                        onBack = { error.value = null; screen.value = Screen.Locked },
                        onRecover = { code, pw -> heavy({ vaultManager.recoverWithCode(code, pw); null }) {
                            onUnlocked(); screen.value = Screen.Home } })

                    Screen.Home -> HomeScreen(
                        digest = digest.value, followUps = followUps.value,
                        onClearFollowUp = { id -> repo?.clearFollowUp(id); refresh() },
                        onAdd = { screen.value = Screen.Capture },
                        onRecords = { screen.value = Screen.Records },
                        onCabinet = { screen.value = Screen.Cabinet },
                        onLock = { lockNow() })

                    Screen.Capture -> CaptureScreen(
                        onCamera = {
                            // A device may have no camera app at all, and OEM camera
                            // apps can still refuse the intent. Never let that crash
                            // the vault.
                            runCatching { takePhoto.launch(null) }.onFailure {
                                error.value = "Could not open the camera. Try choosing from the gallery."
                                screen.value = Screen.Capture
                            }
                        },
                        onGallery = {
                            runCatching { pickImage.launch(Unit) }.onFailure {
                                error.value = "Could not open the gallery."
                                screen.value = Screen.Capture
                            }
                        },
                        onSample = { runPipeline(Pipeline.samplePrescription(it)) },
                        onSeed = if (BuildConfig.DEBUG) ({ seedTestRecords() }) else null,
                        onBack = { screen.value = Screen.Home })

                    Screen.Processing -> ProcessingScreen(processingStage.value, processingPreview.value)

                    is Screen.Review -> ReviewScreen(s.extraction,
                        onConfirm = { edited -> confirmAndStore(edited, s.jpeg) },
                        onDiscard = { screen.value = Screen.Home })

                    Screen.Records -> RecordsScreen(
                        records = repo?.loadRecords() ?: emptyList(),
                        onOpen = { screen.value = Screen.RecordDetail(it) },
                        onDelete = { repo?.deleteRecord(it); refresh(); screen.value = Screen.Records },
                        onBack = { screen.value = Screen.Home })

                    is Screen.RecordDetail -> {
                        val rec = repo?.loadRecords()?.firstOrNull { it.extraction.documentId == s.id }
                        val img = remember(s.id) {
                            repo?.loadDocumentImage(s.id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                        if (rec != null) RecordDetailScreen(rec, img) { screen.value = Screen.Records }
                        else { screen.value = Screen.Records }
                    }

                    Screen.Cabinet -> {
                        var query by remember { mutableStateOf("") }
                        val hiddenRx = if (query.isBlank()) 0
                            else cabinet.value.count { indications.neverSurface(it.generic) }
                        CabinetScreen(
                            items = cabinet.value.filterNot { query.isNotBlank() && indications.neverSurface(it.generic) },
                            query = query, onQuery = { query = it },
                            matches = { q, item -> indications.matches(q, item.generic) },
                            hiddenRxCount = hiddenRx,
                            onCount = { item, n -> updateCabinet { list ->
                                list.map { if (it === item || sameItem(it, item)) it.copy(count = n) else it } } },
                            onExpiry = { item, e -> updateCabinet { list ->
                                list.map { if (sameItem(it, item)) it.copy(expiry = e.ifBlank { null }) else it } } },
                            onBack = { screen.value = Screen.Home })
                    }
                }
                }
            }
        }
    }

    // ---- pipeline -----------------------------------------------------------

    private fun loadAndRun(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeScaled(uri) }
            if (bmp != null) runPipeline(bmp)
            else { error.value = "Could not read that image."; screen.value = Screen.Capture }
        }
    }

    private fun runPipeline(bitmap: Bitmap) {
        processingPreview.value = bitmap
        processingStage.value = Pipeline.Stage.ENCRYPTING
        screen.value = Screen.Processing
        lifecycleScope.launch {
            try {
                // The bytes that get stored — compressed once, in memory only.
                val jpeg = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        .toByteArray()
                }
                processingStage.value = Pipeline.Stage.READING
                val text = Pipeline.ocr(bitmap)
                // Debug builds only: the raw OCR text, for pipeline diagnosis.
                // Release builds log nothing — SECURITY.md 10.2.
                if (BuildConfig.DEBUG) android.util.Log.d("mlk.ocr", "----raw----\\n" + text + "\\n----end----")
                processingStage.value = Pipeline.Stage.UNDERSTANDING
                val extraction = withContext(Dispatchers.Default) { Pipeline.understand(text) }
                processingStage.value = Pipeline.Stage.DONE
                screen.value = Screen.Review(extraction, jpeg)
            } catch (e: Exception) {
                error.value = "Reading failed: ${e.message ?: "unknown error"}"
                screen.value = Screen.Capture
            }
        }
    }

    private fun confirmAndStore(extraction: DocumentExtraction, jpeg: ByteArray) {
        val r = repo ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            r.saveDocumentImage(extraction.documentId, jpeg)
            r.saveRecord(Repo.StoredRecord(
                extraction = extraction,
                savedAt = System.currentTimeMillis(),
                followUpDate = Pipeline.followUpDate(extraction.rawText, extraction.documentDate),
                followUpCleared = false))
            // Prescription medicines are offered into the cabinet automatically;
            // counts start at the prescribed course size and are user-editable.
            if (extraction.medications.isNotEmpty()) {
                val existing = r.loadCabinet().toMutableList()
                extraction.medications.forEach { m ->
                    val already = existing.any {
                        it.generic.equals(m.generic, true) &&
                            (it.strength ?: "").equals(m.strength ?: "", true)
                    }
                    if (!already) existing += Repo.CabinetItem(
                        generic = m.generic, brand = m.brand, strength = m.strength,
                        count = m.durationDays ?: 10, expiry = null,
                        fromDocument = extraction.documentId)
                }
                r.saveCabinet(existing)
            }
            withContext(Dispatchers.Main) { refresh(); screen.value = Screen.Home }
        }
    }

    // ---- state --------------------------------------------------------------

    private fun onUnlocked() {
        repo = Repo(filesDir, vaultManager.vault())
        refresh()
        notifyDueFollowUps()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refresh() {
        val r = repo ?: return
        val records = r.loadRecords()
        digest.value = r.rebuildDigest(records)
        cabinet.value = r.loadCabinet()
        val today = LocalDate.now()
        followUps.value = records.filter { !it.followUpCleared && it.followUpDate != null }
            .mapNotNull { rec ->
                val due = rec.followUpDate!!
                when {
                    due.isBefore(today) -> FollowUpAlert(rec.extraction.documentId, due, overdue = true)
                    !due.isAfter(today.plusDays(14)) -> FollowUpAlert(rec.extraction.documentId, due, overdue = false)
                    else -> null
                }
            }
    }

    private fun updateCabinet(edit: (List<Repo.CabinetItem>) -> List<Repo.CabinetItem>) {
        val r = repo ?: return
        val updated = edit(cabinet.value)
        r.saveCabinet(updated)
        cabinet.value = updated
    }

    private fun sameItem(a: Repo.CabinetItem, b: Repo.CabinetItem) =
        a.generic == b.generic && a.strength == b.strength && a.brand == b.brand

    private fun lockNow() {
        repo = null
        vaultManager.lock()
        digest.value = HealthDigest(); cabinet.value = emptyList(); followUps.value = emptyList()
        screen.value = Screen.Locked
    }

    /** Names nothing. A lock screen is visible to whoever is standing next to you. */
    private fun notifyDueFollowUps() {
        val due = followUps.value
        if (due.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("followups", "Check-up reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val text = if (due.any { it.overdue }) "A follow-up visit is overdue."
                   else "A follow-up visit is coming up."
        nm.notify(1, NotificationCompat.Builder(this, "followups")
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("MediLocker").setContentText(text).setAutoCancel(true).build())
    }

    /**
     * Debug-only bulk ingest, used to test the app at realistic scale.
     *
     * Runs each document through the SAME extraction, merge and storage path a
     * photographed document takes — only the camera and OCR stages are skipped,
     * because those are verified separately and re-running OCR on 100 rendered
     * bitmaps would take minutes without testing anything new.
     *
     * The texts are real prescriptions from the project's own corpus, not
     * invented data.
     */
    private fun seedTestRecords() {
        val r = repo ?: return
        busy.value = true
        processingStage.value = Pipeline.Stage.UNDERSTANDING
        processingPreview.value = null
        screen.value = Screen.Processing

        lifecycleScope.launch(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val raw = assets.open("test_corpus.txt").bufferedReader().readText()
            val docs = raw.split("###DOC ").drop(1)

            var extractMs = 0L
            var storeMs = 0L
            var ingested = 0

            for (block in docs) {
                val type = runCatching {
                    DocumentType.valueOf(block.substringBefore("\n").trim())
                }.getOrDefault(DocumentType.OTHER)
                val text = block.substringAfter("\n").substringBefore("###END").trim()
                if (text.isBlank()) continue

                val t0 = System.nanoTime()
                val extraction = Extraction.extract(
                    "seed-" + java.util.UUID.randomUUID().toString().take(8), text, type
                )
                extractMs += (System.nanoTime() - t0) / 1_000_000

                val t1 = System.nanoTime()
                r.saveRecord(
                    Repo.StoredRecord(
                        extraction = extraction,
                        savedAt = System.currentTimeMillis(),
                        followUpDate = Pipeline.followUpDate(text, extraction.documentDate),
                        followUpCleared = false
                    )
                )
                storeMs += (System.nanoTime() - t1) / 1_000_000
                ingested++
            }

            val t2 = System.nanoTime()
            val records = r.loadRecords()
            val loadMs = (System.nanoTime() - t2) / 1_000_000

            val t3 = System.nanoTime()
            val built = r.rebuildDigest(records)
            val digestMs = (System.nanoTime() - t3) / 1_000_000

            android.util.Log.d(
                "mlk.seed",
                "ingested=" + ingested +
                    " extract=" + extractMs + "ms" +
                    " store=" + storeMs + "ms" +
                    " loadAll=" + loadMs + "ms" +
                    " digest=" + digestMs + "ms" +
                    " total=" + (System.currentTimeMillis() - started) + "ms" +
                    " meds=" + built.medications.size +
                    " labs=" + built.labs.values.sumOf { it.size } +
                    " flags=" + built.flags.size
            )

            withContext(Dispatchers.Main) {
                busy.value = false
                refresh()
                screen.value = Screen.Home
            }
        }
    }

    /** Biometric unlock. Any failure quietly hands back to the password field. */
    private fun promptBiometricUnlock() {
        busy.value = true; error.value = null
        biometrics.unlock(
            activity = this,
            onUnlocked = { vault ->
                busy.value = false
                vaultManager.adopt(vault)
                onUnlocked()
                screen.value = Screen.Home
            },
            onFallback = { message ->
                busy.value = false
                error.value = message
            }
        )
    }

    /** Auto-offer the fingerprint prompt when arriving at a locked vault. */
    private fun maybeAutoPromptBiometric() {
        if (screen.value == Screen.Locked && biometrics.isEnabled && !busy.value) {
            promptBiometricUnlock()
        }
    }

    private fun heavy(work: suspend () -> String?, done: (String?) -> Unit) {
        busy.value = true; error.value = null
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.Default) { work() } }
                .onSuccess(done)
                .onFailure { error.value = it.userMessage() }
            busy.value = false
        }
    }

    /**
     * Decode a picked image, downscaled to something OCR can chew on.
     *
     * The measure pass is the subtle part: with `inJustDecodeBounds = true`,
     * `decodeStream` fills the Options and returns NULL by design. An earlier
     * version wrote `openInputStream(uri)?.use { decodeStream(...) } ?: return null`,
     * intending the elvis to guard a null stream — but it actually tested the
     * decode result, so every gallery pick bailed out before doing any work.
     * The stream is now guarded explicitly and the decode result ignored.
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val resolver = contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val measureStream = resolver.openInputStream(uri) ?: return null
        measureStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2200) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = resolver.openInputStream(uri) ?: return null
        val bmp = decodeStream.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        // Respect EXIF orientation, or OCR reads sideways text.
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        return if (rotation == 0f) bmp else {
            val m = android.graphics.Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vaultManager.lock()
    }
}

private fun Throwable.userMessage(): String =
    if (this is CryptoException) message ?: "Something went wrong."
    else "Something went wrong. Please try again."
