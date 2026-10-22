package com.medilocker.app

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.medilocker.crypto.CryptoPrimitives
import com.medilocker.crypto.LibsodiumPrimitives
import com.medilocker.crypto.RecoveryCode
import com.medilocker.crypto.Vault
import com.medilocker.crypto.WrappedKey
import java.io.File

/**
 * Bridges the platform-independent vault to Android.
 *
 * The only thing persisted here is the wrapped-key blob, which is already
 * ciphertext — the DEK it protects exists in memory and nowhere else. Storing
 * the wrapper in ordinary app-private files is therefore correct: there is no
 * secret in it to protect with a second layer.
 */
class VaultManager(context: Context) {

    val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val passwordWrapper = File(dir, "password.wrapper")
    private val recoveryWrapper = File(dir, "recovery.wrapper")

    /**
     * Android's libsodium is a different artifact from the desktop one — JNI
     * rather than JNA — but it implements the same interfaces, so the crypto
     * core is unchanged between the phone and the test suite.
     */
    val primitives: CryptoPrimitives by lazy {
        val sodium = LazySodiumAndroid(SodiumAndroid())
        LibsodiumPrimitives(sodium, sodium, sodium)
    }

    @Volatile
    private var open: Vault? = null

    val isUnlocked: Boolean get() = open != null

    fun hasVault(): Boolean = passwordWrapper.exists()

    /** @return the recovery code, formatted for display. Shown once, never stored. */
    fun create(password: CharArray): String {
        val created = Vault.create(password, primitives)
        passwordWrapper.writeBytes(created.passwordWrapper.blob)
        recoveryWrapper.writeBytes(created.recoveryWrapper.blob)
        open = created.vault
        return created.recoveryCode.formatted(primitives).also { created.recoveryCode.destroy() }
    }

    /** @throws com.medilocker.crypto.VaultUnlockFailed if the password is wrong. */
    fun unlock(password: CharArray) {
        val wrapper = WrappedKey.parse(passwordWrapper.readBytes())
        open = Vault.unlockWithPassword(password, wrapper, primitives)
    }

    /**
     * The forgotten-password path. Opens the vault with the printed recovery
     * code and immediately re-wraps the DEK under a new password — recovery is
     * only complete once the user can unlock normally again. No records are
     * touched; only the wrapper changes.
     */
    fun recoverWithCode(code: String, newPassword: CharArray) {
        val parsed = RecoveryCode.parse(code, primitives)
        val wrapper = WrappedKey.parse(recoveryWrapper.readBytes())
        val vault = Vault.unlockWithRecoveryCode(parsed, wrapper, primitives)
        parsed.destroy()
        passwordWrapper.writeBytes(vault.rewrapWithNewPassword(newPassword).blob)
        open = vault
    }

    fun vault(): Vault = open ?: error("vault is locked")

    /** Adopt a vault opened by another path (biometric unlock). */
    fun adopt(vault: Vault) { open = vault }

    /** Drops every key. Called on timeout, on background, and on explicit lock. */
    fun lock() {
        open?.close()
        open = null
    }
}
