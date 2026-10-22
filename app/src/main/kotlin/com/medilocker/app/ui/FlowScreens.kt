package com.medilocker.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.medilocker.app.Pipeline
import com.medilocker.app.Repo
import com.medilocker.domain.*
import java.time.LocalDate

@Composable
private fun Eyebrow2(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace, color = color)

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    border: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit
) = Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, border)
) { Column(Modifier.padding(16.dp), content = content) }

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
) { Spacer(Modifier.height(16.dp)); content(); Spacer(Modifier.height(32.dp)) }

// ---------------------------------------------------------------- home

data class FollowUpAlert(val documentId: String, val due: LocalDate, val overdue: Boolean)

@Composable
fun HomeScreen(
    digest: HealthDigest,
    followUps: List<FollowUpAlert>,
    onClearFollowUp: (String) -> Unit,
    onAdd: () -> Unit, onRecords: () -> Unit, onCabinet: () -> Unit, onLock: () -> Unit
) {
    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onAdd) { Text("Add document") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Eyebrow2("Unlocked")
                    Text("Your vault", style = MaterialTheme.typography.displaySmall)
                }
                TextButton(onClick = onLock) { Text("Lock") }
            }

            followUps.forEach { fu ->
                Panel(border = attentionColor()) {
                    Eyebrow2(if (fu.overdue) "Check-up overdue" else "Check-up coming", attentionColor())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (fu.overdue) "A follow-up was due on ${fu.due}." else "A follow-up is due on ${fu.due}.") +
                            " From your document of that visit.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { onClearFollowUp(fu.documentId) }) { Text("Done — clear this") }
                }
            }

            digest.flags.forEach { flag ->
                Panel(border = attentionColor()) {
                    Eyebrow2(flag.kind.name.replace('_', ' '), attentionColor())
                    Spacer(Modifier.height(4.dp))
                    Text(flag.message, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Panel(border = MaterialTheme.colorScheme.primary) {
                Eyebrow2("Health summary")
                Spacer(Modifier.height(8.dp))
                Text("${digest.activeMedications.size} medicines · ${digest.conditions.size} conditions · " +
                    "${digest.labs.values.sumOf { it.size }} results",
                    style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(if (digest.mergedDocuments.isEmpty())
                        "Nothing yet. Add your first prescription or lab report."
                    else "Assembled from ${digest.mergedDocuments.size} document(s). Every line cites its source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRecords, Modifier.weight(1f)) { Text("Records") }
                OutlinedButton(onClick = onCabinet, Modifier.weight(1f)) { Text("Cabinet") }
            }

            if (digest.activeMedications.isNotEmpty()) {
                Eyebrow2("Taking now", MaterialTheme.colorScheme.onSurfaceVariant)
                digest.activeMedications.forEach { med ->
                    Panel {
                        Text(med.brand ?: med.generic, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(med.strength, med.frequency, med.grid?.let { "($it)" },
                                med.durationDays?.let { "x $it days" }).joinToString(" ")
                                .ifBlank { "schedule not recorded" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            digest.labs.forEach { (_, series) ->
                val latest = series.maxByOrNull { it.date } ?: return@forEach
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(latest.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${latest.value}${latest.unit ?: ""}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (latest.outOfRange == true) MaterialTheme.colorScheme.error else secureColor())
                    }
                    if (series.size >= 2) {
                        val first = series.minByOrNull { it.date }!!
                        Text("${first.value} → ${latest.value} since ${first.date}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(90.dp))
        }
    }
}

// ---------------------------------------------------------------- capture

@Composable
fun CaptureScreen(
    onCamera: () -> Unit, onGallery: () -> Unit, onSample: (Int) -> Unit,
    onBack: () -> Unit, onSeed: (() -> Unit)? = null
) {
    var sample by remember { mutableStateOf(0) }
    ScreenColumn {
        TextButton(onClick = onBack) { Text("← Back") }
        Eyebrow2("Add a document")
        Text("Photograph it, or pick a file", style = MaterialTheme.typography.displaySmall)
        Text("The image is encrypted before anything else happens. Reading it runs entirely on this phone.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onCamera, Modifier.fillMaxWidth().height(52.dp)) { Text("Camera") }
        OutlinedButton(onClick = onGallery, Modifier.fillMaxWidth().height(52.dp)) { Text("Choose from gallery") }
        Panel {
            Eyebrow2("No document at hand?", MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("Try a sample. A synthetic prescription is drawn on screen and the real " +
                "text-reading pipeline runs on it — nothing is faked downstream.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSample(0) }) { Text("Prescription") }
                OutlinedButton(onClick = { onSample(1) }) { Text("Lab report") }
                OutlinedButton(onClick = { onSample(2) }) { Text("Duplicate Rx") }
            }
        }
        onSeed?.let {
            Panel(border = attentionColor()) {
                Eyebrow2("Debug build only", attentionColor())
                Spacer(Modifier.height(6.dp))
                Text("Load 100 real prescriptions and lab reports from the project corpus, " +
                    "through the same extraction and merge path a photo takes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = it) { Text("Load 100 test records") }
            }
        }
    }
}

// ---------------------------------------------------------------- processing

@Composable
fun ProcessingScreen(stage: Pipeline.Stage, preview: Bitmap?) {
    ScreenColumn {
        Spacer(Modifier.height(24.dp))
        Eyebrow2("Reading it")
        Text("One moment", style = MaterialTheme.typography.displaySmall)
        preview?.let {
            Image(it.asImageBitmap(), contentDescription = "Document being processed",
                Modifier.fillMaxWidth().height(220.dp))
        }
        Pipeline.Stage.entries.filter { it != Pipeline.Stage.DONE }.forEach { st ->
            val reached = st.ordinal <= stage.ordinal
            val active = st == stage
            Panel(border = if (reached) secureColor() else MaterialTheme.colorScheme.outline) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(if (reached) "✓" else "·",
                        color = if (reached) secureColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(st.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- review

/**
 * The only door into the digest. Everything shown here is editable; anything
 * the user edits is pinned so no later document can overwrite it.
 */
@Composable
fun ReviewScreen(
    extraction: DocumentExtraction,
    onConfirm: (DocumentExtraction) -> Unit,
    onDiscard: () -> Unit
) {
    // Editable copies of each medication's uncertain fields.
    val meds = remember {
        extraction.medications.map {
            mutableStateOf(Triple(it.strength ?: "", it.frequency ?: "", false))
        }
    }
    ScreenColumn {
        Eyebrow2("Check before saving", attentionColor())
        Text("What the app read", style = MaterialTheme.typography.displaySmall)
        Text("Read automatically from your document — please check it against the original. " +
            "Nothing is saved until you confirm.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Panel {
            Eyebrow2(extraction.type.name.replace('_', ' '), MaterialTheme.colorScheme.onSurfaceVariant)
            extraction.documentDate?.let { Text("Dated $it", style = MaterialTheme.typography.bodyMedium) }
            extraction.clinicians.firstOrNull()?.let {
                Text("Dr. ${it.name}" + (it.regNo?.let { r -> " · $r" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
            }
            extraction.conditions.forEach {
                Text("Diagnosis: ${it.label}" + (it.icd10?.let { c -> "  [$c]" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }

        extraction.medications.forEachIndexed { i, med ->
            val uncertain = med.confidence != Confidence.HIGH
            Panel(border = if (uncertain) attentionColor() else MaterialTheme.colorScheme.outline) {
                if (uncertain) { Eyebrow2("Check this one", attentionColor()); Spacer(Modifier.height(4.dp)) }
                Text((med.brand ?: med.generic) + (med.brand?.let { " (${med.generic})" } ?: ""),
                    style = MaterialTheme.typography.titleMedium)
                med.grid?.let { Text("Schedule grid $it" + (med.durationDays?.let { d -> " · $d days" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = meds[i].value.first,
                        onValueChange = { meds[i].value = Triple(it, meds[i].value.second, true) },
                        label = { Text("Strength") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = meds[i].value.second,
                        onValueChange = { meds[i].value = Triple(meds[i].value.first, it, true) },
                        label = { Text("Frequency") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        }

        extraction.labs.forEach { lab ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(lab.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${lab.value}${lab.unit ?: ""}", style = MaterialTheme.typography.titleMedium,
                        color = if (lab.outOfRange == true) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface)
                }
                Text(if (lab.refLow != null || lab.refHigh != null)
                        "Range on report: ${lab.refLow ?: "?"}–${lab.refHigh ?: "?"}"
                    else "No range stated on the report — value stored, not judged",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (extraction.medications.isEmpty() && extraction.labs.isEmpty())
            Panel(border = attentionColor()) {
                Text("Nothing recognisable was found. The document is still stored encrypted; " +
                    "extraction can be re-run on it later.", style = MaterialTheme.typography.bodyLarge)
            }

        Button(onClick = {
            val edited = extraction.copy(medications = extraction.medications.mapIndexed { i, med ->
                val (st, fr, touched) = meds[i].value
                if (touched) med.copy(
                    strength = st.ifBlank { null }, frequency = fr.ifBlank { null },
                    pinned = true, confidence = Confidence.HIGH
                ) else med
            })
            onConfirm(edited)
        }, Modifier.fillMaxWidth().height(52.dp)) { Text("Looks right — save to vault") }
        TextButton(onClick = onDiscard, Modifier.fillMaxWidth()) { Text("Discard") }
    }
}

// ---------------------------------------------------------------- records

@Composable
fun RecordsScreen(records: List<Repo.StoredRecord>, onOpen: (String) -> Unit,
                  onDelete: (String) -> Unit, onBack: () -> Unit) {
    ScreenColumn {
        TextButton(onClick = onBack) { Text("← Back") }
        Eyebrow2("Records")
        Text("${records.size} document(s)", style = MaterialTheme.typography.displaySmall)
        records.sortedByDescending { it.savedAt }.forEach { r ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.extraction.type.name.replace('_', ' ').lowercase()
                            .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium)
                        Text((r.extraction.documentDate?.toString() ?: "no date") +
                            " · ${r.extraction.medications.size} meds · ${r.extraction.labs.size} results",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onOpen(r.extraction.documentId) }) { Text("Open") }
                    TextButton(onClick = { onDelete(r.extraction.documentId) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun RecordDetailScreen(record: Repo.StoredRecord, image: Bitmap?, onBack: () -> Unit) {
    ScreenColumn {
        TextButton(onClick = onBack) { Text("← Back") }
        image?.let { Image(it.asImageBitmap(), "Original document", Modifier.fillMaxWidth()) }
        Panel {
            Text(Artifacts.documentCard(record.extraction),
                style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}

// ---------------------------------------------------------------- cabinet

@Composable
fun CabinetScreen(
    items: List<Repo.CabinetItem>,
    query: String, onQuery: (String) -> Unit,
    matches: (String, Repo.CabinetItem) -> Boolean,
    hiddenRxCount: Int,
    onCount: (Repo.CabinetItem, Int) -> Unit,
    onExpiry: (Repo.CabinetItem, String) -> Unit,
    onBack: () -> Unit
) {
    ScreenColumn {
        TextButton(onClick = onBack) { Text("← Back") }
        Eyebrow2("Medicine cabinet")
        Text("What you already have", style = MaterialTheme.typography.displaySmall)
        OutlinedTextField(value = query, onValueChange = onQuery,
            label = { Text("Search a symptom — fever, acidity, allergy…") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        val searching = query.isNotBlank()
        val shown = if (searching) items.filter { matches(query, it) } else items

        if (searching) {
            Text("Showing medicines you own whose label mentions “$query”. This is retrieval " +
                "from your own cabinet — not a recommendation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hiddenRxCount > 0)
                Panel(border = attentionColor()) {
                    Text("$hiddenRxCount prescription-only medicine(s) in your cabinet are never " +
                        "shown for symptom search. Those need a doctor.",
                        style = MaterialTheme.typography.bodyMedium, color = attentionColor())
                }
            if (shown.isEmpty()) Text("Nothing in your cabinet matches.",
                style = MaterialTheme.typography.bodyLarge)
        }
        if (items.isEmpty()) Text("Empty. When you save a prescription you'll be offered its " +
            "medicines here.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        shown.forEach { item ->
            Panel(border = if (item.expired) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.outline) {
                Text((item.brand ?: item.generic) +
                    (item.brand?.let { " (${item.generic})" } ?: "") +
                    (item.strength?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium)
                if (item.expired) Text("EXPIRED — do not use. Dispose safely.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCount(item, (item.count - 1).coerceAtLeast(0)) }) { Text("−") }
                    Text("${item.count} left", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { onCount(item, item.count + 1) }) { Text("+") }
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(value = item.expiry ?: "",
                        onValueChange = { onExpiry(item, it) },
                        label = { Text("Exp yyyy-MM") }, singleLine = true,
                        modifier = Modifier.width(150.dp))
                }
            }
        }
    }
}
