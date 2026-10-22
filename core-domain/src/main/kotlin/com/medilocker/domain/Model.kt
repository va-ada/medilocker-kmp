package com.medilocker.domain

import java.time.LocalDate

/**
 * The typed facts a document yields, and the running summary they fold into.
 *
 * Everything here is a plain immutable value with no IO and no platform types
 * beyond [LocalDate], so the merge engine is exhaustively testable and ports to
 * Kotlin/Native by swapping java.time for kotlinx-datetime.
 */

enum class DocumentType {
    PRESCRIPTION, LAB_REPORT, DISCHARGE_SUMMARY, RADIOLOGY, VACCINATION, BILL, OTHER
}

/**
 * Where a fact came from. Every leaf in the digest carries at least one.
 *
 * This is why citation is structural rather than generated: a claim in the
 * summary is a rendering of a fact, and a fact without a source cannot be
 * constructed.
 */
data class SourceRef(
    val documentId: String,
    val page: Int = 1,
    val line: Int? = null
) {
    override fun toString() = "$documentId:p$page" + (line?.let { ":l$it" } ?: "")
}

/** How confident the extractor was. Drives what the Review screen highlights. */
enum class Confidence { HIGH, MEDIUM, LOW;
    companion object {
        fun of(score: Double) = when {
            score >= 0.85 -> HIGH
            score >= 0.60 -> MEDIUM
            else -> LOW
        }
    }
}

enum class MedStatus { ACTIVE, COMPLETED, SUPERSEDED }

data class Medication(
    val generic: String,
    val brand: String? = null,
    val strength: String? = null,
    val form: String? = null,
    val frequency: String? = null,
    val grid: String? = null,
    val durationDays: Int? = null,
    val instruction: String? = null,
    val started: LocalDate? = null,
    val ended: LocalDate? = null,
    val status: MedStatus = MedStatus.ACTIVE,
    val confidence: Confidence = Confidence.HIGH,
    val sources: List<SourceRef> = emptyList(),
    /** Set when the user corrected this at Review. No later merge may overwrite it. */
    val pinned: Boolean = false
) {
    /** Two courses overlap if neither ends before the other starts. */
    fun overlaps(other: Medication): Boolean {
        val aStart = started ?: return true
        val bStart = other.started ?: return true
        val aEnd = ended ?: aStart.plusDays((durationDays ?: 0).toLong())
        val bEnd = other.ended ?: bStart.plusDays((other.durationDays ?: 0).toLong())
        return !aEnd.isBefore(bStart) && !bEnd.isBefore(aStart)
    }

    /** Same drug at the same dose and schedule — a continuation, not a change. */
    fun sameRegimenAs(other: Medication): Boolean =
        generic.equals(other.generic, true) &&
            strength.equals(other.strength, true) &&
            frequency.equals(other.frequency, true)
}

data class LabObservation(
    val testKey: String,
    val displayName: String,
    val value: Double,
    val unit: String? = null,
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val date: LocalDate,
    val confidence: Confidence = Confidence.HIGH,
    val source: SourceRef
) {
    /** null when no reference range was present. We never infer one. */
    val outOfRange: Boolean?
        get() = when {
            refLow == null && refHigh == null -> null
            refLow != null && value < refLow -> true
            refHigh != null && value > refHigh -> true
            else -> false
        }
}

data class Condition(
    val label: String,
    val icd10: String? = null,
    val firstSeen: LocalDate? = null,
    val lastSeen: LocalDate? = null,
    val confidence: Confidence = Confidence.HIGH,
    val sources: List<SourceRef> = emptyList()
)

data class Allergy(
    val substance: String,
    val reaction: String? = null,
    val sources: List<SourceRef> = emptyList()
)

data class Clinician(
    val name: String,
    val specialty: String? = null,
    val regNo: String? = null,
    val seenOn: List<LocalDate> = emptyList(),
    val sources: List<SourceRef> = emptyList()
)

enum class FlagKind {
    /** Two brands of the same generic active at once — the Dolo 650 / Calpol case. */
    DUPLICATE_THERAPY,
    /** Same drug, same window, different strength or schedule. */
    DOSE_CHANGED,
    /** A value outside its stated reference range. */
    LAB_OUT_OF_RANGE
}

/**
 * Something the user should look at. The app flags and cites; it never resolves
 * a clinical conflict on its own.
 */
data class DigestFlag(
    val kind: FlagKind,
    val message: String,
    val sources: List<SourceRef> = emptyList()
)

enum class Section { MEDICATIONS, CONDITIONS, LABS, ALLERGIES, CARE_TEAM }

/** What one document yielded, before it is folded in. */
data class DocumentExtraction(
    val documentId: String,
    val type: DocumentType,
    val documentDate: LocalDate?,
    val rawText: String = "",
    val medications: List<Medication> = emptyList(),
    val labs: List<LabObservation> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val allergies: List<Allergy> = emptyList(),
    val clinicians: List<Clinician> = emptyList()
)

/** The running summary. Rebuilt only by [MergeEngine]; never edited in place. */
data class HealthDigest(
    val medications: List<Medication> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val labs: Map<String, List<LabObservation>> = emptyMap(),
    val allergies: List<Allergy> = emptyList(),
    val careTeam: List<Clinician> = emptyList(),
    val flags: List<DigestFlag> = emptyList(),
    val mergedDocuments: Set<String> = emptySet()
) {
    val activeMedications: List<Medication>
        get() = medications.filter { it.status == MedStatus.ACTIVE }

    /**
     * Identifies a section's content so narration knows what actually changed.
     * A prescription upload must not cause the labs prose to be rewritten.
     */
    fun factHash(section: Section): Int = when (section) {
        Section.MEDICATIONS -> medications.hashCode()
        Section.CONDITIONS -> conditions.hashCode()
        Section.LABS -> labs.hashCode()
        Section.ALLERGIES -> allergies.hashCode()
        Section.CARE_TEAM -> careTeam.hashCode()
    }
}

/** Outcome of folding one document in. */
data class MergeResult(
    val digest: HealthDigest,
    val changes: List<String>,
    val changedSections: Set<Section>,
    val newFlags: List<DigestFlag>
) {
    val isNoOp: Boolean get() = changes.isEmpty()
}
