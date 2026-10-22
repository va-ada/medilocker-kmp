package com.medilocker.domain

/**
 * The text files each document produces, and the rendering of the digest.
 *
 * Three artifacts per document, all encrypted at rest like everything else:
 *
 *  - `raw.txt`         — OCR output, kept verbatim so extraction can be re-run
 *                        later with a better model without re-scanning paper.
 *  - `structured.json` — the typed facts, with provenance.
 *  - `card.md`         — the human-readable per-document card.
 *
 * Keeping `raw.txt` matters: extraction improves over the life of the project,
 * and reprocessing an old document must not require the original to be
 * photographed again.
 *
 * Rendering is deterministic string assembly. No model is involved, so the same
 * facts always produce the same text — which is what lets the app show the user
 * a real diff after an upload instead of an unexplained rewrite.
 */
object Artifacts {

    fun rawText(extraction: DocumentExtraction): String = extraction.rawText

    fun structuredJson(extraction: DocumentExtraction): String = buildString {
        append("{\n")
        append("""  "documentId": ${q(extraction.documentId)},""").append('\n')
        append("""  "type": ${q(extraction.type.name)},""").append('\n')
        append("""  "documentDate": ${q(extraction.documentDate?.toString())},""").append('\n')

        append("""  "medications": [""").append('\n')
        extraction.medications.forEachIndexed { i, m ->
            append("    {")
            append(""""generic": ${q(m.generic)}, """)
            append(""""brand": ${q(m.brand)}, """)
            append(""""strength": ${q(m.strength)}, """)
            append(""""form": ${q(m.form)}, """)
            append(""""frequency": ${q(m.frequency)}, """)
            append(""""grid": ${q(m.grid)}, """)
            append(""""durationDays": ${m.durationDays ?: "null"}, """)
            append(""""instruction": ${q(m.instruction)}, """)
            append(""""confidence": ${q(m.confidence.name)}, """)
            append(""""sources": [${m.sources.joinToString(", ") { q(it.toString()) }}]""")
            append("}").append(if (i < extraction.medications.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")

        append("""  "labs": [""").append('\n')
        extraction.labs.forEachIndexed { i, l ->
            append("    {")
            append(""""test": ${q(l.testKey)}, """)
            append(""""name": ${q(l.displayName)}, """)
            append(""""value": ${l.value}, """)
            append(""""unit": ${q(l.unit)}, """)
            append(""""refLow": ${l.refLow ?: "null"}, """)
            append(""""refHigh": ${l.refHigh ?: "null"}, """)
            append(""""date": ${q(l.date.toString())}, """)
            append(""""outOfRange": ${l.outOfRange ?: "null"}, """)
            append(""""source": ${q(l.source.toString())}""")
            append("}").append(if (i < extraction.labs.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")

        append("""  "conditions": [""")
        append(extraction.conditions.joinToString(", ") {
            """{"label": ${q(it.label)}, "icd10": ${q(it.icd10)}}"""
        })
        append("],\n")

        append("""  "allergies": [""")
        append(extraction.allergies.joinToString(", ") { q(it.substance) })
        append("],\n")

        append("""  "clinicians": [""")
        append(extraction.clinicians.joinToString(", ") {
            """{"name": ${q(it.name)}, "specialty": ${q(it.specialty)}, "regNo": ${q(it.regNo)}}"""
        })
        append("]\n}")
    }

    /** The per-document card the user reads at Record Detail. */
    fun documentCard(extraction: DocumentExtraction): String = buildString {
        append("# ").append(title(extraction.type)).append('\n')
        extraction.documentDate?.let { append("**Date:** ").append(it).append("  \n") }
        extraction.clinicians.firstOrNull()?.let { doctor ->
            append("**Seen by:** Dr. ").append(doctor.name)
            doctor.specialty?.let { append(" · ").append(it) }
            append("  \n")
        }
        append('\n')

        if (extraction.conditions.isNotEmpty()) {
            append("## Diagnosis\n")
            extraction.conditions.forEach { c ->
                append("- ").append(c.label)
                c.icd10?.let { append(" `").append(it).append("`") }
                append('\n')
            }
            append('\n')
        }

        if (extraction.medications.isNotEmpty()) {
            append("## Medicines (").append(extraction.medications.size).append(")\n\n")
            append("| # | Medicine | Strength | Schedule | Duration |\n")
            append("|---|---|---|---|---|\n")
            extraction.medications.forEachIndexed { i, m ->
                append("| ").append(i + 1)
                append(" | ").append(m.describe().substringBefore(" ", m.brand ?: m.generic).let { m.brand ?: m.generic })
                if (m.brand != null) append(" _(").append(m.generic).append(")_")
                append(" | ").append(m.strength ?: "—")
                append(" | ").append(listOfNotNull(m.frequency, m.grid?.let { "($it)" }).joinToString(" ").ifBlank { "—" })
                append(" | ").append(m.durationDays?.let { "$it days" } ?: "—")
                append(" |\n")
            }
            append('\n')
            val uncertain = extraction.medications.filter { it.confidence != Confidence.HIGH }
            if (uncertain.isNotEmpty()) {
                append("> ").append(uncertain.size)
                append(" entr").append(if (uncertain.size == 1) "y needs" else "ies need")
                append(" checking: ")
                append(uncertain.joinToString(", ") { it.brand ?: it.generic })
                append("\n\n")
            }
        }

        if (extraction.labs.isNotEmpty()) {
            append("## Results (").append(extraction.labs.size).append(")\n\n")
            append("| Test | Value | Range | |\n|---|---|---|---|\n")
            extraction.labs.forEach { l ->
                append("| ").append(l.displayName)
                append(" | ").append(l.value).append(l.unit ?: "")
                append(" | ").append(
                    if (l.refLow != null || l.refHigh != null) "${l.refLow ?: "?"}–${l.refHigh ?: "?"}" else "not stated"
                )
                append(" | ").append(
                    when (l.outOfRange) {
                        true -> "**outside range**"
                        false -> "in range"
                        null -> "—"
                    }
                )
                append(" |\n")
            }
            append('\n')
        }

        append("---\n")
        append("_Read automatically from your document. Please check it against the original ")
        append("before relying on it. This is not medical advice._\n")
    }

    /**
     * The Health Digest as text — for export, sharing and the plain-text view.
     *
     * Every claim carries its source inline. In v2 the on-device model narrates
     * these same sections in prose, constrained to the fact ids present here, so
     * it cannot introduce anything this rendering does not already contain.
     */
    fun digestSummary(digest: HealthDigest): String = buildString {
        append("# Health Summary\n\n")
        append("Assembled from ").append(digest.mergedDocuments.size)
        append(if (digest.mergedDocuments.size == 1) " document" else " documents").append(".\n\n")

        if (digest.flags.isNotEmpty()) {
            append("## Needs your attention\n\n")
            digest.flags.forEach { f ->
                append("- **").append(label(f.kind)).append("** — ").append(f.message)
                append(" _[").append(f.sources.joinToString(", ") { it.toString() }).append("]_\n")
            }
            append('\n')
        }

        val active = digest.activeMedications
        append("## Current medicines")
        if (active.isEmpty()) append("\n\nNone recorded.\n\n") else {
            append(" (").append(active.size).append(")\n\n")
            active.forEach { m ->
                append("- ").append(m.describe())
                m.durationDays?.let { append(" for ").append(it).append(" days") }
                append(" _[").append(m.sources.joinToString(", ") { it.toString() }).append("]_\n")
            }
            append('\n')
        }

        val past = digest.medications.filter { it.status != MedStatus.ACTIVE }
        if (past.isNotEmpty()) {
            append("## Past medicines (").append(past.size).append(")\n\n")
            past.forEach { m ->
                append("- ").append(m.describe())
                append(" — ").append(m.status.name.lowercase()).append('\n')
            }
            append('\n')
        }

        if (digest.conditions.isNotEmpty()) {
            append("## Conditions\n\n")
            digest.conditions.forEach { c ->
                append("- ").append(c.label)
                c.icd10?.let { append(" `").append(it).append("`") }
                c.firstSeen?.let { append(" — first seen ").append(it) }
                append(" _[").append(c.sources.joinToString(", ") { it.toString() }).append("]_\n")
            }
            append('\n')
        }

        if (digest.labs.isNotEmpty()) {
            append("## Results over time\n\n")
            digest.labs.forEach { (_, series) ->
                val latest = series.maxByOrNull { it.date } ?: return@forEach
                append("### ").append(latest.displayName).append('\n')
                series.sortedBy { it.date }.forEach { o ->
                    append("- ").append(o.date).append(": ").append(o.value).append(o.unit ?: "")
                    if (o.outOfRange == true) append(" **(outside range)**")
                    append(" _[").append(o.source).append("]_\n")
                }
                if (series.size >= 2) {
                    val first = series.minByOrNull { it.date }!!
                    val direction = when {
                        latest.value > first.value -> "risen"
                        latest.value < first.value -> "fallen"
                        else -> "stayed level"
                    }
                    append("- Trend: ").append(direction)
                    append(" from ").append(first.value).append(" to ").append(latest.value)
                    append(" between ").append(first.date).append(" and ").append(latest.date).append(".\n")
                }
                append('\n')
            }
        }

        if (digest.allergies.isNotEmpty()) {
            append("## Allergies\n\n")
            digest.allergies.forEach { append("- ").append(it.substance).append('\n') }
            append('\n')
        }

        if (digest.careTeam.isNotEmpty()) {
            append("## Doctors seen\n\n")
            digest.careTeam.forEach { c ->
                append("- Dr. ").append(c.name)
                c.specialty?.let { append(" · ").append(it) }
                if (c.seenOn.isNotEmpty()) append(" — ").append(c.seenOn.size).append(" visit(s)")
                append('\n')
            }
            append('\n')
        }

        append("---\n")
        append("_Assembled automatically from your own documents. Every line above cites the ")
        append("document it came from. This is a summary of your records, not medical advice — ")
        append("please consult a qualified doctor._\n")
    }

    private fun label(kind: FlagKind) = when (kind) {
        FlagKind.DUPLICATE_THERAPY -> "Possible duplicate medicine"
        FlagKind.DOSE_CHANGED -> "Dose changed"
        FlagKind.LAB_OUT_OF_RANGE -> "Result outside range"
    }

    private fun title(type: DocumentType) = when (type) {
        DocumentType.PRESCRIPTION -> "Prescription"
        DocumentType.LAB_REPORT -> "Lab Report"
        DocumentType.DISCHARGE_SUMMARY -> "Discharge Summary"
        DocumentType.RADIOLOGY -> "Radiology Report"
        DocumentType.VACCINATION -> "Vaccination Record"
        DocumentType.BILL -> "Bill"
        DocumentType.OTHER -> "Document"
    }

    private fun q(value: String?): String =
        if (value == null) "null" else "\"" + value
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t") + "\""
}
