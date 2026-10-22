package com.medilocker.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains

/** The whole flow, end to end: text -> classify -> extract -> artifacts -> merge -> digest. */
class FlowTest {

    private val prescription = """
        Dr. Anita Malhotra
        M.D. (Medicine)  |  Reg. No. MMC/26962
        City Care Polyclinic, Navi Mumbai
        --------------------------------------------------------------
        Patient: Suresh Desai    Age/Sex: 62/M    Date: 18/01/2026
        Diagnosis: Type 2 Diabetes  [ICD-10: E119]

        Rx
           1. Tab. Glycomet (Metformin) 500mg — BD (1-0-1) x 30 days, after food
           2. Tab. Telma (Telmisartan) 40mg — OD (1-0-0) x 30 days, before food

        Advice: Regular exercise advised.
        Follow-up: after 2 weeks
    """.trimIndent()

    private val labReport = """
        Sunrise Diagnostics, Mumbai
        Patient: Suresh Desai    Age/Sex: 62/M    Date: 20/02/2026

        HbA1c 8.2 % (4.0-5.6)
        Fasting Glucose 148 mg/dL (70-100)
        Haemoglobin 13.4 g/dL (12.0-15.5)
        Reference ranges per specimen
    """.trimIndent()

    @Test
    fun `classification picks the right document type`() {
        assertEquals(DocumentType.PRESCRIPTION, Extraction.classify(prescription))
        assertEquals(DocumentType.LAB_REPORT, Extraction.classify(labReport))
    }

    @Test
    fun `a prescription is parsed into medicines, diagnosis, doctor and date`() {
        val e = Extraction.extract("rx-1", prescription, DocumentType.PRESCRIPTION)

        assertEquals(2, e.medications.size)
        val metformin = e.medications[0]
        assertEquals("Metformin", metformin.generic)
        assertEquals("Glycomet", metformin.brand)
        assertEquals("500mg", metformin.strength)
        assertEquals("BD", metformin.frequency)
        assertEquals("1-0-1", metformin.grid)
        assertEquals(30, metformin.durationDays)
        assertEquals("after food", metformin.instruction)

        assertEquals("Type 2 Diabetes", e.conditions.single().label)
        assertEquals("E119", e.conditions.single().icd10)
        assertEquals("Anita Malhotra", e.clinicians.single().name)
        assertEquals("MMC/26962", e.clinicians.single().regNo)
        assertEquals(2026, e.documentDate?.year)
        assertEquals(1, e.documentDate?.monthValue)
    }

    @Test
    fun `a lab report is parsed with values, units and ranges`() {
        val e = Extraction.extract("lab-1", labReport, DocumentType.LAB_REPORT)
        val byKey = e.labs.associateBy { it.testKey }

        assertContains(byKey.keys, "hba1c")
        assertEquals(8.2, byKey.getValue("hba1c").value)
        assertEquals(true, byKey.getValue("hba1c").outOfRange)

        assertEquals(13.4, byKey.getValue("haemoglobin").value)
        assertEquals(false, byKey.getValue("haemoglobin").outOfRange, "13.4 is inside 12.0-15.5")
    }

    @Test
    fun `every artifact is produced and the card cites what it found`() {
        val e = Extraction.extract("rx-1", prescription, DocumentType.PRESCRIPTION)

        assertEquals(prescription, Artifacts.rawText(e))

        val json = Artifacts.structuredJson(e)
        assertContains(json, "\"generic\": \"Metformin\"")
        assertContains(json, "\"grid\": \"1-0-1\"")
        assertContains(json, "rx-1")

        val card = Artifacts.documentCard(e)
        assertContains(card, "# Prescription")
        assertContains(card, "Glycomet")
        assertContains(card, "Type 2 Diabetes")
        assertContains(card, "not medical advice")
    }

    @Test
    fun `the full flow builds a digest that cites every claim`() {
        var digest = HealthDigest()

        val rx = Extraction.extract("rx-1", prescription, Extraction.classify(prescription))
        digest = MergeEngine.merge(digest, rx).digest

        val lab = Extraction.extract("lab-1", labReport, Extraction.classify(labReport))
        val result = MergeEngine.merge(digest, lab)
        digest = result.digest

        assertEquals(2, digest.activeMedications.size)
        assertEquals(setOf("rx-1", "lab-1"), digest.mergedDocuments)
        assertTrue(digest.labs.containsKey("hba1c"))
        assertTrue(result.newFlags.any { it.kind == FlagKind.LAB_OUT_OF_RANGE })

        // The property the PRD requires: no fact without a source.
        digest.medications.forEach { assertTrue(it.sources.isNotEmpty(), "${it.generic} has no source") }
        digest.conditions.forEach { assertTrue(it.sources.isNotEmpty(), "${it.label} has no source") }
        digest.labs.values.flatten().forEach { assertTrue(it.source.documentId.isNotBlank()) }

        val summary = Artifacts.digestSummary(digest)
        assertContains(summary, "# Health Summary")
        assertContains(summary, "Glycomet")
        assertContains(summary, "HbA1c")
        assertContains(summary, "rx-1")
        assertContains(summary, "lab-1")
    }

    @Test
    fun `a second lab report extends the trend rather than replacing it`() {
        var digest = HealthDigest()
        digest = MergeEngine.merge(digest, Extraction.extract("lab-1", labReport, DocumentType.LAB_REPORT)).digest

        val followUp = labReport.replace("Date: 20/02/2026", "Date: 20/08/2026").replace("HbA1c 8.2", "HbA1c 6.4")
        digest = MergeEngine.merge(digest, Extraction.extract("lab-2", followUp, DocumentType.LAB_REPORT)).digest

        assertEquals(2, digest.labs.getValue("hba1c").size)
        val summary = Artifacts.digestSummary(digest)
        assertContains(summary, "Trend: fallen")
        assertContains(summary, "from 8.2 to 6.4")
    }

    @Test
    fun `re-uploading the same file leaves the summary byte-identical`() {
        // Determinism is the point of a non-model merge: the user must be able
        // to trust that the summary only changed because the facts changed.
        val rx = Extraction.extract("rx-1", prescription, DocumentType.PRESCRIPTION)
        val once = MergeEngine.merge(HealthDigest(), rx).digest
        val twice = MergeEngine.merge(once, rx).digest

        assertEquals(Artifacts.digestSummary(once), Artifacts.digestSummary(twice))
    }

    @Test
    fun `low confidence entries are surfaced on the card for the user to check`() {
        val sparse = """
            Rx
               1. Tab. Someunknowndrug — SOS
        """.trimIndent()
        val e = Extraction.extract("rx-x", sparse, DocumentType.PRESCRIPTION)
        assertEquals(Confidence.LOW, e.medications.single().confidence)
        assertContains(Artifacts.documentCard(e), "needs checking")
    }
}

class SplitLineOcrTest {
    /**
     * Block-based OCR (ML Kit on device — observed on the emulator, 2026-08-20)
     * splits prescription lines at the dash and emits the schedule tails as
     * separate lines, often after unrelated text. The extractor must re-pair
     * them in order rather than losing every medicine on the page.
     */
    private val blockSplit = """
        Dr. Sunita Pillai
        General Physician Reg. No. MMC/41085
        Patient: Suresh Desai Age/Sex: 62/M Date: 05/08/2026
        Diagnosis: Viral Fever
        Rx
        1. Tab. Dolo 650 (Paracetamol) 650mg
        2. Tab. Calpol (Paracetamol) 650mg
        Advice: Plenty of oral fluids.
        Follow-up: after 1 week
        TDS X 5 days, after food
        SOS x 5 days
    """.trimIndent()

    @kotlin.test.Test
    fun `medicines split from their schedules by block OCR are re-paired in order`() {
        val e = Extraction.extract("rx-split", blockSplit, DocumentType.PRESCRIPTION)

        kotlin.test.assertEquals(2, e.medications.size, "both medicines must be found")
        val dolo = e.medications[0]; val calpol = e.medications[1]

        kotlin.test.assertEquals("Paracetamol", dolo.generic)
        kotlin.test.assertEquals("Dolo 650", dolo.brand)
        kotlin.test.assertEquals("TDS", dolo.frequency, "first orphan tail pairs with first medicine")
        kotlin.test.assertEquals(5, dolo.durationDays)
        kotlin.test.assertEquals("after food", dolo.instruction)

        kotlin.test.assertEquals("SOS", calpol.frequency, "second tail pairs with second medicine")
        kotlin.test.assertEquals(Confidence.MEDIUM, dolo.confidence,
            "positionally re-paired schedules are heuristic and must surface for review")
    }

    @kotlin.test.Test
    fun `a numbered advice line does not steal an orphan tail`() {
        val text = """
            Rx
            1. Tab. Metformin 500mg
            2. Avoid sugary drinks
            BD x 30 days
        """.trimIndent()
        val e = Extraction.extract("rx-x", text, DocumentType.PRESCRIPTION)
        kotlin.test.assertEquals(1, e.medications.size)
        kotlin.test.assertEquals("BD", e.medications[0].frequency)
    }
}

class OcrQuirkTest {
    @kotlin.test.Test
    fun `a date with OCR-inserted spaces still parses`() {
        val d = Extraction.findDate(listOf("Patient: X Age/Sex: 62/M Date: 30 /07/2026"))
        kotlin.test.assertEquals(java.time.LocalDate.of(2026, 7, 30), d)
    }

    @kotlin.test.Test
    fun `a lab line duplicated by dual-script OCR is stored once, keeping the full range`() {
        val e = Extraction.extract("lab-d", """
            Date: 30/07/2026
            Creatinine 1.1 mg/dL (0.7-1.3)
            Creatinine 1.1 mg/dL (.7-1.3)
        """.trimIndent(), DocumentType.LAB_REPORT)
        val creat = e.labs.filter { it.testKey == "creatinine" }
        kotlin.test.assertEquals(1, creat.size, "duplicate analyte lines must collapse")
        kotlin.test.assertEquals(0.7, creat[0].refLow, "the complete range must win")
    }
}

class RealPrescriptionOcrTest {
    /**
     * Verbatim ML Kit output from a real Android handset (Samsung, 21 Aug 2026),
     * including its actual mistakes: "0D" for OD, "S0s" for SOS, spaces inside
     * words, a danda swapped for an "l", and the whole page partially read twice
     * by the dual-script pass.
     */
    private val realOcr = """
        Dr. Meera Krishnan
        M.B.B.S., M.D. (Medicine) Reg. No. MMC/58412
        Wellness Multispeciality Clinic, Borivali West, Mumbai
        Patient: Aditya Soni
        Diagnosis: Acute Gastritis [ICD-10: K291]
        Rx
        Age/Sex: 21/M
        1. Tab. Pan-D (Pantoprazole ) 40mg - 0D (1-0-0) x 14 days, empty stomach
        2. Tab. Mefta-Spas (Dicyclomine) 20mg - S0s x 5 days, af ter f00d
        Date: 14/08/2026
        3. Syp, Gelusi। (Magnes ium Hydroxide) 200mg - TDS x 7 days, af ter food
        Follow-up: after 3 weeks
        Advice: Avoid spicy and oily food. Plenty of fluids.
        Signature:
        2. Tab. Meftal-Spas (Dicyclomine) 20mg - SOS x 5 days, after food
        3. Syp. Gelusil (Magnesium Hydroxide) 200mg - TDS x 7 days, after food
    """.trimIndent()

    @kotlin.test.Test
    fun `hyphenated Indian brand names survive extraction`() {
        val e = Extraction.extract("rx-real", realOcr, DocumentType.PRESCRIPTION)
        val brands = e.medications.mapNotNull { it.brand }
        kotlin.test.assertTrue(brands.any { it.startsWith("Pan-D") },
            "Pan-D was truncated at its hyphen; got $brands")
        kotlin.test.assertTrue(brands.any { it.contains("Spas") },
            "Meftal-Spas was truncated at its hyphen; got $brands")
    }

    @kotlin.test.Test
    fun `a page read twice by dual-script OCR yields one entry per medicine`() {
        val e = Extraction.extract("rx-real", realOcr, DocumentType.PRESCRIPTION)
        kotlin.test.assertEquals(3, e.medications.size,
            "expected 3 medicines, got ${e.medications.map { it.brand ?: it.generic }}")
    }

    @kotlin.test.Test
    fun `the cleaner of two readings wins`() {
        val e = Extraction.extract("rx-real", realOcr, DocumentType.PRESCRIPTION)
        val dicyclomine = e.medications.first { (it.brand ?: "").contains("Spas") }
        kotlin.test.assertEquals("SOS", dicyclomine.frequency,
            "the degraded 'S0s' reading was kept over the clean 'SOS' one")
        kotlin.test.assertEquals("after food", dicyclomine.instruction)
    }

    @kotlin.test.Test
    fun `header fields are read correctly from real OCR`() {
        val e = Extraction.extract("rx-real", realOcr, DocumentType.PRESCRIPTION)
        kotlin.test.assertEquals(java.time.LocalDate.of(2026, 8, 14), e.documentDate)
        kotlin.test.assertEquals("Acute Gastritis", e.conditions.single().label)
        kotlin.test.assertEquals("K291", e.conditions.single().icd10)
        kotlin.test.assertEquals("Meera Krishnan", e.clinicians.single().name)
        kotlin.test.assertEquals("MMC/58412", e.clinicians.single().regNo)
    }
}
