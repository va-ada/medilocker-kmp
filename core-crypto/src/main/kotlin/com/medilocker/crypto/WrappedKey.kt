package com.medilocker.crypto

/**
 * The envelope. A wrapped DEK blob, exactly as laid out in SECURITY.md 4.
 *
 * ```
 *  offset size field
 *       0    4 magic "MLKW"
 *       4    1 version = 1
 *       5    1 wrapper type (1=password, 2=recovery, 3=device)
 *       6    1 kdf id (1 = Argon2id, 0 = none, for hardware-held keys)
 *       7    4 argon2 memory KiB
 *      11    4 argon2 iterations
 *      15    1 argon2 parallelism
 *      16   16 salt
 *      32   24 nonce
 *      56   32 wrapped DEK ciphertext
 *      88   16 Poly1305 tag
 *     104      total
 * ```
 *
 * Bytes `[0, 32)` are passed as AAD, which authenticates the stored KDF
 * parameters. An attacker with write access to the blob cannot downgrade a
 * vault to `memory = 8 KiB` to make offline guessing cheap: altering any
 * parameter invalidates the tag and the unwrap fails.
 */
class WrappedKey private constructor(val blob: ByteArray) {

    val type: WrapperType get() = WrapperType.fromId(blob[OFF_TYPE].toInt())
    val memoryKib: Int get() = Fmt.getInt(blob, OFF_MEMORY)
    val iterations: Int get() = Fmt.getInt(blob, OFF_ITERATIONS)

    enum class WrapperType(val id: Int) {
        PASSWORD(1), RECOVERY_CODE(2), DEVICE(3);

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id }
                ?: throw MalformedBlob("wrapped key", "unknown wrapper type id $id")
        }
    }

    /** Argon2id cost. Stored in the blob so it can be raised without invalidating vaults. */
    data class KdfParams(
        val memoryKib: Int = DEFAULT_MEMORY_KIB,
        val iterations: Int = DEFAULT_ITERATIONS,
        val parallelism: Int = DEFAULT_PARALLELISM
    ) {
        init {
            require(parallelism == 1) {
                "libsodium's Argon2id is fixed at parallelism 1; the field exists only for forward compatibility"
            }
        }
    }

    companion object {
        private const val MAGIC = "MLKW"
        private const val VERSION = 1

        private const val OFF_VERSION = 4
        private const val OFF_TYPE = 5
        private const val OFF_KDF = 6
        private const val OFF_MEMORY = 7
        private const val OFF_ITERATIONS = 11
        private const val OFF_PARALLELISM = 15
        private const val OFF_SALT = 16
        private const val OFF_NONCE = 32
        private const val OFF_CIPHERTEXT = 56

        /** Header bytes that are authenticated as AAD. */
        private const val AAD_LENGTH = 32

        const val BLOB_LENGTH = 104

        private const val KDF_ARGON2ID = 1
        private const val KDF_NONE = 0

        /** SECURITY.md 3.3. */
        const val DEFAULT_MEMORY_KIB = 64 * 1024
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_PARALLELISM = 1

        /** SECURITY.md 3.3 — 12, not the 6 the previous version allowed. */
        const val MIN_PASSWORD_LENGTH = 12

        fun parse(blob: ByteArray): WrappedKey {
            Fmt.requireLength(blob, BLOB_LENGTH, "wrapped key")
            Fmt.requireMagic(blob, MAGIC, "wrapped key")
            Fmt.requireVersion(blob[OFF_VERSION].toInt(), VERSION, "wrapped key")
            WrapperType.fromId(blob[OFF_TYPE].toInt())
            return WrappedKey(blob.copyOf())
        }

        /**
         * Wrap [dek] under a key derived from [password].
         *
         * @throws WeakParameter if the password is shorter than [MIN_PASSWORD_LENGTH].
         */
        fun wrapWithPassword(
            dek: ByteArray,
            password: CharArray,
            primitives: CryptoPrimitives,
            params: KdfParams = KdfParams()
        ): WrappedKey {
            if (password.size < MIN_PASSWORD_LENGTH) {
                throw WeakParameter(
                    "Password must be at least $MIN_PASSWORD_LENGTH characters. " +
                        "Shorter passwords are brute-forced offline once this blob is stolen."
                )
            }
            return wrapDerived(dek, passwordBytes(password), WrapperType.PASSWORD, primitives, params)
        }

        /** Wrap [dek] under a key derived from a recovery code. */
        fun wrapWithRecoveryCode(
            dek: ByteArray,
            code: RecoveryCode,
            primitives: CryptoPrimitives,
            params: KdfParams = KdfParams()
        ): WrappedKey =
            wrapDerived(dek, code.canonicalBytes(), WrapperType.RECOVERY_CODE, primitives, params)

        /**
         * Wrap [dek] under a key held in StrongBox or the Secure Enclave.
         *
         * No KDF runs: [deviceKey] already comes from hardware. SECURITY.md 9 —
         * this is a convenience path only; the password and recovery wrappers
         * remain the sources of truth and this one can always be rebuilt.
         */
        fun wrapWithDeviceKey(
            dek: ByteArray,
            deviceKey: ByteArray,
            primitives: CryptoPrimitives
        ): WrappedKey {
            require(deviceKey.size == CryptoPrimitives.KEY_BYTES) {
                "device key must be ${CryptoPrimitives.KEY_BYTES} bytes"
            }
            val header = header(WrapperType.DEVICE, KDF_NONE, KdfParams(), ByteArray(CryptoPrimitives.SALT_BYTES))
            return seal(dek, deviceKey, header, primitives)
        }

        private fun wrapDerived(
            dek: ByteArray,
            secret: ByteArray,
            type: WrapperType,
            primitives: CryptoPrimitives,
            params: KdfParams
        ): WrappedKey {
            require(dek.size == CryptoPrimitives.KEY_BYTES) {
                "DEK must be ${CryptoPrimitives.KEY_BYTES} bytes"
            }
            val salt = primitives.randomBytes(CryptoPrimitives.SALT_BYTES)
            val header = header(type, KDF_ARGON2ID, params, salt)
            val kek = primitives.argon2id(
                secret, salt, params.memoryKib, params.iterations, CryptoPrimitives.KEY_BYTES
            )
            try {
                return seal(dek, kek, header, primitives)
            } finally {
                kek.wipe()
                secret.wipe()
            }
        }

        private fun seal(
            dek: ByteArray,
            kek: ByteArray,
            header: ByteArray,
            primitives: CryptoPrimitives
        ): WrappedKey {
            val nonce = primitives.randomBytes(CryptoPrimitives.NONCE_BYTES)
            val aad = header.copyOf(AAD_LENGTH)
            val sealed = primitives.aeadEncrypt(kek, nonce, dek, aad)

            val blob = ByteArray(BLOB_LENGTH)
            header.copyInto(blob, 0, 0, AAD_LENGTH)
            nonce.copyInto(blob, OFF_NONCE)
            sealed.copyInto(blob, OFF_CIPHERTEXT)
            return WrappedKey(blob)
        }

        private fun header(
            type: WrapperType,
            kdfId: Int,
            params: KdfParams,
            salt: ByteArray
        ): ByteArray {
            val header = ByteArray(AAD_LENGTH)
            Fmt.magicBytes(MAGIC).copyInto(header, 0)
            header[OFF_VERSION] = VERSION.toByte()
            header[OFF_TYPE] = type.id.toByte()
            header[OFF_KDF] = kdfId.toByte()
            Fmt.putInt(header, OFF_MEMORY, params.memoryKib)
            Fmt.putInt(header, OFF_ITERATIONS, params.iterations)
            header[OFF_PARALLELISM] = params.parallelism.toByte()
            salt.copyInto(header, OFF_SALT)
            return header
        }

        private fun passwordBytes(password: CharArray): ByteArray {
            // Encode without ever materialising a String — Strings are immutable
            // and interned, so they cannot be wiped. SECURITY.md 8.1.
            val buffer = java.nio.CharBuffer.wrap(password)
            val encoded = Charsets.UTF_8.encode(buffer)
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            if (encoded.hasArray()) encoded.array().fill(0)
            return bytes
        }
    }

    /** @throws VaultUnlockFailed if [password] is wrong. */
    fun unwrapWithPassword(password: CharArray, primitives: CryptoPrimitives): SecureBytes {
        expectType(WrapperType.PASSWORD)
        return unwrapDerived(passwordBytes(password), primitives, "password")
    }

    /** @throws VaultUnlockFailed if [code] is wrong. */
    fun unwrapWithRecoveryCode(code: RecoveryCode, primitives: CryptoPrimitives): SecureBytes {
        expectType(WrapperType.RECOVERY_CODE)
        return unwrapDerived(code.canonicalBytes(), primitives, "recovery code")
    }

    /** @throws VaultUnlockFailed if the hardware key no longer matches. */
    fun unwrapWithDeviceKey(deviceKey: ByteArray, primitives: CryptoPrimitives): SecureBytes {
        expectType(WrapperType.DEVICE)
        return open(deviceKey, primitives, "device key")
    }

    private fun unwrapDerived(
        secret: ByteArray,
        primitives: CryptoPrimitives,
        label: String
    ): SecureBytes {
        val salt = blob.copyOfRange(OFF_SALT, OFF_SALT + CryptoPrimitives.SALT_BYTES)
        val kek = primitives.argon2id(
            secret, salt, memoryKib, iterations, CryptoPrimitives.KEY_BYTES
        )
        try {
            return open(kek, primitives, label)
        } finally {
            kek.wipe()
            secret.wipe()
        }
    }

    private fun open(kek: ByteArray, primitives: CryptoPrimitives, label: String): SecureBytes {
        val nonce = blob.copyOfRange(OFF_NONCE, OFF_NONCE + CryptoPrimitives.NONCE_BYTES)
        val sealed = blob.copyOfRange(OFF_CIPHERTEXT, BLOB_LENGTH)
        val aad = blob.copyOf(AAD_LENGTH)
        return try {
            SecureBytes(primitives.aeadDecrypt(kek, nonce, sealed, aad, "wrapped key"))
        } catch (_: AuthenticationFailure) {
            // Deliberately does not distinguish "wrong credential" from
            // "tampered blob" to the caller — both mean the same thing here.
            throw VaultUnlockFailed(label)
        }
    }

    private fun expectType(expected: WrapperType) {
        if (type != expected) {
            throw MalformedBlob("wrapped key", "expected a $expected wrapper but this blob is $type")
        }
    }
}
