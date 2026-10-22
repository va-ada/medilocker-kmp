package com.medilocker.crypto

import java.text.Normalizer
import java.util.Locale

/**
 * Equality search over encrypted values. SECURITY.md 7.
 *
 * This exists to replace a specific defect. The previous version needed
 * `where('category', isEqualTo: encryptedCategory)` to work, so its encryption
 * had to be deterministic — which is *why* its IV was fixed at zero. The zero
 * IV was not an oversight, it was load-bearing. Randomising the IV alone would
 * have broken folder browsing, which is presumably why nobody did.
 *
 * The fix is to stop asking the ciphertext to be searchable. The real value is
 * sealed with a random nonce like everything else, and a separate column holds
 *
 *     HMAC-SHA256(K_index, normalize(value))  truncated to 16 bytes
 *
 * Equality queries hit the index column. The ciphertext stays randomised.
 *
 * ## What this leaks — accepted, per SECURITY.md N4
 *
 * Anyone who can read the database sees which records share a value, and how
 * often each value occurs. Against a small guessable domain such as category
 * names, frequency analysis will likely recover the mapping.
 *
 * Accepted for v1 because the alternative — pulling and decrypting every record
 * client-side to filter — does not scale, and because category names are low
 * sensitivity relative to document contents.
 *
 * **Enforced consequence:** blind indexes are permitted on category only. They
 * are forbidden on titles, notes, diagnoses and document text. [IndexedField]
 * is the whole allowed set, and there is no API taking a free-form field name.
 */
class BlindIndex internal constructor(
    private val key: SecureBytes,
    private val primitives: CryptoPrimitives
) {

    /**
     * The only fields that may be blind-indexed.
     *
     * Adding to this enum is a security decision requiring a fresh leakage
     * assessment against SECURITY.md 7.3 — not a routine change.
     */
    enum class IndexedField(internal val label: String) {
        CATEGORY("category")
    }

    fun compute(value: String, field: IndexedField): ByteArray {
        val normalized = normalize(value)
        val input = "${field.label}:$normalized".toByteArray(Charsets.UTF_8)
        return try {
            primitives.hmacSha256(key.material(), input).copyOf(INDEX_BYTES)
        } finally {
            input.wipe()
        }
    }

    /** Hex, for storage in a text column and for use in a SQL equality predicate. */
    fun computeHex(value: String, field: IndexedField): String =
        compute(value, field).joinToString("") { "%02x".format(it) }

    companion object {
        /** 128 bits: collision-safe for this domain, and half the storage of a full digest. */
        const val INDEX_BYTES = 16

        /**
         * Byte-stable forever. Changing this silently orphans every existing
         * index, so a change requires a version bump and a migration — it is
         * not a refactor.
         *
         * NFKC first so that visually identical strings encoded differently
         * (precomposed vs. decomposed) produce one index. This matters here
         * more than in most apps: Devanagari category names can be encoded
         * either way, and without normalisation the same folder name typed on
         * two devices would produce two different folders.
         */
        internal fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .trim()
                .replace(WHITESPACE_RUN, " ")

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
