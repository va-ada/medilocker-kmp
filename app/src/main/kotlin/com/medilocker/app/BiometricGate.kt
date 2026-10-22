package com.medilocker.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.medilocker.crypto.CryptoPrimitives
import com.medilocker.crypto.Vault
import com.medilocker.crypto.WrappedKey
import com.medilocker.crypto.wipe
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint / face unlock.
 *
 * ## Why this is double-wrapped
 *
 * Android Keystore keys are deliberately **non-exportable** — that is the whole
 * point of hardware-backed storage, and it means the vault cannot simply be
 * handed "the device key" as raw bytes.
 *
 * So there are two layers:
 *
 * ```
 *   Keystore key (hardware, biometric-gated, never leaves the TEE/StrongBox)
 *        │ AES-GCM
 *        ▼
 *   device secret (32 random bytes, on disk only as ciphertext)
 *        │ XChaCha20-Poly1305, via the vault's own wrapper format
 *        ▼
 *   DEK  ── the key everything is actually encrypted with
 * ```
 *
 * The crypto core is untouched: the device secret is an ordinary device-type
 * wrapper as far as [WrappedKey] is concerned. Only the custody of that secret
 * is delegated to hardware.
 *
 * ## What invalidation means
 *
 * The Keystore key is minted with `setInvalidatedByBiometricEnrollment(true)`.
 * If someone adds a fingerprint, Android permanently destroys the key, cipher
 * init throws, and this class deletes the device wrapper and falls back to the
 * password. That is intentional per SECURITY.md 9 — enrolling a new finger must
 * not silently grant access to an existing vault.
 *
 * Biometrics are a **convenience path only**. The password and recovery code
 * remain the sources of truth and can always rebuild this.
 */
class BiometricGate(private val vaultDir: File, private val primitives: CryptoPrimitives) {

    private val wrapperFile = File(vaultDir, "device.wrapper")
    private val secretFile = File(vaultDir, "device.secret")

    val isEnabled: Boolean get() = wrapperFile.exists() && secretFile.exists()

    fun disable() {
        wrapperFile.delete()
        secretFile.delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    // ---- enrolment ----------------------------------------------------------

    /**
     * Bind an already-unlocked vault to this device's biometrics.
     * Requires a biometric confirmation, which doubles as the user proving the
     * finger they are enrolling with is theirs.
     */
    fun enable(
        activity: FragmentActivity,
        vault: Vault,
        onDone: (Boolean, String?) -> Unit
    ) {
        val cipher = runCatching {
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, freshKey()) }
        }.getOrElse {
            onDone(false, "This device would not create a hardware-backed key."); return
        }

        prompt(
            activity,
            title = "Confirm to enable",
            subtitle = "Use your fingerprint or face to unlock MediLocker in future.",
            cipher = cipher,
            onError = { onDone(false, it) },
            onSuccess = { authorised ->
                runCatching {
                    val deviceSecret = primitives.randomBytes(CryptoPrimitives.KEY_BYTES)
                    try {
                        // Wrapper first: if this fails nothing is persisted.
                        val wrapper = vault.bindToDevice(deviceSecret)
                        val sealed = authorised.doFinal(deviceSecret)
                        secretFile.writeBytes(authorised.iv + sealed)
                        wrapperFile.writeBytes(wrapper.blob)
                    } finally {
                        deviceSecret.wipe()
                    }
                }.fold(
                    onSuccess = { onDone(true, null) },
                    onFailure = { disable(); onDone(false, "Could not enable biometric unlock.") }
                )
            }
        )
    }

    // ---- unlocking ----------------------------------------------------------

    /**
     * @param onFallback the device wrapper is gone or no longer valid — the
     *   caller must ask for the password. Never an error the user must action.
     */
    fun unlock(
        activity: FragmentActivity,
        onUnlocked: (Vault) -> Unit,
        onFallback: (String?) -> Unit
    ) {
        if (!isEnabled) { onFallback(null); return }

        val blob = runCatching { secretFile.readBytes() }.getOrNull()
        if (blob == null || blob.size <= GCM_IV_BYTES) { disable(); onFallback(null); return }

        val cipher = try {
            Cipher.getInstance(TRANSFORM).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    existingKey() ?: run { disable(); onFallback(null); return },
                    GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES)
                )
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Fingerprints changed on the device. By design the key is dead.
            disable()
            onFallback("Biometrics changed on this device, so fingerprint unlock was turned off. Enter your password.")
            return
        } catch (_: Exception) {
            disable(); onFallback(null); return
        }

        prompt(
            activity,
            title = "Unlock MediLocker",
            subtitle = "Use your fingerprint or face.",
            cipher = cipher,
            onError = { onFallback(it) },
            onSuccess = { authorised ->
                runCatching {
                    val deviceSecret = authorised.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
                    try {
                        Vault.unlockWithDeviceKey(
                            deviceSecret, WrappedKey.parse(wrapperFile.readBytes()), primitives
                        )
                    } finally {
                        deviceSecret.wipe()
                    }
                }.fold(
                    onSuccess = onUnlocked,
                    onFailure = { disable(); onFallback(null) }
                )
            }
        )
    }

    // ---- plumbing -----------------------------------------------------------

    private fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onError: (String?) -> Unit
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authorised = result.cryptoObject?.cipher
                if (authorised == null) onError(null) else onSuccess(authorised)
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                // Cancelling is a choice, not a failure — say nothing and let
                // the password field take over.
                val quiet = code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    code == BiometricPrompt.ERROR_CANCELED
                onError(if (quiet) null else message.toString())
            }
        }

        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButtonText("Use password")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setConfirmationRequired(false)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? =
        runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull()

    /** Always mints a new key, so re-enabling never reuses stale material. */
    private fun freshKey(): SecretKey {
        runCatching { keyStore().deleteEntry(ALIAS) }
        return generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            generator.init(spec); generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            // Not every device has a dedicated security chip. Fall back to the
            // TEE rather than refusing to offer biometrics at all.
            if (strongBox) generate(strongBox = false) else throw IllegalStateException("no keystore")
        }
    }

    companion object {
        private const val ALIAS = "medilocker.device.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        /** Whether this device can offer strong biometrics at all. */
        fun available(context: android.content.Context): Boolean =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }
}
