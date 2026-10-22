package com.medilocker.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MergeEngineTest {

    private val day1 = LocalDate.of(2026, 3, 1)
    // Inside day1's 5-day course, so the two genuinely overlap. Set this outside
    // the window and the engine correctly records a separate course instead.
    private val day2 = LocalDate.of(2026, 3, 4)
    private val later = LocalDate.of(2026, 8, 1)

    private fun med(
        generic: String, brand: String? = null, strength: String? = "500mg",
        frequency: String? = "BD", started: LocalDate? = day1, days: Int? = 5,
        pinned: Boolean = false, doc: String = "doc-1"
    ) = Medication(
        generic = generic, brand = brand, strength = strength, frequency = frequency,
        started = started, durationDays = days, pinned = pinned,
        ended = started?.plusDays((days ?: 0).toLong()),
        sources = listOf(SourceRef(doc))
    )

    private fun rx(id: String, date: LocalDate?, vararg meds: Medication) =
        DocumentExtraction(id, DocumentType.PRESCRIPTION, date, medications = meds.toList())

    // ---- idempotence --------------------------------------------------------

    @Test
    fun `re-ingesting the same document changes nothing`() {
        val doc = rx("doc-1", day1, med("paracetamol"))
        val once = MergeEngine.merge(HealthDigest(), doc)
        val twice = MergeEngine.merge(once.digest, doc)

        assertTrue(twice.isNoOp, "second merge produced changes: ${twice.changes}")
        assertEquals(once.digest, twice.digest)
        assertEquals(1, twice.digest.medications.size)
    }

    // ---- the rule table -----------------------------------------------------

    @Test
    fun `a new drug is added`() {
        val result = MergeEngine.merge(HealthDigest(), rx("doc-1", day1, med("amoxicillin")))
        assertEquals(1, result.digest.medications.size)
        assertTrue(Section.MEDICATIONS in result.changedSections)
    }

    @Test
    fun `same drug same dose overlapping is treated as one continuing course`() {
        var digest = MergeEngine.merge(HealthDigest(), rx("doc-1", day1, med("metformin", strength = "500mg"))).digest
        digest = MergeEngine.merge(digest, rx("doc-2", day2, med("metformin", strength = "500mg", started = day2, doc = "doc-2"))).digest

        assertEquals(1, digest.medications.size, "a continuation must not create a second course")
        assertEquals(2, digest.medications[0].sources.size, "both documents should be cited")
    }

    @Test
    fun `a dose change supersedes the old course and keeps it as history`() {
        var digest = MergeEngine.merge(HealthDigest(), rx("doc-1", day1, med("metformin", strength = "500mg"))).digest
        val result = MergeEngine.merge(
            digest, rx("doc-2", day2, med("metformin", strength = "1000mg", started = day2, doc = "doc-2"))
        )
        digest = result.digest

        assertEquals(2, digest.medications.size, "the old course must be retained, not deleted")
        assertEquals(1, digest.activeMedications.size)
        assertEquals("1000mg", digest.activeMedications[0].strength)
        assertEquals(1, digest.medications.count { it.status == MedStatus.SUPERSEDED })
        assertTrue(result.newFlags.any { it.kind == FlagKind.DOSE_CHANGED })
    }

    @Test
    fun `two brands of the same generic at the same time are flagged not merged`() {
        // Dolo 650 from one doctor, Calpol from another. Both paracetamol.
        // This is the specific error the product exists to catch.
        var digest = MergeEngine.merge(
            HealthDigest(), rx("doc-1", day1, med("paracetamol", brand = "Dolo 650", strength = "650mg"))
        ).digest
        val result = MergeEngine.merge(
            digest, rx("doc-2", day1, med("paracetamol", brand = "Calpol", strength = "650mg", doc = "doc-2"))
        )

        val flag = result.newFlags.firstOrNull { it.kind == FlagKind.DUPLICATE_THERAPY }
        assertTrue(flag != null, "duplicate therapy was not flagged")
        assertTrue(flag.message.contains("Calpol") && flag.message.contains("Dolo 650"))
        assertEquals(2, flag.sources.size, "the flag must cite both prescriptions")
        assertEquals(2, result.digest.medications.size, "both entries must remain visible to the user")
    }

    @Test
    fun `the same drug at a later, non-overlapping date is a separate course`() {
        var digest = MergeEngine.merge(HealthDigest(), rx("doc-1", day1, med("azithromycin", days = 3))).digest
        digest = MergeEngine.merge(
            digest, rx("doc-2", later, med("azithromycin", started = later, days = 3, doc = "doc-2"))
        ).digest

        assertEquals(2, digest.medications.size, "a repeat course months later is not a continuation")
    }

    @Test
    fun `a user-corrected entry is never overwritten by a later extraction`() {
        val corrected = med("levothyroxine", strength = "75mcg", pinned = true)
        var digest = HealthDigest(medications = listOf(corrected))

        val result = MergeEngine.merge(
            digest, rx("doc-2", day2, med("levothyroxine", strength = "25mcg", started = day2, doc = "doc-2"))
        )

        assertEquals(1, result.digest.medications.size)
        assertEquals("75mcg", result.digest.medications[0].strength, "the pinned value was overwritten")
        assertTrue(result.digest.medications[0].pinned)
        assertEquals(2, result.digest.medications[0].sources.size, "the new sighting should still be cited")
    }

    // ---- labs ---------------------------------------------------------------

    @Test
    fun `lab values accumulate into a dated series`() {
        fun lab(v: Double, d: LocalDate, doc: String) = DocumentExtraction(
            doc, DocumentType.LAB_REPORT, d,
            labs = listOf(LabObservation("hba1c", "HbA1c", v, "%", 4.0, 5.6, d, source = SourceRef(doc)))
        )

        var digest = MergeEngine.merge(HealthDigest(), lab(7.8, day1, "doc-1")).digest
        digest = MergeEngine.merge(digest, lab(6.9, day2, "doc-2")).digest
        digest = MergeEngine.merge(digest, lab(6.1, later, "doc-3")).digest

        val series = digest.labs.getValue("hba1c")
        assertEquals(3, series.size)
        assertEquals(listOf(day1, day2, later), series.map { it.date }, "series must stay date-ordered")
    }

    @Test
    fun `an out of range value is flagged and an unstated range is not guessed`() {
        val withRange = DocumentExtraction(
            "doc-1", DocumentType.LAB_REPORT, day1,
            labs = listOf(LabObservation("hba1c", "HbA1c", 9.2, "%", 4.0, 5.6, day1, source = SourceRef("doc-1")))
        )
        val flagged = MergeEngine.merge(HealthDigest(), withRange)
        assertTrue(flagged.newFlags.any { it.kind == FlagKind.LAB_OUT_OF_RANGE })

        val noRange = DocumentExtraction(
            "doc-2", DocumentType.LAB_REPORT, day1,
            labs = listOf(LabObservation("ferritin", "Ferritin", 9.2, "ng/mL", null, null, day1, source = SourceRef("doc-2")))
        )
        val unflagged = MergeEngine.merge(flagged.digest, noRange)
        assertFalse(
            unflagged.newFlags.any { it.kind == FlagKind.LAB_OUT_OF_RANGE },
            "a value with no stated range must not be judged"
        )
    }

    @Test
    fun `the same observation reported twice is stored once`() {
        val obs = LabObservation("hb", "Haemoglobin", 13.4, "g/dL", 12.0, 15.5, day1, source = SourceRef("doc-1"))
        var digest = MergeEngine.merge(
            HealthDigest(), DocumentExtraction("doc-1", DocumentType.LAB_REPORT, day1, labs = listOf(obs))
        ).digest
        digest = MergeEngine.merge(
            digest,
            DocumentExtraction("doc-2", DocumentType.LAB_REPORT, day1, labs = listOf(obs.copy(source = SourceRef("doc-2"))))
        ).digest

        assertEquals(1, digest.labs.getValue("hb").size)
    }

    // ---- narration targeting ------------------------------------------------

    @Test
    fun `a prescription upload does not change the labs section`() {
        // Narration re-runs only for changed sections. If a prescription dirtied
        // the labs hash, every upload would rewrite prose the user already read.
        val digest = MergeEngine.merge(
            HealthDigest(),
            DocumentExtraction(
                "doc-0", DocumentType.LAB_REPORT, day1,
                labs = listOf(LabObservation("hb", "Haemoglobin", 13.4, "g/dL", 12.0, 15.5, day1, source = SourceRef("doc-0")))
            )
        ).digest
        val before = digest.factHash(Section.LABS)

        val result = MergeEngine.merge(digest, rx("doc-1", day2, med("paracetamol")))

        assertEquals(before, result.digest.factHash(Section.LABS))
        assertFalse(Section.LABS in result.changedSections)
        assertTrue(Section.MEDICATIONS in result.changedSections)
    }

    @Test
    fun `nothing is ever removed by a merge`() {
        var digest = HealthDigest()
        val counts = mutableListOf<Int>()
        listOf("a", "b", "c", "d").forEachIndexed { i, id ->
            digest = MergeEngine.merge(
                digest, rx(id, day1.plusMonths(i.toLong() * 3), med("drug-$id", started = day1.plusMonths(i.toLong() * 3)))
            ).digest
            counts += digest.medications.size
        }
        assertEquals(listOf(1, 2, 3, 4), counts, "medication count must be monotonic")
    }
}
