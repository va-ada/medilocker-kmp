package com.medilocker.crypto

/**
 * The vault: the only way to obtain a working cipher.
 *
 * Everything above this layer goes through [Vault]. There is no API anywhere
 * that hands out a raw key, and no cipher can be constructed without an
 * unlocked vault — so "encrypt this without binding it" and "store this
 * unencrypted" are not reachable states.
 *
 * ## Lifecycle
 *
 * ```
 *  create(password)  ──>  NewVault { vault, passwordWrapper, recoveryWrapper, recoveryCode }
 *                                        │              │
 *                          persist these two blobs; show the code once
 *
 *  unlockWithPassword(password, passwordWrapper)  ──> Vault
 *  unlockWithRecoveryCode(code, recoveryWrapper)  ──> Vault
 *  unlockWithDeviceKey(hwKey, deviceWrapper)      ──> Vault
 * ```
 *
 * Compare against the previous version, where the key was generated once at
 * signup and stored on one device with no derivation and no backup: a reinstall
 * or a second device meant every record became permanently unreadable, silently.
 * Here the DEK is recoverable from any wrapper, and a password change re-wraps
 * it without re-encrypting a single record.
 */
class Vault private constructor(
    private val dek: SecureBytes,
    private val primitives: CryptoPrimitives
) : AutoCloseable {

    /** Result of creating a vault. The recovery code is shown once and never persisted. */
    class NewVault(
        val vault: Vault,
        val passwordWrapper: WrappedKey,
        val recoveryWrapper: WrappedKey,
        val recoveryCode: RecoveryCode
    )

    // Subkeys are derived on first use and held for the unlocked lifetime
    // rather than re-derived per call. All are zeroized by close().
    private val subkeys = LinkedHashMap<KeyPurpose, SecureBytes>()

    @Synchronized
    private fun subkey(purpose: KeyPurpose): SecureBytes =
        subkeys.getOrPut(purpose) { KeyDerivation.subkey(dek.material(), purpose, primitives) }

    fun fieldCipher(): FieldCipher = FieldCipher(subkey(KeyPurpose.FIELD), primitives)

    fun fileCipher(chunkSize: Int = FileCipher.DEFAULT_CHUNK_SIZE): FileCipher =
        FileCipher(subkey(KeyPurpose.FILE), primitives, chunkSize)

    fun blindIndex(): BlindIndex = BlindIndex(subkey(KeyPurpose.BLIND_INDEX), primitives)

    /**
     * SQLCipher passphrase. Caller owns the returned bytes and must destroy them.
     * Derived on demand rather than held, since it is needed only at DB open.
     */
    fun databasePassphrase(): SecureBytes =
        KeyDerivation.subkey(dek.material(), KeyPurpose.DATABASE, primitives)

    /**
     * Re-wrap the DEK under a new password.
     *
     * **No records are re-encrypted.** That is the point of the envelope: the
     * DEK is unchanged, only the thing wrapping it changes. Persist the returned
     * blob in place of the old one.
     */
    fun rewrapWithNewPassword(
        newPassword: CharArray,
        params: WrappedKey.KdfParams = WrappedKey.KdfParams()
    ): WrappedKey = WrappedKey.wrapWithPassword(dek.material(), newPassword, primitives, params)

    /** Re-wrap under a fresh recovery code, invalidating the previous one. */
    fun rewrapWithNewRecoveryCode(
        params: WrappedKey.KdfParams = WrappedKey.KdfParams()
    ): Pair<WrappedKey, RecoveryCode> {
        val code = RecoveryCode.generate(primitives)
        return WrappedKey.wrapWithRecoveryCode(dek.material(), code, primitives, params) to code
    }

    /**
     * Bind this vault to hardware-held key material for biometric fast-unlock.
     * [deviceKey] must come from StrongBox or the Secure Enclave — SECURITY.md 9.
     */
    fun bindToDevice(deviceKey: ByteArray): WrappedKey =
        WrappedKey.wrapWithDeviceKey(dek.material(), deviceKey, primitives)

    /**
     * Drop all key material. The vault is unusable afterwards, and every cipher
     * previously handed out fails with [KeyDestroyed] rather than continuing to
     * work off a stale reference.
     */
    @Synchronized
    override fun close() {
        subkeys.values.forEach { it.destroy() }
        subkeys.clear()
        dek.destroy()
    }

    companion object {

        /**
         * Create a brand new vault.
         *
         * Generates a random DEK and produces two independent wrappers around
         * it. Both blobs must be persisted; the recovery code must be shown to
         * the user exactly once and never stored.
         *
         * @throws WeakParameter if the password is under 12 characters.
         */
        fun create(
            password: CharArray,
            primitives: CryptoPrimitives,
            params: WrappedKey.KdfParams = WrappedKey.KdfParams()
        ): NewVault {
            val dekBytes = primitives.randomBytes(CryptoPrimitives.KEY_BYTES)
            val recoveryCode = RecoveryCode.generate(primitives)

            // Wrap before handing the DEK to the Vault, so a failure here (a
            // password that is too short, say) leaves nothing half-constructed.
            val passwordWrapper = try {
                WrappedKey.wrapWithPassword(dekBytes, password, primitives, params)
            } catch (e: Throwable) {
                dekBytes.wipe()
                recoveryCode.destroy()
                throw e
            }
            val recoveryWrapper = WrappedKey.wrapWithRecoveryCode(dekBytes, recoveryCode, primitives, params)

            return NewVault(
                vault = Vault(SecureBytes(dekBytes), primitives),
                passwordWrapper = passwordWrapper,
                recoveryWrapper = recoveryWrapper,
                recoveryCode = recoveryCode
            )
        }

        /** @throws VaultUnlockFailed if the password is wrong. */
        fun unlockWithPassword(
            password: CharArray,
            wrapper: WrappedKey,
            primitives: CryptoPrimitives
        ): Vault = Vault(wrapper.unwrapWithPassword(password, primitives), primitives)

        /** @throws VaultUnlockFailed if the recovery code is wrong. */
        fun unlockWithRecoveryCode(
            code: RecoveryCode,
            wrapper: WrappedKey,
            primitives: CryptoPrimitives
        ): Vault = Vault(wrapper.unwrapWithRecoveryCode(code, primitives), primitives)

        /** @throws VaultUnlockFailed if the hardware key no longer matches. */
        fun unlockWithDeviceKey(
            deviceKey: ByteArray,
            wrapper: WrappedKey,
            primitives: CryptoPrimitives
        ): Vault = Vault(wrapper.unwrapWithDeviceKey(deviceKey, primitives), primitives)
    }
}
