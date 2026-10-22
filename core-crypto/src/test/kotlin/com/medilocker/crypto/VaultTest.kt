package com.medilocker.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VaultTest {

    private val p = T.primitives

    private fun newVault() = Vault.create(T.password(), p, T.fastKdf)

    // ---- The defect this class exists to fix --------------------------------

    @Test
    fun `a second device unlocks the same vault with only the password`() {
        // The previous version generated the key once at signup and stored it
        // on one device. A reinstall or a second device left every record
        // permanently unreadable. This is the regression test for that.
        val created = newVault()
        val recordId = "rec-1"
        val sealed = created.vault.fieldCipher()
            .encrypt("Chest X-Ray 12 Jan", recordId, "title")
        created.vault.close()

        // Nothing carried over but the password and the stored wrapper blob.
        val storedBlob = created.passwordWrapper.blob
        val onSecondDevice = Vault.unlockWithPassword(
            T.password(), WrappedKey.parse(storedBlob), p
        )

        assertEquals(
            "Chest X-Ray 12 Jan",
            onSecondDevice.fieldCipher().decrypt(sealed, recordId, "title")
        )
        onSecondDevice.close()
    }

    @Test
    fun `changing the password does not re-encrypt any record`() {
        val created = newVault()
        val recordId = "rec-2"
        val sealed = created.vault.fieldCipher()
            .encrypt("Haemoglobin 13.4 g/dL", recordId, "notes")

        val newWrapper = created.vault.rewrapWithNewPassword(
            T.password("an-entirely-different-passphrase"), T.fastKdf
        )
        created.vault.close()

        val reopened = Vault.unlockWithPassword(
            T.password("an-entirely-different-passphrase"), newWrapper, p
        )

        // The ciphertext produced before the password change is byte-identical
        // and still opens — the DEK never changed, only its wrapper did.
        assertEquals(
            "Haemoglobin 13.4 g/dL",
            reopened.fieldCipher().decrypt(sealed, recordId, "notes")
        )
        reopened.close()
    }

    @Test
    fun `the old password stops working after a change`() {
        val created = newVault()
        val newWrapper = created.vault.rewrapWithNewPassword(
            T.password("an-entirely-different-passphrase"), T.fastKdf
        )
        created.vault.close()

        assertFailsWith<VaultUnlockFailed> {
            Vault.unlockWithPassword(T.password(), newWrapper, p)
        }
    }

    // ---- Recovery -----------------------------------------------------------

    @Test
    fun `the recovery code opens the same vault as the password`() {
        val created = newVault()
        val sealed = created.vault.fieldCipher().encrypt("Vitamin D 22 ng/mL", "rec-3", "notes")
        val printed = created.recoveryCode.formatted(p)
        created.vault.close()

        // The user typed the code back in from paper.
        val typedBack = RecoveryCode.parse(printed, p)
        val recovered = Vault.unlockWithRecoveryCode(typedBack, created.recoveryWrapper, p)

        assertEquals("Vitamin D 22 ng/mL", recovered.fieldCipher().decrypt(sealed, "rec-3", "notes"))
        recovered.close()
    }

    @Test
    fun `recovery code survives realistic transcription damage`() {
        val created = newVault()
        val printed = created.recoveryCode.formatted(p)

        // Lowercased, spaces instead of dashes, and the Crockford confusables
        // a person would actually write down.
        val mangled = printed.lowercase().replace("-", " ")
        val parsed = RecoveryCode.parse(mangled, p)

        assertContentEquals(created.recoveryCode.canonicalBytes(), parsed.canonicalBytes())
        created.vault.close()
    }

    @Test
    fun `a mistyped recovery code is caught by the checksum`() {
        val created = newVault()
        val printed = created.recoveryCode.formatted(p)
        created.vault.close()

        // Change one character in the body to something else in the alphabet.
        val chars = printed.toCharArray()
        val idx = chars.indexOfFirst { it in "23456789" }
        chars[idx] = if (chars[idx] == '2') '3' else '2'

        assertFailsWith<MalformedBlob> { RecoveryCode.parse(String(chars), p) }
    }

    @Test
    fun `recovery code is rejected by the password wrapper and vice versa`() {
        val created = newVault()
        assertFailsWith<MalformedBlob> {
            created.passwordWrapper.unwrapWithRecoveryCode(created.recoveryCode, p)
        }
        assertFailsWith<MalformedBlob> {
            created.recoveryWrapper.unwrapWithPassword(T.password(), p)
        }
        created.vault.close()
    }

    // ---- Unlock failures ----------------------------------------------------

    @Test
    fun `a wrong password is rejected rather than yielding a wrong key`() {
        val created = newVault()
        created.vault.close()
        assertFailsWith<VaultUnlockFailed> {
            Vault.unlockWithPassword(T.password("not-the-right-passphrase"), created.passwordWrapper, p)
        }
    }

    @Test
    fun `a password under twelve characters is refused at creation`() {
        assertFailsWith<WeakParameter> { Vault.create(T.password("short"), p, T.fastKdf) }
        assertFailsWith<WeakParameter> { Vault.create(T.password("elevenchars"), p, T.fastKdf) }
    }

    // ---- Wrapper blob integrity ---------------------------------------------

    @Test
    fun `kdf parameters cannot be downgraded in the stored blob`() {
        // Without the header being authenticated as AAD, an attacker could
        // rewrite memory to 8 KiB and make offline guessing trivial.
        val created = newVault()
        created.vault.close()

        val tampered = created.passwordWrapper.blob.copyOf()
        Fmt.putInt(tampered, 7, 8) // memory KiB -> 8

        val parsed = WrappedKey.parse(tampered)
        assertEquals(8, parsed.memoryKib, "the test did not actually modify the blob")

        // It fails at the floor check before it can even reach the tag.
        assertFailsWith<CryptoException> { parsed.unwrapWithPassword(T.password(), p) }
    }

    @Test
    fun `a flipped bit anywhere in the wrapper blob is detected`() {
        val created = newVault()
        created.vault.close()
        val blob = created.passwordWrapper.blob

        // Sample across each region: header, salt, nonce, ciphertext, tag.
        for (index in listOf(7, 11, 20, 40, 60, 90, 103)) {
            val tampered = blob.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertFailsWith<CryptoException>("byte $index was not authenticated") {
                WrappedKey.parse(tampered).unwrapWithPassword(T.password(), p)
            }
        }
    }

    @Test
    fun `an inflated kdf cost in the stored blob is refused without attempting it`() {
        // Regression for a real defect: argon2id originally had a floor but no
        // ceiling, so flipping the high byte of the memory field asked libsodium
        // for 16 GiB and hung the unlock indefinitely. The bound must be checked
        // before the KDF is invoked, and must return quickly.
        val created = newVault()
        created.vault.close()

        val inflated = created.passwordWrapper.blob.copyOf()
        Fmt.putInt(inflated, 7, 16_842_752) // 16 GiB in KiB

        val startedAt = System.nanoTime()
        assertFailsWith<WeakParameter> {
            WrappedKey.parse(inflated).unwrapWithPassword(T.password(), p)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMs < 1_000, "rejection took ${elapsedMs}ms — the KDF was attempted anyway")
    }

    @Test
    fun `wrapper blob is exactly the documented size`() {
        val created = newVault()
        created.vault.close()
        assertEquals(104, created.passwordWrapper.blob.size, "SECURITY.md 4 specifies 104 bytes")
    }

    // ---- Device binding -----------------------------------------------------

    @Test
    fun `device key binding round trips and rejects a different device key`() {
        val created = newVault()
        val hardwareKey = p.randomBytes(32)
        val deviceWrapper = created.vault.bindToDevice(hardwareKey)
        val sealed = created.vault.fieldCipher().encrypt("Allergy: penicillin", "rec-4", "notes")
        created.vault.close()

        val viaBiometrics = Vault.unlockWithDeviceKey(hardwareKey, deviceWrapper, p)
        assertEquals("Allergy: penicillin", viaBiometrics.fieldCipher().decrypt(sealed, "rec-4", "notes"))
        viaBiometrics.close()

        // Biometric re-enrolment rotates the hardware key; the wrapper must die.
        assertFailsWith<VaultUnlockFailed> {
            Vault.unlockWithDeviceKey(p.randomBytes(32), deviceWrapper, p)
        }
    }

    // ---- Lifecycle ----------------------------------------------------------

    @Test
    fun `closing the vault destroys keys and disables ciphers already handed out`() {
        val created = newVault()
        val cipher = created.vault.fieldCipher()
        val sealed = cipher.encrypt("still fine", "rec-5", "title")

        created.vault.close()

        assertFailsWith<KeyDestroyed> { cipher.decrypt(sealed, "rec-5", "title") }
        assertFailsWith<KeyDestroyed> { created.vault.fieldCipher() }
    }

    @Test
    fun `two vaults created with the same password have different keys`() {
        val a = Vault.create(T.password(), p, T.fastKdf)
        val b = Vault.create(T.password(), p, T.fastKdf)

        val sealed = a.vault.fieldCipher().encrypt("private", "rec-6", "title")
        assertFailsWith<AuthenticationFailure>("vault B must not read vault A's data") {
            b.vault.fieldCipher().decrypt(sealed, "rec-6", "title")
        }
        assertNotEquals(
            a.passwordWrapper.blob.toList(), b.passwordWrapper.blob.toList()
        )
        a.vault.close(); b.vault.close()
    }

    @Test
    fun `database passphrase is stable across unlocks and distinct from field key`() {
        val created = newVault()
        val first = created.vault.databasePassphrase().material().copyOf()
        created.vault.close()

        val reopened = Vault.unlockWithPassword(T.password(), created.passwordWrapper, p)
        assertContentEquals(first, reopened.databasePassphrase().material())
        assertTrue(first.size == 32)
        reopened.close()
    }
}
