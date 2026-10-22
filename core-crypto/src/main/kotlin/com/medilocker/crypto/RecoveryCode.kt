package com.medilocker.crypto

/**
 * The escape hatch for a forgotten password. SECURITY.md 3.5.
 *
 * 160 bits of entropy in Crockford base32 — an alphabet that omits I, L, O and
 * U so it cannot be mis-transcribed from paper, and that folds the characters
 * people confuse anyway (I and L read as 1, O reads as 0).
 *
 * Displayed once at vault creation, never stored by the app in any form. What
 * persists is only the wrapped-DEK blob it opens.
 *
 * At 160 bits the code is not guessable, so the Argon2id wrapper around it is
 * defence in depth rather than the barrier — unlike the password wrapper, where
 * Argon2id is doing the real work.
 */
class RecoveryCode private constructor(private val entropy: ByteArray) {

    /** Grouped for transcription: `RC-XXXX-XXXX-…` with a trailing checksum group. */
    fun formatted(primitives: CryptoPrimitives): String {
        val body = encode(entropy)
        val check = checksum(entropy, primitives)
        return "RC-" + (body + check).chunked(GROUP).joinToString("-")
    }

    /** The bytes fed to Argon2id. Canonical, so formatting never affects the key. */
    internal fun canonicalBytes(): ByteArray = entropy.copyOf()

    fun destroy() = entropy.wipe()

    override fun toString(): String =
        "RecoveryCode(not rendered — call formatted() explicitly)"

    companion object {
        /** Crockford base32: no I, L, O, U. */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        private const val ENTROPY_BYTES = 20      // 160 bits
        private const val BODY_CHARS = 32         // 160 / 5
        private const val CHECKSUM_CHARS = 4      // 20 bits
        private const val GROUP = 4

        private val CHECKSUM_KEY = "medilocker:v1:recovery-checksum".toByteArray(Charsets.UTF_8)

        fun generate(primitives: CryptoPrimitives): RecoveryCode =
            RecoveryCode(primitives.randomBytes(ENTROPY_BYTES))

        /**
         * Parse user input. Tolerant of case, spacing, grouping and the
         * Crockford confusables, strict about everything else.
         *
         * @throws MalformedBlob if the checksum fails — which catches a
         *   mistyped code before it is spent on a 64 MiB Argon2id derivation
         *   that would fail anyway with a less useful message.
         */
        fun parse(input: String, primitives: CryptoPrimitives): RecoveryCode {
            // Strip every separator first, then remove the prefix. Doing it the
            // other way round only recognised the literal "RC-" and rejected a
            // code written with spaces instead of dashes, which is exactly how
            // someone copying from paper would type it.
            var s = input.uppercase().filter { !it.isWhitespace() && it != '-' && it != '_' }

            // Unambiguous: the body is a fixed width, so a two-character excess
            // beginning "RC" can only be the prefix.
            if (s.length == BODY_CHARS + CHECKSUM_CHARS + 2 && s.startsWith("RC")) {
                s = s.substring(2)
            }

            s = s.replace('I', '1')
                .replace('L', '1')
                .replace('O', '0')

            if (s.length != BODY_CHARS + CHECKSUM_CHARS) {
                throw MalformedBlob(
                    "recovery code",
                    "expected ${BODY_CHARS + CHECKSUM_CHARS} characters after formatting is removed, got ${s.length}"
                )
            }
            s.forEach {
                if (it !in ALPHABET) {
                    throw MalformedBlob("recovery code", "character '$it' is not in the recovery alphabet")
                }
            }

            val entropy = decode(s.substring(0, BODY_CHARS))
            val expected = checksum(entropy, primitives)
            val actual = s.substring(BODY_CHARS)
            if (!primitives.constantTimeEquals(
                    expected.toByteArray(Charsets.US_ASCII),
                    actual.toByteArray(Charsets.US_ASCII)
                )
            ) {
                entropy.wipe()
                throw MalformedBlob("recovery code", "checksum does not match — the code was mistyped")
            }
            return RecoveryCode(entropy)
        }

        private fun checksum(entropy: ByteArray, primitives: CryptoPrimitives): String {
            val mac = primitives.hmacSha256(CHECKSUM_KEY, entropy)
            return encode(mac).substring(0, CHECKSUM_CHARS)
        }

        private fun encode(data: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0L
            var bitsHeld = 0
            for (byte in data) {
                buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
                bitsHeld += 8
                while (bitsHeld >= 5) {
                    val index = ((buffer ushr (bitsHeld - 5)) and 0x1F).toInt()
                    out.append(ALPHABET[index])
                    bitsHeld -= 5
                }
            }
            if (bitsHeld > 0) {
                val index = ((buffer shl (5 - bitsHeld)) and 0x1F).toInt()
                out.append(ALPHABET[index])
            }
            return out.toString()
        }

        private fun decode(text: String): ByteArray {
            val out = ByteArray(text.length * 5 / 8)
            var buffer = 0L
            var bitsHeld = 0
            var written = 0
            for (char in text) {
                buffer = (buffer shl 5) or ALPHABET.indexOf(char).toLong()
                bitsHeld += 5
                if (bitsHeld >= 8) {
                    out[written++] = ((buffer ushr (bitsHeld - 8)) and 0xFF).toByte()
                    bitsHeld -= 8
                }
            }
            return out
        }
    }
}
