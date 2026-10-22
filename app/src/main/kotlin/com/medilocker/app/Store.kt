package com.medilocker.app

import com.medilocker.crypto.Vault
import com.medilocker.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * Encrypted persistence, built entirely on the vault's own ciphers — no second
 * crypto stack, no plaintext at rest.
 *
 *   documents/{id}.bin   image bytes, chunked FileCipher
 *   records/{id}.json    the confirmed extraction + app metadata, FieldCipher
 *   cabinet.json         medicine cabinet, FieldCipher
 *
 * The digest is deliberately NOT persisted. It is rebuilt at unlock by folding
 * every stored record through the merge engine — which is deterministic and
 * idempotent, so the rebuild always equals the digest the user last saw. One
 * source of truth, zero risk of summary/records drift.
 */
class Repo(private val root: File, private val vault: Vault) {

    private val docsDir = File(root, "documents").apply { mkdirs() }
    private val recsDir = File(root, "records").apply { mkdirs() }
    private val cabinetFile = File(root, "cabinet.json")

    data class StoredRecord(
        val extraction: DocumentExtraction,
        val savedAt: Long,
        val followUpDate: LocalDate?,
        val followUpCleared: Boolean
    )

    // ---- documents ----------------------------------------------------------

    fun saveDocumentImage(id: String, jpeg: ByteArray) {
        val out = ByteArrayOutputStream()
        vault.fileCipher().encrypt(ByteArrayInputStream(jpeg), out, id)
        File(docsDir, "$id.bin").writeBytes(out.toByteArray())
    }

    fun loadDocumentImage(id: String): ByteArray? {
        val f = File(docsDir, "$id.bin")
        if (!f.exists()) return null
        val out = ByteArrayOutputStream()
        vault.fileCipher().decrypt(ByteArrayInputStream(f.readBytes()), out, id)
        return out.toByteArray()
    }

    // ---- records ------------------------------------------------------------

    fun saveRecord(rec: StoredRecord) {
        val json = Codec.record(rec).toString()
        val sealed = vault.fieldCipher().encrypt(json, rec.extraction.documentId, "record")
        File(recsDir, "${rec.extraction.documentId}.json").writeBytes(sealed)
    }

    fun loadRecords(): List<StoredRecord> =
        (recsDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val id = f.name.removeSuffix(".json")
                Codec.record(JSONObject(vault.fieldCipher().decrypt(f.readBytes(), id, "record")))
            }.getOrNull()
        }.sortedBy { it.savedAt }

    fun deleteRecord(id: String) {
        // Both artefacts, atomically enough for one device. The old app deleted
        // the row and orphaned the file forever.
        File(recsDir, "$id.json").delete()
        File(docsDir, "$id.bin").delete()
    }

    /** The digest is a fold, never a stored object. */
    fun rebuildDigest(records: List<StoredRecord> = loadRecords()): HealthDigest =
        records.fold(HealthDigest()) { d, r -> MergeEngine.merge(d, r.extraction).digest }

    fun clearFollowUp(id: String) {
        loadRecords().firstOrNull { it.extraction.documentId == id }?.let {
            saveRecord(it.copy(followUpCleared = true))
        }
    }

    // ---- cabinet ------------------------------------------------------------

    data class CabinetItem(
        val generic: String, val brand: String?, val strength: String?,
        val count: Int, val expiry: String?,      // yyyy-MM, from the strip
        val fromDocument: String?
    ) {
        val expired: Boolean get() = expiry != null &&
            runCatching { java.time.YearMonth.parse(expiry).atEndOfMonth().isBefore(LocalDate.now()) }
                .getOrDefault(false)
    }

    fun loadCabinet(): List<CabinetItem> {
        if (!cabinetFile.exists()) return emptyList()
        val arr = JSONArray(vault.fieldCipher().decrypt(cabinetFile.readBytes(), "cabinet", "items"))
        return (0 until arr.length()).map { Codec.cabinetItem(arr.getJSONObject(it)) }
    }

    fun saveCabinet(items: List<CabinetItem>) {
        val arr = JSONArray().apply { items.forEach { put(Codec.cabinetItem(it)) } }
        cabinetFile.writeBytes(vault.fieldCipher().encrypt(arr.toString(), "cabinet", "items"))
    }
}

/** Hand-rolled JSON — the model is small and stable, and org.json ships with Android. */
private object Codec {

    fun record(r: Repo.StoredRecord): JSONObject = JSONObject().apply {
        put("savedAt", r.savedAt)
        putOpt("followUp", r.followUpDate?.toString())
        put("followUpCleared", r.followUpCleared)
        put("extraction", extraction(r.extraction))
    }

    fun record(o: JSONObject) = Repo.StoredRecord(
        extraction = extraction(o.getJSONObject("extraction")),
        savedAt = o.getLong("savedAt"),
        followUpDate = o.optString("followUp").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
        followUpCleared = o.optBoolean("followUpCleared")
    )

    fun extraction(e: DocumentExtraction): JSONObject = JSONObject().apply {
        put("id", e.documentId); put("type", e.type.name)
        putOpt("date", e.documentDate?.toString()); put("raw", e.rawText)
        put("meds", JSONArray().apply { e.medications.forEach { put(med(it)) } })
        put("labs", JSONArray().apply { e.labs.forEach { put(lab(it)) } })
        put("conds", JSONArray().apply { e.conditions.forEach { put(cond(it)) } })
        put("allergies", JSONArray().apply { e.allergies.forEach { a ->
            put(JSONObject().put("s", a.substance).putOpt("r", a.reaction).put("src", sources(a.sources))) } })
        put("clins", JSONArray().apply { e.clinicians.forEach { c ->
            put(JSONObject().put("n", c.name).putOpt("sp", c.specialty).putOpt("reg", c.regNo)
                .put("seen", JSONArray(c.seenOn.map { it.toString() })).put("src", sources(c.sources))) } })
    }

    fun extraction(o: JSONObject) = DocumentExtraction(
        documentId = o.getString("id"),
        type = DocumentType.valueOf(o.getString("type")),
        documentDate = o.optString("date").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
        rawText = o.optString("raw"),
        medications = o.getJSONArray("meds").objects().map(::med),
        labs = o.getJSONArray("labs").objects().map(::lab),
        conditions = o.getJSONArray("conds").objects().map(::cond),
        allergies = o.getJSONArray("allergies").objects().map {
            Allergy(it.getString("s"), it.optString("r").ifBlank { null }, sources(it.getJSONArray("src"))) },
        clinicians = o.getJSONArray("clins").objects().map {
            Clinician(it.getString("n"), it.optString("sp").ifBlank { null }, it.optString("reg").ifBlank { null },
                it.getJSONArray("seen").let { a -> (0 until a.length()).map { i -> LocalDate.parse(a.getString(i)) } },
                sources(it.getJSONArray("src"))) }
    )

    private fun med(m: Medication) = JSONObject().apply {
        put("g", m.generic); putOpt("b", m.brand); putOpt("st", m.strength); putOpt("f", m.form)
        putOpt("fr", m.frequency); putOpt("gr", m.grid); putOpt("d", m.durationDays)
        putOpt("i", m.instruction); putOpt("s", m.started?.toString()); putOpt("e", m.ended?.toString())
        put("status", m.status.name); put("c", m.confidence.name); put("pin", m.pinned)
        put("src", sources(m.sources))
    }

    private fun med(o: JSONObject) = Medication(
        generic = o.getString("g"), brand = o.optString("b").ifBlank { null },
        strength = o.optString("st").ifBlank { null }, form = o.optString("f").ifBlank { null },
        frequency = o.optString("fr").ifBlank { null }, grid = o.optString("gr").ifBlank { null },
        durationDays = if (o.has("d")) o.getInt("d") else null,
        instruction = o.optString("i").ifBlank { null },
        started = o.optString("s").ifBlank { null }?.let(LocalDate::parse),
        ended = o.optString("e").ifBlank { null }?.let(LocalDate::parse),
        status = MedStatus.valueOf(o.getString("status")),
        confidence = Confidence.valueOf(o.getString("c")),
        pinned = o.getBoolean("pin"),
        sources = sources(o.getJSONArray("src"))
    )

    private fun lab(l: LabObservation) = JSONObject().apply {
        put("k", l.testKey); put("n", l.displayName); put("v", l.value); putOpt("u", l.unit)
        putOpt("lo", l.refLow); putOpt("hi", l.refHigh); put("d", l.date.toString())
        put("c", l.confidence.name); put("src", source(l.source))
    }

    private fun lab(o: JSONObject) = LabObservation(
        testKey = o.getString("k"), displayName = o.getString("n"), value = o.getDouble("v"),
        unit = o.optString("u").ifBlank { null },
        refLow = if (o.has("lo")) o.getDouble("lo") else null,
        refHigh = if (o.has("hi")) o.getDouble("hi") else null,
        date = LocalDate.parse(o.getString("d")),
        confidence = Confidence.valueOf(o.getString("c")),
        source = source(o.getJSONObject("src"))
    )

    private fun cond(c: Condition) = JSONObject().apply {
        put("l", c.label); putOpt("icd", c.icd10)
        putOpt("fs", c.firstSeen?.toString()); putOpt("ls", c.lastSeen?.toString())
        put("c", c.confidence.name); put("src", sources(c.sources))
    }

    private fun cond(o: JSONObject) = Condition(
        label = o.getString("l"), icd10 = o.optString("icd").ifBlank { null },
        firstSeen = o.optString("fs").ifBlank { null }?.let(LocalDate::parse),
        lastSeen = o.optString("ls").ifBlank { null }?.let(LocalDate::parse),
        confidence = Confidence.valueOf(o.getString("c")),
        sources = sources(o.getJSONArray("src"))
    )

    private fun source(s: SourceRef) = JSONObject().put("d", s.documentId).put("p", s.page).putOpt("l", s.line)
    private fun source(o: JSONObject) = SourceRef(o.getString("d"), o.getInt("p"), if (o.has("l")) o.getInt("l") else null)
    private fun sources(l: List<SourceRef>) = JSONArray().apply { l.forEach { put(source(it)) } }
    private fun sources(a: JSONArray) = (0 until a.length()).map { source(a.getJSONObject(it)) }

    fun cabinetItem(c: Repo.CabinetItem) = JSONObject().apply {
        put("g", c.generic); putOpt("b", c.brand); putOpt("st", c.strength)
        put("n", c.count); putOpt("ex", c.expiry); putOpt("doc", c.fromDocument)
    }
    fun cabinetItem(o: JSONObject) = Repo.CabinetItem(
        o.getString("g"), o.optString("b").ifBlank { null }, o.optString("st").ifBlank { null },
        o.getInt("n"), o.optString("ex").ifBlank { null }, o.optString("doc").ifBlank { null })
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
