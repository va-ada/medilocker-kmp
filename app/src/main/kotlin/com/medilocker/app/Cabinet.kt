package com.medilocker.app

import android.content.Context
import org.json.JSONObject

/**
 * Symptom → generics lookup for the medicine cabinet.
 *
 * The index was built OFFLINE from real openFDA label text: a hand-curated
 * list of common self-medication generics, each kept only if its own label
 * mentions the symptom. The app never generates a recommendation — it answers
 * "which of the medicines YOU ALREADY OWN carry a label mentioning this?"
 *
 * Antibiotics and antivirals were excluded at index build time and are also
 * name-guarded here, so a prescription-only leftover can never surface as a
 * symptom match. Leftover antibiotics are how resistance happens.
 */
class Indications(context: Context) {

    private val index: Map<String, List<String>> = run {
        val o = JSONObject(context.assets.open("indications.json").bufferedReader().readText())
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k); (0 until a.length()).map { a.getString(it) }
        }
    }

    /** Indian ↔ US naming: the cabinet stores whatever the prescription said. */
    private val aliases = mapOf(
        "paracetamol" to "acetaminophen",
        "acetaminophen" to "paracetamol"
    )

    private val rxOnlyGuard = Regex(
        "(cillin|mycin$|thromycin|floxacin|^cef|cephalexin|cycline$|oseltamivir|zanamivir|acyclovir|aciclovir|isoniazid|rifampi)"
    )

    fun neverSurface(generic: String) = rxOnlyGuard.containsMatchIn(generic.lowercase())

    /** Symptoms whose label lists mention this generic (or its alias). */
    fun matches(symptomQuery: String, generic: String): Boolean {
        val g = generic.lowercase().trim()
        val names = setOf(g, aliases[g] ?: g)
        val q = symptomQuery.lowercase().trim()
        val lists = index.filterKeys { it.contains(q) || q.contains(it) }.values.flatten()
        return lists.any { it in names }
    }

    fun knownSymptoms(): List<String> = index.keys.sorted()
}
