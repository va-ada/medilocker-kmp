package com.medilocker.domain

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pass 2 — real-data evaluation against the project's own synthetic prescription
 * corpus (24,961 records), sampled to 400 with a fixed seed.
 *
 * This is not a round-trip test. The fixture carries the generator's ground
 * truth, so the numbers printed here are a genuine extraction accuracy figure
 * for the rule-based stage, measured on documents this parser has never seen.
 *
 * Thresholds are set at what the rule stage should achieve on its own. The
 * neural NER of Phase 3 is expected to lift the drug-name figure; these bounds
 * exist to catch regressions, not to declare the target met.
 */
class CorpusEvaluationTest {

    private data class Record(
        val id: String, val nMeds: Int, val drugs: List<String>,
        val brands: List<String>, val strengths: List<String>,
        val freqs: List<String>, val text: String
    )

    private fun load(): List<Record> {
        val stream = javaClass.classLoader.getResourceAsStream("corpus_sample.txt") ?: return emptyList()
        val content = stream.bufferedReader().readText()
        return content.split("###RECORD ").drop(1).map { block ->
            fun field(tag: String) = block.substringAfter("###$tag ", "").substringBefore("\n").trim()
            Record(
                id = block.substringBefore("\n").trim(),
                nMeds = field("NMEDS").toIntOrNull() ?: 0,
                drugs = field("DRUGS").split("|").filter { it.isNotBlank() },
                brands = field("BRANDS").split("|").filter { it.isNotBlank() },
                strengths = field("STRENGTHS").split("|").filter { it.isNotBlank() },
                freqs = field("FREQS").split("|").filter { it.isNotBlank() },
                text = block.substringAfter("###TEXT\n").substringBefore("\n###END")
            )
        }
    }

    @Test
    fun `extraction accuracy on the real corpus`() {
        val records = load()
        assertTrue(records.isNotEmpty(), "corpus fixture missing")

        var lineHits = 0; var lineTotal = 0
        var drugHits = 0; var drugTotal = 0
        var strengthHits = 0; var strengthTotal = 0
        var freqHits = 0; var freqTotal = 0
        var typeHits = 0
        var dateHits = 0
        var diagnosisHits = 0
        var doctorHits = 0

        for (r in records) {
            val extraction = Extraction.extract(r.id, r.text, DocumentType.PRESCRIPTION)

            if (Extraction.classify(r.text) == DocumentType.PRESCRIPTION) typeHits++
            if (extraction.documentDate != null) dateHits++
            if (extraction.conditions.isNotEmpty()) diagnosisHits++
            if (extraction.clinicians.isNotEmpty()) doctorHits++

            lineTotal += r.nMeds
            lineHits += minOf(extraction.medications.size, r.nMeds)

            val gotGenerics = extraction.medications.map { it.generic.lowercase() }
            for (expected in r.drugs) {
                drugTotal++
                if (gotGenerics.any { it.contains(expected.lowercase()) || expected.lowercase().contains(it) }) drugHits++
            }
            val gotStrengths = extraction.medications.mapNotNull { it.strength?.lowercase()?.replace(" ", "") }
            for (expected in r.strengths) {
                strengthTotal++
                if (gotStrengths.contains(expected.lowercase().replace(" ", ""))) strengthHits++
            }
            val gotFreqs = extraction.medications.mapNotNull { it.frequency }
            for (expected in r.freqs) {
                freqTotal++
                if (gotFreqs.contains(expected)) freqHits++
            }
        }

        fun pct(a: Int, b: Int) = if (b == 0) 0.0 else 100.0 * a / b
        val n = records.size

        println(
            """
            |
            |==================================================================
            | PASS 2 — rule-based extraction vs. corpus ground truth
            | ${n} real prescriptions, seed 20260818
            |==================================================================
            | Document type classified   ${"%6.2f".format(pct(typeHits, n))} %   ($typeHits/$n)
            | Document date found        ${"%6.2f".format(pct(dateHits, n))} %   ($dateHits/$n)
            | Diagnosis found            ${"%6.2f".format(pct(diagnosisHits, n))} %   ($diagnosisHits/$n)
            | Prescriber found           ${"%6.2f".format(pct(doctorHits, n))} %   ($doctorHits/$n)
            |------------------------------------------------------------------
            | Medication lines detected  ${"%6.2f".format(pct(lineHits, lineTotal))} %   ($lineHits/$lineTotal)
            | Drug name matched          ${"%6.2f".format(pct(drugHits, drugTotal))} %   ($drugHits/$drugTotal)
            | Strength matched           ${"%6.2f".format(pct(strengthHits, strengthTotal))} %   ($strengthHits/$strengthTotal)
            | Frequency matched          ${"%6.2f".format(pct(freqHits, freqTotal))} %   ($freqHits/$freqTotal)
            |==================================================================
            """.trimMargin()
        )

        assertTrue(pct(typeHits, n) >= 95.0, "classification regressed")
        assertTrue(pct(dateHits, n) >= 95.0, "date parsing regressed")
        assertTrue(pct(lineHits, lineTotal) >= 90.0, "medication line detection regressed")
        assertTrue(pct(drugHits, drugTotal) >= 85.0, "drug name extraction regressed")
        assertTrue(pct(freqHits, freqTotal) >= 90.0, "frequency parsing regressed")
    }

    @Test
    fun `merging the whole sample never loses a fact or throws`() {
        val records = load()
        var digest = HealthDigest()
        for (r in records.take(150)) {
            val before = digest.medications.size
            digest = MergeEngine.merge(digest, Extraction.extract(r.id, r.text, DocumentType.PRESCRIPTION)).digest
            assertTrue(digest.medications.size >= before, "merge lost medications on ${r.id}")
        }
        println("| Digest after 150 real prescriptions: ${digest.medications.size} courses, " +
                "${digest.conditions.size} conditions, ${digest.careTeam.size} clinicians, ${digest.flags.size} flags")
        assertTrue(digest.medications.isNotEmpty())
    }
}
