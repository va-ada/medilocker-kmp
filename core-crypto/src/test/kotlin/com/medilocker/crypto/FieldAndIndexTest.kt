package com.medilocker.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FieldCipherTest {

    private val p = T.primitives
    private val created = Vault.create(T.password(), p, T.fastKdf)
    private val cipher = created.vault.fieldCipher()

    @Test
    fun `round trips unicode including devanagari`() {
        // Indian prescriptions are code-mixed; the vault must not mangle them.
        val text = "रोगी को तेज़ बुखार — Paracetamol 500mg BD"
        val sealed = cipher.encrypt(text, "rec-a", "notes")
        assertEquals(text, cipher.decrypt(sealed, "rec-a", "notes"))
    }

    @Test
    fun `the same plaintext encrypts differently every time`() {
        // Direct regression on the zero-IV defect: the previous version produced
        // identical ciphertext for identical plaintext, which leaked equality
        // across every record in the database.
        val seen = HashSet<String>()
        repeat(200) {
            val blob = cipher.encrypt("Lab Reports", "rec-b", "category")
            assertTrue(
                seen.add(blob.joinToString("") { b -> "%02x".format(b) }),
                "identical ciphertext produced twice — encryption is deterministic"
            )
        }
    }

    // ---- T6: ciphertext relocation ------------------------------------------

    @Test
    fun `a field ciphertext will not open under a different field name`() {
        val sealed = cipher.encrypt("Prescriptions", "rec-c", "category")
        assertFailsWith<AuthenticationFailure> {
            cipher.decrypt(sealed, "rec-c", "title")
        }
    }

    @Test
    fun `a field ciphertext will not open under a different record`() {
        // The attack that matters clinically: moving one patient's diagnosis
        // onto another patient's record, where every value stays "validly
        // encrypted" and nothing looks wrong.
        val sealed = cipher.encrypt("Type 2 diabetes", "patient-a", "diagnosis")
        assertFailsWith<AuthenticationFailure> {
            cipher.decrypt(sealed, "patient-b", "diagnosis")
        }
    }

    @Test
    fun `record id and field name cannot be made ambiguous by a colon`() {
        // Without this, ("a:b","c") and ("a","b:c") would build the same AAD
        // and relocation between them would succeed.
        assertFailsWith<IllegalArgumentException> { cipher.encrypt("x", "rec:a", "title") }
        assertFailsWith<IllegalArgumentException> { cipher.encrypt("x", "rec", "ti:tle") }
    }

    @Test
    fun `blank binding identifiers are refused`() {
        assertFailsWith<IllegalArgumentException> { cipher.encrypt("x", "", "title") }
        assertFailsWith<IllegalArgumentException> { cipher.encrypt("x", "rec", "  ") }
    }

    // ---- Tampering and malformed input --------------------------------------

    @Test
    fun `every byte of the sealed field is authenticated`() {
        val sealed = cipher.encrypt("Serum creatinine 1.1", "rec-d", "notes")
        for (index in 5 until sealed.size) {   // skip magic+version, covered below
            val tampered = sealed.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertFailsWith<CryptoException>("byte $index was not authenticated") {
                cipher.decrypt(tampered, "rec-d", "notes")
            }
        }
    }

    @Test
    fun `garbage and truncated blobs are rejected, never returned as plaintext`() {
        // The previous version returned the input on decryption failure, which
        // displayed base64 to users as their record title.
        assertFailsWith<MalformedBlob> { cipher.decrypt(ByteArray(0), "rec-e", "title") }
        assertFailsWith<MalformedBlob> { cipher.decrypt(ByteArray(10), "rec-e", "title") }
        assertFailsWith<MalformedBlob> {
            cipher.decrypt("this is not encrypted at all".toByteArray(), "rec-e", "title")
        }

        val sealed = cipher.encrypt("x", "rec-e", "title")
        assertFailsWith<CryptoException> {
            cipher.decrypt(sealed.copyOf(sealed.size - 1), "rec-e", "title")
        }
    }

    @Test
    fun `an unknown format version is refused rather than guessed at`() {
        val sealed = cipher.encrypt("x", "rec-f", "title")
        sealed[4] = 99
        assertFailsWith<MalformedBlob> { cipher.decrypt(sealed, "rec-f", "title") }
    }

    @Test
    fun `empty string round trips`() {
        assertEquals("", cipher.decrypt(cipher.encrypt("", "rec-g", "notes"), "rec-g", "notes"))
    }
}

class BlindIndexTest {

    private val p = T.primitives
    private val created = Vault.create(T.password(), p, T.fastKdf)
    private val index = created.vault.blindIndex()
    private val field = BlindIndex.IndexedField.CATEGORY

    @Test
    fun `equal values produce equal indexes so queries work`() {
        assertEquals(
            index.computeHex("Lab Reports", field),
            index.computeHex("Lab Reports", field)
        )
    }

    @Test
    fun `different values produce different indexes`() {
        assertNotEquals(
            index.computeHex("Lab Reports", field),
            index.computeHex("Prescriptions", field)
        )
    }

    @Test
    fun `normalisation folds case, padding and internal whitespace`() {
        val canonical = index.computeHex("Lab Reports", field)
        listOf("  Lab Reports  ", "lab reports", "LAB REPORTS", "Lab   Reports").forEach {
            assertEquals(canonical, index.computeHex(it, field), "'$it' should normalise to the same index")
        }
    }

    @Test
    fun `devanagari normalises across unicode composition forms`() {
        // The same folder name typed on two devices can arrive precomposed or
        // decomposed. Without NFKC these would become two different folders.
        val precomposed = "नि"          // नि  as base + vowel sign
        val compatible = "नि"
        assertEquals(
            index.computeHex(precomposed, field),
            index.computeHex(compatible, field)
        )
        // And a real round trip through the normaliser is stable.
        assertEquals(
            index.computeHex("दवाइयाँ", field),
            index.computeHex("  दवाइयाँ ", field)
        )
    }

    @Test
    fun `index is the documented width`() {
        assertEquals(16, index.compute("Lab Reports", field).size)
        assertEquals(32, index.computeHex("Lab Reports", field).length)
    }

    @Test
    fun `two vaults produce different indexes for the same value`() {
        // Otherwise the index would be a global identifier, letting anyone with
        // the database correlate the same category across separate users.
        val other = Vault.create(T.password(), p, T.fastKdf)
        assertNotEquals(
            index.computeHex("Lab Reports", field),
            other.vault.blindIndex().computeHex("Lab Reports", field)
        )
        other.vault.close()
    }
}
