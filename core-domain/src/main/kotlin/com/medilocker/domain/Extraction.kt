package com.medilocker.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Rule-based extraction — the classical text-analysis stage of the pipeline.
 *
 * This runs before any model and handles the parts that rules genuinely do
 * better than a network: dosage grids, Indian frequency notation, durations,
 * strengths, reference ranges and dates. These are closed, regular vocabularies;
 * a learned model would be slower, larger, less predictable and no more correct.
 *
 * The neural NER stage layers on top for the open-vocabulary work — drug and
 * condition names in free prose — and never replaces what is parsed here.
 *
 * Target format is the Indian prescription convention the project's synthetic
 * corpus renders, which is also how real Mumbai clinic prescriptions are written:
 *
 * ```
 *   Rx
 *      1. Tab. Vitastem (Tetracycline) 250mg — OD (1-0-0) x 10 days, after food
 *      2. Cap. Amoxicillin 500mg — TDS x 5 days
 * ```
 */
object Extraction {

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy")
    )

    /** Indian frequency notation. Latin abbreviations still dominate handwriting here. */
    val FREQUENCIES = setOf("OD", "BD", "TDS", "TID", "QID", "QDS", "HS", "SOS", "PRN", "QD", "BID", "STAT")

    private val DOSAGE_FORMS = setOf(
        "Tab", "Cap", "Syp", "Susp", "Inj", "Drop", "Drops", "Oint", "Cream", "Gel", "Powder", "Sachet", "Spray"
    )

    // The dash-separated tail (schedule, duration, instruction) is OPTIONAL.
    // OCR engines that read the page in blocks routinely split a prescription
    // line at the dash, leaving "1. Tab. Dolo 650 (Paracetamol) 650mg" in one
    // block and "TDS x 5 days, after food" in another, often at the end of the
    // text. Orphan tails are re-paired with tail-less medicines by order below.
    /**
     * An ASCII hyphen only separates the medicine from its schedule when it has
     * whitespace on BOTH sides. Indian brand names are full of tight hyphens —
     * Pan-D, Meftal-Spas, Shelcal-500, Becosules-Z — and treating those as the
     * separator truncated the brand to "Pan" and "Mefta" on a real prescription.
     * Em and en dashes stay permissive; nobody puts those inside a brand name.
     */
    private val MED_LINE = Regex(
        """^\s*(\d+)[.)]\s*([A-Za-z]+)[.,]?\s+(.+?)(?:(?:\s*[—–]+\s*|\s+-{1,2}\s+)(.+))?$"""
    )
    private val ORPHAN_TAIL = Regex(
        """^\s*(?:(?:OD|BD|TDS|TID|QID|QDS|HS|SOS|PRN|QD|BID|STAT)\b|\(?\d(?:\s*-\s*\d){1,3}\)?|[x×]\s*\d+\s*(?:day|week|month))""",
        RegexOption.IGNORE_CASE
    )
    private val BRAND_GENERIC = Regex("""^(.+?)\s*\((.+?)\)\s*(.*)$""")
    private val STRENGTH = Regex("""(\d+(?:\.\d+)?)\s*(mcg|mg|gm|g|ml|IU|%)\b""", RegexOption.IGNORE_CASE)
    private val GRID = Regex("""\b(\d(?:\s*-\s*\d){1,3})\b""")
    private val DURATION = Regex("""[x×]\s*(\d+)\s*(day|days|week|weeks|month|months)\b""", RegexOption.IGNORE_CASE)
    private val DOCTOR = Regex("""^\s*Dr\.?\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val REG_NO = Regex("""Reg\.?\s*No\.?\s*[:\-]?\s*(\S+)""", RegexOption.IGNORE_CASE)
    private val DIAGNOSIS = Regex("""^\s*Diagnosis\s*[:\-]\s*(.+?)\s*(?:\[ICD-10:\s*([A-Z0-9.]+)\])?\s*$""", RegexOption.IGNORE_CASE)
    // OCR happily inserts spaces inside dates ("30 /07/2026"), so separators
    // tolerate surrounding whitespace and the match is compacted before parsing.
    private val DATE_LINE = Regex("""Date\s*[:\-]\s*(\d{1,4}\s*[/\-]\s*\d{1,2}\s*[/\-]\s*\d{2,4})""", RegexOption.IGNORE_CASE)
    private val ALLERGY = Regex("""^\s*(?:Allerg(?:y|ies))\s*[:\-]\s*(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Lab line: `Haemoglobin 13.4 g/dL (12.0-15.5)`.
     * The range is optional and is never invented when absent — a value with no
     * stated range is stored with `outOfRange == null`, not assumed normal.
     */
    private val LAB_LINE = Regex(
        """^\s*([A-Za-z][A-Za-z0-9 ()/%.\-]{2,40}?)\s*[:\-]?\s+(\d+(?:\.\d+)?)\s*([A-Za-z/%µ]+(?:/[A-Za-z]+)?)?\s*(?:\(\s*(\d+(?:\.\d+)?)\s*[-–]\s*(\d+(?:\.\d+)?)\s*\))?\s*$"""
    )

    fun extract(documentId: String, text: String, type: DocumentType): DocumentExtraction {
        val lines = text.lines()
        val date = findDate(lines)

        return DocumentExtraction(
            documentId = documentId,
            type = type,
            documentDate = date,
            rawText = text,
            medications = if (type == DocumentType.LAB_REPORT) emptyList()
                          else extractMedications(lines, documentId, date),
            labs = if (type == DocumentType.LAB_REPORT) extractLabs(lines, documentId, date) else emptyList(),
            conditions = extractConditions(lines, documentId),
            allergies = extractAllergies(lines, documentId),
            clinicians = extractClinicians(lines, documentId)
        )
    }

    // ---- medications --------------------------------------------------------

    fun extractMedications(lines: List<String>, documentId: String, date: LocalDate?): List<Medication> {
        val result = mutableListOf<Medication>()
        val medLineIndices = mutableSetOf<Int>()
        val lineNumbers = mutableMapOf<Medication, Int>()

        lines.forEachIndexed { index, line ->
            val match = MED_LINE.find(line) ?: return@forEachIndexed
            val (_, formToken, namePart, tail) = match.destructured

            // Guard against numbered advice lines that merely look like Rx lines.
            val form = DOSAGE_FORMS.firstOrNull { it.equals(formToken, true) } ?: return@forEachIndexed
            medLineIndices += index

            var brand: String? = null
            var generic: String
            var remainder = namePart

            val bg = BRAND_GENERIC.find(namePart)
            if (bg != null) {
                brand = bg.groupValues[1].trim()
                generic = bg.groupValues[2].trim()
                remainder = bg.groupValues[3].trim()
            } else {
                generic = STRENGTH.find(namePart)
                    ?.let { namePart.substring(0, it.range.first) }
                    ?.trim()
                    ?: namePart.trim()
            }

            val strength = (STRENGTH.find(remainder) ?: STRENGTH.find(namePart))
                ?.value?.replace(" ", "")

            val frequency = tail.split(Regex("[\\s,()]+"))
                .firstOrNull { it.uppercase() in FREQUENCIES }?.uppercase()

            val grid = GRID.find(tail)?.groupValues?.get(1)?.replace(" ", "")

            val durationDays = DURATION.find(tail)?.let {
                val n = it.groupValues[1].toInt()
                when (it.groupValues[2].lowercase().trimEnd('s')) {
                    "week" -> n * 7
                    "month" -> n * 30
                    else -> n
                }
            }

            val instruction = tail.substringAfter(",", "").trim().ifBlank { null }

            // Confidence drops when the fields a prescription must have are absent.
            val known = listOfNotNull(strength, frequency, durationDays).size
            val confidence = when {
                known >= 3 -> Confidence.HIGH
                known == 2 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }

            val rxNumber = match.groupValues[1].toIntOrNull() ?: 0
            val built = Medication(
                generic = generic.trim().ifBlank { brand ?: "unknown" },
                brand = brand,
                strength = strength,
                form = form,
                frequency = frequency,
                grid = grid,
                durationDays = durationDays,
                instruction = instruction,
                started = date,
                ended = if (date != null && durationDays != null) date.plusDays(durationDays.toLong()) else null,
                confidence = confidence,
                sources = listOf(SourceRef(documentId, line = index + 1))
            )
            result += built
            lineNumbers[built] = rxNumber
        }

        // Dual-script OCR reads the page twice and can emit the same Rx line
        // twice at different quality ("Mefta-Spas" and "Meftal-Spas"). Collapse
        // entries that share an Rx number AND a compatible name, keeping the
        // most complete reading. Entries sharing a number but naming clearly
        // different drugs are both kept — losing a medicine is worse than
        // showing a duplicate for the user to resolve.
        val collapsed = mutableListOf<Medication>()
        for ((_, group) in result.groupBy { lineNumbers[it] ?: -1 }) {
            if (group.size == 1) { collapsed += group; continue }
            val buckets = mutableListOf<MutableList<Medication>>()
            for (med in group) {
                val bucket = buckets.firstOrNull { b -> compatibleNames(b.first(), med) }
                if (bucket != null) bucket += med else buckets += mutableListOf(med)
            }
            buckets.forEach { b -> collapsed += b.maxBy { completeness(it) } }
        }
        result.clear(); result.addAll(collapsed.sortedBy { lineNumbers[it] ?: 0 })

        // Re-pair orphan schedule tails with tail-less medicines, in order. The
        // pairing is positional and therefore heuristic, so an enriched
        // medicine is capped at MEDIUM confidence and surfaces on the review
        // screen rather than being silently trusted.
        val orphans = lines.withIndex().filter { (i, ln) ->
            i !in medLineIndices && ORPHAN_TAIL.containsMatchIn(ln.trim()) && MED_LINE.find(ln) == null
        }.map { it.value.trim() }

        if (orphans.isNotEmpty()) {
            var orphanIndex = 0
            for (i in result.indices) {
                if (orphanIndex >= orphans.size) break
                val med = result[i]
                if (med.frequency != null || med.durationDays != null) continue
                val tail = orphans[orphanIndex++]

                val frequency = tail.split(Regex("[\\s,()]+"))
                    .firstOrNull { it.uppercase() in FREQUENCIES }?.uppercase()
                val grid = GRID.find(tail)?.groupValues?.get(1)?.replace(" ", "")
                val durationDays = DURATION.find(tail)?.let {
                    val n = it.groupValues[1].toInt()
                    when (it.groupValues[2].lowercase().trimEnd('s')) {
                        "week" -> n * 7; "month" -> n * 30; else -> n
                    }
                }
                val instruction = tail.substringAfter(",", "").trim().ifBlank { null }

                result[i] = med.copy(
                    frequency = frequency ?: med.frequency,
                    grid = grid ?: med.grid,
                    durationDays = durationDays ?: med.durationDays,
                    instruction = instruction ?: med.instruction,
                    ended = if (med.started != null && durationDays != null)
                        med.started.plusDays(durationDays.toLong()) else med.ended,
                    confidence = Confidence.MEDIUM
                )
            }
        }
        return result
    }

    // ---- labs ---------------------------------------------------------------

    fun extractLabs(lines: List<String>, documentId: String, date: LocalDate?): List<LabObservation> {
        val observed = date ?: LocalDate.now()
        val result = mutableListOf<LabObservation>()

        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            if (DATE_LINE.containsMatchIn(line)) return@forEachIndexed
            val match = LAB_LINE.find(line) ?: return@forEachIndexed

            val name = match.groupValues[1].trim().trimEnd(':', '-').trim()
            if (name.length < 3 || name.lowercase() in NON_ANALYTES) return@forEachIndexed

            val value = match.groupValues[2].toDoubleOrNull() ?: return@forEachIndexed
            val unit = match.groupValues[3].ifBlank { null }
            val low = match.groupValues[4].toDoubleOrNull()
            val high = match.groupValues[5].toDoubleOrNull()

            result += LabObservation(
                testKey = normaliseTestName(name),
                displayName = name,
                value = value,
                unit = unit,
                refLow = low,
                refHigh = high,
                date = observed,
                confidence = if (unit != null && low != null) Confidence.HIGH else Confidence.MEDIUM,
                source = SourceRef(documentId, line = index + 1)
            )
        }
        // Dual-script OCR can emit the same analyte line twice with one copy
        // slightly mangled. Same test, same value, same date is one observation;
        // keep the copy that carries the most complete reference range.
        return result
            .groupBy { Triple(it.testKey, it.value, it.date) }
            .map { (_, dupes) ->
                dupes.maxBy { (if (it.refLow != null) 2 else 0) + (if (it.refHigh != null) 1 else 0) }
            }
    }

    private val NON_ANALYTES = setOf("age", "sex", "patient", "date", "page", "phone", "reg no", "id")

    /** How many of the fields that matter this reading actually captured. */
    private fun completeness(m: Medication): Int =
        listOfNotNull(m.brand, m.strength, m.frequency, m.grid, m.durationDays, m.instruction).size +
            m.generic.length / 10

    /**
     * Two readings of the same drug.
     *
     * A prefix test alone is not enough: OCR usually differs by a character
     * INSERTED or dropped mid-word rather than at the end. A real capture gave
     * "Mefta-Spas" and "Meftal-Spas" for the same line, which share no prefix
     * relationship but are one edit apart. The allowance scales with name
     * length so short names stay strict.
     */
    private fun compatibleNames(a: Medication, b: Medication): Boolean {
        fun key(m: Medication) = (m.brand ?: m.generic).lowercase().filter { it.isLetter() }
        val x = key(a); val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.startsWith(y) || y.startsWith(x)) return true
        val allowance = maxOf(1, minOf(x.length, y.length) / 5)
        return editDistance(x, y) <= allowance
    }

    /** Levenshtein, iterative, two rows. */
    internal fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    /** Stable key so the same analyte lands in one series regardless of spelling. */
    fun normaliseTestName(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .let { SYNONYMS[it] ?: it }

    private val SYNONYMS = mapOf(
        "hb" to "haemoglobin", "hgb" to "haemoglobin", "hemoglobin" to "haemoglobin",
        "hba1c" to "hba1c", "glycated_haemoglobin" to "hba1c",
        "fbs" to "fasting_glucose", "fasting_blood_sugar" to "fasting_glucose",
        "tsh" to "tsh", "creat" to "creatinine", "s_creatinine" to "creatinine"
    )

    // ---- everything else ----------------------------------------------------

    fun extractConditions(lines: List<String>, documentId: String): List<Condition> =
        lines.mapIndexedNotNull { index, line ->
            DIAGNOSIS.find(line)?.let {
                Condition(
                    label = it.groupValues[1].trim(),
                    icd10 = it.groupValues[2].ifBlank { null },
                    sources = listOf(SourceRef(documentId, line = index + 1))
                )
            }
        }

    fun extractAllergies(lines: List<String>, documentId: String): List<Allergy> =
        lines.flatMapIndexed { index, line ->
            ALLERGY.find(line)?.groupValues?.get(1)
                ?.split(",", ";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && !it.equals("nil", true) && !it.equals("none", true) }
                ?.map { Allergy(it, sources = listOf(SourceRef(documentId, line = index + 1))) }
                ?: emptyList()
        }

    fun extractClinicians(lines: List<String>, documentId: String): List<Clinician> {
        lines.forEachIndexed { index, line ->
            val doctor = DOCTOR.find(line) ?: return@forEachIndexed
            val name = doctor.groupValues[1].trim()
            if (name.isBlank() || name.length > 60) return@forEachIndexed

            // Specialty and registration sit on the following line by convention.
            val next = lines.getOrNull(index + 1).orEmpty()
            val regNo = REG_NO.find(next)?.groupValues?.get(1)
                ?: REG_NO.find(line)?.groupValues?.get(1)
            val specialty = next.substringBefore("|").trim()
                .takeIf { it.isNotBlank() && it.length < 40 && !it.contains("Reg", true) }

            return listOf(
                Clinician(
                    name = name,
                    specialty = specialty,
                    regNo = regNo,
                    sources = listOf(SourceRef(documentId, line = index + 1))
                )
            )
        }
        return emptyList()
    }

    fun findDate(lines: List<String>): LocalDate? {
        for (line in lines) {
            val raw = DATE_LINE.find(line)?.groupValues?.get(1)?.replace(Regex("\\s+"), "") ?: continue
            for (format in DATE_FORMATS) {
                runCatching { return LocalDate.parse(raw, format) }
            }
        }
        return null
    }

    /** Classifier stand-in for v1: keyword scoring. Replaced by the ML model in Phase 2.3. */
    fun classify(text: String): DocumentType {
        val lower = text.lowercase()
        val scores = mapOf(
            DocumentType.PRESCRIPTION to listOf("rx", "tab.", "cap.", "follow-up", "diagnosis").count { lower.contains(it) },
            DocumentType.LAB_REPORT to listOf("reference", "range", "specimen", "result", "mg/dl", "g/dl").count { lower.contains(it) },
            DocumentType.DISCHARGE_SUMMARY to listOf("discharge", "admitted", "course in hospital").count { lower.contains(it) },
            DocumentType.VACCINATION to listOf("vaccin", "dose 1", "immunis", "immuniz").count { lower.contains(it) },
            DocumentType.BILL to listOf("invoice", "total amount", "gst", "receipt").count { lower.contains(it) }
        )
        val best = scores.maxByOrNull { it.value }
        return if (best == null || best.value == 0) DocumentType.OTHER else best.key
    }
}

private inline fun <T, R> List<T>.flatMapIndexed(transform: (Int, T) -> Iterable<R>): List<R> =
    buildList { this@flatMapIndexed.forEachIndexed { i, v -> addAll(transform(i, v)) } }
