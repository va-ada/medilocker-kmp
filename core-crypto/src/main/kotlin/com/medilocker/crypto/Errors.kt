package com.medilocker.crypto

/**
 * All crypto failures are exceptions. Nothing in this layer returns null, a
 * placeholder, or the input on failure.
 *
 * SECURITY.md 2.2 invariants 3 and 4. The previous version of this app returned
 * the ciphertext from its decrypt function when decryption failed, which showed
 * users base64 as their record title, and returned null when the key was
 * missing, which silently wrote null-content records. Both are impossible here
 * because there is no non-throwing failure path.
 */
sealed class CryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Authentication tag did not verify: wrong key, tampering, or wrong AAD. */
class AuthenticationFailure(context: String) : CryptoException(
    "Authentication failed while decrypting $context. The data was modified, " +
        "the key is wrong, or it was bound to a different field or record."
)

/** A stored blob did not match the expected format. */
class MalformedBlob(what: String, detail: String) : CryptoException(
    "Malformed $what: $detail"
)

/** Wrapped-DEK could not be opened — almost always a wrong password. */
class VaultUnlockFailed(via: String) : CryptoException(
    "Could not unlock the vault using the $via. The credential is incorrect."
)

/** Caller supplied a parameter this layer refuses to accept. */
class WeakParameter(detail: String) : CryptoException(detail)

/** Key material was used after being zeroized. */
class KeyDestroyed : CryptoException(
    "This key has been zeroized and can no longer be used."
)

/** An encrypted file was truncated, reordered, or spliced. */
class FileIntegrityFailure(detail: String) : CryptoException(
    "Encrypted file failed integrity checks: $detail"
)
