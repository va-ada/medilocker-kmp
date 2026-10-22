package com.medilocker.domain

/**
 * Folds a document's extracted facts into the running [HealthDigest].
 *
 * **No language model participates.** This is a pure function over typed
 * collections, which is what makes the summary stable between uploads,
 * exhaustively testable, and cheap enough to run on every ingest.
 *
 * The alternative — concatenating every document and asking a model to
 * re-summarise — fails four ways: a 4B model at 4k context cannot hold 200
 * documents; wording changes every run so the user cannot distinguish a real
 * change from a rephrasing; it costs 30+ seconds per upload; and provenance
 * dissolves into the prose.
 *
 * Two invariants hold throughout:
 *
 *  1. **Nothing is ever deleted.** Superseded facts are marked, with a reason,
 *     so the history stays auditable.
 *  2. **A pinned fact is never overwritten.** Once the user corrects something
 *     at Review, no later extraction may contradict it.
 */
object MergeEngine {

    fun merge(digest: HealthDigest, extraction: DocumentExtraction): MergeResult {
        // Idempotence: re-ingesting a document already folded in is a no-op.
        // Without this, opening the same file twice would silently double every
        // medication course.
        if (extraction.documentId in digest.mergedDocuments) {
            return MergeResult(digest, emptyList(), emptySet(), emptyList())
        }

        val changes = mutableListOf<String>()
        val sections = mutableSetOf<Section>()
        val flags = mutableListOf<DigestFlag>()

        val medications = mergeMedications(digest.medications, extraction, changes, flags)
        if (medications != digest.medications) sections += Section.MEDICATIONS

        val labs = mergeLabs(digest.labs, extraction, changes, flags)
        if (labs != digest.labs) sections += Section.LABS

        val conditions = mergeConditions(digest.conditions, extraction, changes)
        if (conditions != digest.conditions) sections += Section.CONDITIONS

        val allergies = mergeAllergies(digest.allergies, extraction, changes)
        if (allergies != digest.allergies) sections += Section.ALLERGIES

        val careTeam = mergeCareTeam(digest.careTeam, extraction, changes)
        if (careTeam != digest.careTeam) sections += Section.CARE_TEAM

        return MergeResult(
            digest = digest.copy(
                medications = medications,
                labs = labs,
                conditions = conditions,
                allergies = allergies,
                careTeam = careTeam,
                flags = digest.flags + flags,
                mergedDocuments = digest.mergedDocuments + extraction.documentId
            ),
            changes = changes,
            changedSections = sections,
            newFlags = flags
        )
    }

    // ---- Medications --------------------------------------------------------

    private fun mergeMedications(
        existing: List<Medication>,
        extraction: DocumentExtraction,
        changes: MutableList<String>,
        flags: MutableList<DigestFlag>
    ): List<Medication> {
        if (extraction.medications.isEmpty()) return existing
        val result = existing.toMutableList()

        for (incoming in extraction.medications) {
            val dated = incoming.copy(started = incoming.started ?: extraction.documentDate)

            // A pinned entry for the same drug is authoritative — record the
            // sighting, change nothing else.
            val pinnedIndex = result.indexOfFirst {
                it.pinned && it.generic.equals(dated.generic, true) && it.overlaps(dated)
            }
            if (pinnedIndex >= 0) {
                result[pinnedIndex] = result[pinnedIndex].addSources(dated.sources)
                changes += "kept user-corrected ${dated.generic}; noted it appears again"
                continue
            }

            val overlapping = result.withIndex().filter { (_, m) ->
                m.status != MedStatus.SUPERSEDED &&
                    m.generic.equals(dated.generic, true) &&
                    m.overlaps(dated)
            }

            if (overlapping.isEmpty()) {
                result += dated
                changes += "added ${dated.describe()}"
                continue
            }

            val sameBrand = overlapping.firstOrNull { (_, m) ->
                (m.brand ?: "").equals(dated.brand ?: "", true)
            }

            if (sameBrand == null) {
                // Same ingredient, different brand, at the same time. This is
                // the error the product exists to catch, so it is flagged and
                // NOT merged — the user must see both entries.
                result += dated
                val others = overlapping.joinToString(", ") { it.value.brand ?: it.value.generic }
                flags += DigestFlag(
                    FlagKind.DUPLICATE_THERAPY,
                    "${dated.brand ?: dated.generic} and $others are both ${dated.generic}, " +
                        "prescribed for overlapping periods. Please check with your doctor.",
                    sources = (dated.sources + overlapping.flatMap { it.value.sources }).distinct()
                )
                changes += "flagged duplicate therapy for ${dated.generic}"
                continue
            }

            val (index, current) = sameBrand
            if (current.sameRegimenAs(dated)) {
                // Continuation of the same course: extend it, record the source.
                result[index] = current
                    .addSources(dated.sources)
                    .copy(ended = latest(current.ended, dated.ended))
                changes += "confirmed ongoing ${dated.describe()}"
            } else {
                // Same drug and brand, overlapping, but the dose or schedule
                // changed. Newer wins; the older course is retained as history.
                result[index] = current.copy(status = MedStatus.SUPERSEDED, ended = extraction.documentDate)
                result += dated
                flags += DigestFlag(
                    FlagKind.DOSE_CHANGED,
                    "${dated.generic} changed from ${current.doseLabel()} to ${dated.doseLabel()}.",
                    sources = (current.sources + dated.sources).distinct()
                )
                changes += "dose change for ${dated.generic}: ${current.doseLabel()} -> ${dated.doseLabel()}"
            }
        }
        return result
    }

    // ---- Labs ---------------------------------------------------------------

    private fun mergeLabs(
        existing: Map<String, List<LabObservation>>,
        extraction: DocumentExtraction,
        changes: MutableList<String>,
        flags: MutableList<DigestFlag>
    ): Map<String, List<LabObservation>> {
        if (extraction.labs.isEmpty()) return existing
        val result = existing.mapValues { it.value.toMutableList() }.toMutableMap()

        for (observation in extraction.labs) {
            val series = result.getOrPut(observation.testKey) { mutableListOf() }

            // Same test, same date, same value from a different document is the
            // same observation reported twice, not a second measurement.
            val duplicate = series.any {
                it.date == observation.date && it.value == observation.value
            }
            if (duplicate) continue

            series += observation
            series.sortBy { it.date }
            changes += "recorded ${observation.displayName} ${observation.value}${observation.unit ?: ""}"

            if (observation.outOfRange == true) {
                flags += DigestFlag(
                    FlagKind.LAB_OUT_OF_RANGE,
                    "${observation.displayName} was ${observation.value}${observation.unit ?: ""} on " +
                        "${observation.date}, outside the range on the report " +
                        "(${observation.refLow ?: "?"}-${observation.refHigh ?: "?"}).",
                    sources = listOf(observation.source)
                )
            }
        }
        return result.mapValues { it.value.toList() }
    }

    // ---- Conditions, allergies, clinicians ----------------------------------

    private fun mergeConditions(
        existing: List<Condition>,
        extraction: DocumentExtraction,
        changes: MutableList<String>
    ): List<Condition> {
        if (extraction.conditions.isEmpty()) return existing
        val result = existing.toMutableList()

        for (incoming in extraction.conditions) {
            val seen = incoming.firstSeen ?: extraction.documentDate
            val index = result.indexOfFirst { it.label.equals(incoming.label, true) }
            if (index >= 0) {
                val current = result[index]
                result[index] = current.copy(
                    lastSeen = latest(current.lastSeen, seen),
                    icd10 = current.icd10 ?: incoming.icd10,
                    sources = (current.sources + incoming.sources).distinct()
                )
            } else {
                result += incoming.copy(firstSeen = seen, lastSeen = seen)
                changes += "added condition ${incoming.label}"
            }
        }
        return result
    }

    private fun mergeAllergies(
        existing: List<Allergy>,
        extraction: DocumentExtraction,
        changes: MutableList<String>
    ): List<Allergy> {
        if (extraction.allergies.isEmpty()) return existing
        val result = existing.toMutableList()
        for (incoming in extraction.allergies) {
            val index = result.indexOfFirst { it.substance.equals(incoming.substance, true) }
            if (index >= 0) {
                result[index] = result[index].copy(
                    reaction = result[index].reaction ?: incoming.reaction,
                    sources = (result[index].sources + incoming.sources).distinct()
                )
            } else {
                result += incoming
                changes += "added allergy ${incoming.substance}"
            }
        }
        return result
    }

    private fun mergeCareTeam(
        existing: List<Clinician>,
        extraction: DocumentExtraction,
        changes: MutableList<String>
    ): List<Clinician> {
        if (extraction.clinicians.isEmpty()) return existing
        val result = existing.toMutableList()
        for (incoming in extraction.clinicians) {
            val visit = extraction.documentDate
            val index = result.indexOfFirst { it.name.equals(incoming.name, true) }
            if (index >= 0) {
                val current = result[index]
                result[index] = current.copy(
                    specialty = current.specialty ?: incoming.specialty,
                    regNo = current.regNo ?: incoming.regNo,
                    seenOn = (current.seenOn + listOfNotNull(visit)).distinct().sorted(),
                    sources = (current.sources + incoming.sources).distinct()
                )
            } else {
                result += incoming.copy(seenOn = listOfNotNull(visit))
                changes += "added ${incoming.name} to care team"
            }
        }
        return result
    }

    // ---- helpers ------------------------------------------------------------

    private fun Medication.addSources(more: List<SourceRef>) =
        copy(sources = (sources + more).distinct())

    private fun latest(a: java.time.LocalDate?, b: java.time.LocalDate?) =
        when {
            a == null -> b
            b == null -> a
            else -> if (a.isAfter(b)) a else b
        }
}

internal fun Medication.doseLabel(): String =
    listOfNotNull(strength, frequency).joinToString(" ").ifBlank { "an unspecified dose" }

internal fun Medication.describe(): String =
    buildString {
        append(brand ?: generic)
        if (brand != null) append(" (").append(generic).append(")")
        strength?.let { append(" ").append(it) }
        frequency?.let { append(" ").append(it) }
    }
