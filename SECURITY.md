# MediLocker — Security Architecture

**Status:** specification · v1 · written before implementation
**Scope:** the cryptographic core. Everything else in the app is built on top of this and may not bypass it.

---

## 0. Design rule

> **The server, the disk, and the backup are all assumed hostile. Only the user's unlocked device is trusted, and only while it is unlocked.**

Every decision below follows from that one sentence. Where a choice trades convenience for that property, the property wins.

A second rule, learned from the defects in the previous version of this app:

> **Prefer primitives that cannot be misused over primitives that must be used carefully.**

The previous implementation used AES with `IV.fromLength(16)` — an all-zero IV, identical for every record ever written. That is not an unusual mistake; it is the *expected* outcome of handing a developer a raw block cipher and a nonce parameter. This design removes the opportunity rather than documenting the hazard.

---

## 1. Threat model

### 1.1 Defended against

| # | Adversary | Capability | Defence |
|---|---|---|---|
| T1 | Cloud/server operator | Full read of all stored data | Everything is ciphertext before it leaves the device. Server holds no key material. |
| T2 | Database-level attacker | Read **and write** the record store | AEAD authentication + AAD binding (§5). Tampering is detected, not silently decrypted. |
| T3 | Backup / sync exfiltration | Reads device backups, iCloud, ADB backup | Vault DB and files are ciphertext at rest; model + caches excluded from backup. |
| T4 | Device thief, locked device | Physical possession, no PIN/biometric | DEK exists only in memory while unlocked. At rest, only wrapped copies. |
| T5 | Offline password guessing | Steals wrapped DEK blob, brute-forces offline | Argon2id, memory-hard, tuned params (§3.3). |
| T6 | Ciphertext relocation | Moves a valid ciphertext to another field/record | AAD binds every ciphertext to its record id + field name (§5). |
| T7 | File truncation / reordering | Cuts or shuffles chunks of an encrypted document | Per-chunk index in AAD + explicit final-chunk marker (§6). |
| T8 | Nonce reuse | Exploits repeated nonce to recover plaintext | XChaCha20-Poly1305 with 192-bit random nonces (§3.1). |

### 1.2 Explicitly NOT defended against

Stating these plainly is part of the design. A security document that claims total coverage is not trustworthy.

| # | Not defended | Why |
|---|---|---|
| N1 | Compromised OS, root/jailbreak, or malicious debugger on an **unlocked** device | The DEK is in memory by necessity. No app-level defence is meaningful here. |
| N2 | The user's own password being weak or reused | Argon2id raises the cost; it cannot fix `123456`. Enforced minimums at §3.3. |
| N3 | Coercion of the user | Out of scope. No duress mode in v1. |
| N4 | **Equality and frequency leakage from blind indexes** | An attacker with DB read access learns *which records share a category*, and can frequency-match against a known category distribution. This is the accepted, documented cost of server-side search. See §7.3. |
| N5 | Metadata: record count, record sizes, write timestamps | Visible to anyone with DB access. Padding and timing obfuscation are not implemented in v1. |
| N6 | Screenshots on iOS | iOS has no `FLAG_SECURE` equivalent. Screen **recording** is detectable (`UIScreen.isCaptured`) and the view is blanked; still screenshots cannot be blocked. Android uses `FLAG_SECURE`. This asymmetry is real and is not papered over. |
| N7 | Memory scraping of a JVM process | The JVM garbage collector may copy `ByteArray` contents during compaction, so zeroization (§8) is best-effort on Android. It is reliable on Kotlin/Native (iOS). |

---

## 2. Key hierarchy

```
  password ──────Argon2id(salt_pw)─────> KEK_pw ──wraps──┐
                                                          │
  recovery code ─Argon2id(salt_rc)─────> KEK_rc ──wraps──┤
                                                          ├──> DEK (32 B, random, per vault)
  device key ────StrongBox/SecureEnclave> KEK_dev ─wraps──┘         │
                                                                     │
        ┌────────────────────────────────────────────────────────────┘
        │  HKDF-SHA256, distinct info string per purpose
        ├──> K_field   "medilocker:v1:field"   field AEAD
        ├──> K_file    "medilocker:v1:file"    document AEAD
        ├──> K_index   "medilocker:v1:index"   blind index HMAC
        └──> K_db      "medilocker:v1:db"      SQLCipher passphrase
```

### 2.1 Why an envelope, not a single key

The previous version derived nothing and stored one random key in secure storage, written **once, at signup only**. The consequences were: no second device, no reinstall, no recovery — and silent permanent data loss when the key was absent, because `encryptData` returned `null` and records saved with null contents.

The envelope fixes all of it:

| Operation | Cost |
|---|---|
| Unlock on a new device | Argon2id once. DEK unwraps. All existing data readable. |
| Change password | Re-wrap the DEK. **Zero records re-encrypted.** |
| Forgotten password | Recovery code unwraps an independent copy of the same DEK. |
| Biometric fast-unlock | Device-key wrapper, hardware-bound, never leaves the enclave. |

Three wrappers hold three independently-encrypted copies of the *same* DEK. Compromising one wrapper yields the DEK; that is inherent to the design and is why each wrapper is individually strong.

### 2.2 Invariants

1. The DEK is **never** written to disk, logs, backups, or the network in unwrapped form.
2. Subkeys are **always** derived via HKDF with a distinct `info` string. No key is ever used for two purposes.
3. A missing or unavailable key is a **hard error**. It never degrades to plaintext, never returns null, never falls through to a default.
4. Failed decryption raises. It never returns the input, and never returns a placeholder.

Invariants 3 and 4 are direct responses to defects in the previous implementation, where `decryptData` returned the raw ciphertext on failure — showing users base64 as their record title — and `encryptData` returned `null` when the key was missing, silently writing null-content records.

---

## 3. Primitives

### 3.1 AEAD — XChaCha20-Poly1305

| Property | Value |
|---|---|
| Key | 32 bytes |
| Nonce | **24 bytes, random per message** |
| Tag | 16 bytes |

Chosen over AES-GCM specifically for the 192-bit nonce. With a 96-bit GCM nonce, random generation becomes risky past ~2³² messages and correctness depends on a counter the developer must maintain. At 192 bits, random nonces have negligible collision probability at any volume this app will ever reach, so **there is no nonce-management burden to get wrong**. Given that the previous version shipped a fixed all-zero IV, removing this footgun entirely is the single highest-value primitive choice in this document.

Poly1305 authentication means T2 (database write access) is detected rather than silently decrypted into attacker-chosen plaintext.

### 3.2 Subkey derivation — HKDF-SHA256

Every purpose gets its own `info` string (§2). Domain separation means a weakness or oracle in one subsystem cannot be pivoted into another — the blind-index key cannot decrypt fields, the DB passphrase cannot decrypt documents.

### 3.3 Password KDF — Argon2id

| Parameter | Value | Reasoning |
|---|---|---|
| memory | 64 MiB | Transient, at unlock only. Acceptable even on 4 GB devices. |
| iterations | 3 | |
| parallelism | 1 | Deterministic across platforms. |
| salt | 16 bytes, random per wrapper | |
| output | 32 bytes | |

**Parameters are stored inside the wrapper blob**, so they can be raised in future versions without invalidating existing vaults.

Memory-hard by design: GPU and ASIC attacks against T5 are far costlier than against PBKDF2 or bcrypt.

Minimum password length is enforced at **12 characters** — not the 6 the previous version allowed, which is trivially brute-forced offline once the wrapper blob is stolen.

### 3.4 Randomness

Platform CSPRNG only. Android `SecureRandom` (no seeding by the app), iOS `SecRandomCopyBytes`. No custom PRNG, no `Math.random`, no time-based seeding, anywhere, ever.

### 3.5 Recovery code

`RC-` + 32 base32 characters (Crockford alphabet, ambiguous characters excluded) + a 4-character checksum, grouped for transcription.

160 bits of entropy — strong enough that the Argon2id wrapper around it is defence in depth rather than the primary barrier. Generated once, displayed once, never stored by the app in any form.

---

## 4. Wrapped-DEK blob format

```
offset  size  field
------  ----  --------------------------------------------------
     0     4  magic "MLKW"
     4     1  version = 1
     5     1  wrapper type (1=password, 2=recovery, 3=device)
     6     1  kdf id (1 = Argon2id)
     7     4  argon2 memory KiB      (big endian)
    11     4  argon2 iterations      (big endian)
    15     1  argon2 parallelism
    16    16  salt
    32    24  nonce
    56    32  wrapped DEK ciphertext
    88    16  Poly1305 tag
------  ----
   104 bytes total
```

The header bytes `[0..32)` are passed as **AAD**, so the stored KDF parameters are authenticated. An attacker cannot downgrade a vault to `memory=8 KiB` to make offline guessing cheap — altering the parameters invalidates the tag.

**Parameters are also range-checked before the KDF runs**, with both a floor and a ceiling:

| | memory | iterations |
|---|---|---|
| floor | 32 MiB | 2 |
| production default | 64 MiB | 3 |
| ceiling | 512 MiB | 10 |

The floor stops a downgrade. The **ceiling stops a denial of service**, and it is not hypothetical: these values are read out of a blob an attacker may control, and flipping one bit in the high byte of the memory field turns 32 MiB into 16 GiB. libsodium will attempt it, and the unlock hangs forever — locking the user out of their own medical records without breaking any cryptography. Authentication alone does not help here, because the parameters must be read *before* the tag over them can be verified. The bound must be checked first, and it is.

*(Found by the wrapper-blob tamper test during implementation, not by review.)*

---

## 5. Encrypted field format

```
offset  size  field
------  ----  --------------------------------------------------
     0     4  magic "MLKF"
     4     1  version = 1
     5    24  nonce
    29     N  ciphertext
  29+N    16  tag
```

**AAD = `medilocker:v1:field:{recordId}:{fieldName}`**

This is what defeats T6. A ciphertext encrypted as record `abc`'s `title` will not authenticate as record `abc`'s `category`, nor as record `xyz`'s `title`. An attacker with database write access cannot shuffle encrypted values between fields or between patients' records — a meaningful attack in a medical context, where swapping a diagnosis between two records causes real harm while every individual value remains "validly encrypted".

---

## 6. Encrypted file format (streaming)

Medical documents are large; a 50 MB PDF must never be fully resident. Files are chunked.

```
header (25 bytes)
   0   4  magic "MLKD"
   4   1  version = 1
   5   4  chunk plaintext size (big endian, default 65536)
   9  16  nonce prefix (random per file)

then, repeating:
       N  chunk ciphertext
      16  tag
```

**Per-chunk nonce** = `noncePrefix(16) || chunkIndex(8, big endian)` = 24 bytes. Unique per chunk, unique per file.

**Per-chunk AAD** = `medilocker:v1:file:{fileId}:{chunkIndex}:{isFinal}`

Three separate attacks are blocked:

- **Reordering** — chunk index is in both the nonce and the AAD.
- **Truncation** — the final chunk is marked `isFinal=1`. A truncated file terminates on a chunk that does not claim to be final, and is rejected. Without this marker, cutting a lab report in half yields a file that decrypts perfectly and reads as complete.
- **Cross-file splicing** — `fileId` is in the AAD.

---

## 7. Blind index

### 7.1 The problem it replaces

The previous version needed `where('category', isEqualTo: encryptedCategory)` to work, so encryption had to be deterministic — which is *why* the IV was fixed at zero. The zero IV was not an oversight; it was load-bearing. Any fix that only randomizes the IV breaks folder browsing.

### 7.2 The construction

```
blindIndex(value) = HMAC-SHA256(K_index, normalize(value))[0..16]
```

Stored in a **separate column** alongside the randomized AEAD ciphertext of the real value. Equality queries hit the index column; the ciphertext column stays randomized.

`normalize` = NFKC → trim → Unicode-aware lowercase → collapse internal whitespace. It must remain **byte-stable forever**; changing it silently orphans every existing index. Any change requires a migration and a version bump.

Truncated to 128 bits: collision-safe for this domain, and halves storage.

### 7.3 What it leaks — accepted

Per N4: an attacker with database read access sees which records share a value and how often each value occurs. Against a small, guessable domain like category names (`Prescriptions`, `Lab Reports`, …), frequency analysis can likely recover the mapping.

This is accepted for v1 because the alternative — downloading and decrypting every record client-side to filter — does not scale, and because category names are low-sensitivity relative to document contents, which are never indexed. **Blind indexes are permitted on category only. They are forbidden on titles, notes, diagnoses, and document text.**

---

## 8. Key material handling

1. Keys live in `ByteArray`, never `String`. Strings are immutable and interned; they cannot be wiped.
2. All key material is used inside a scope that zeroizes on exit, including on exception.
3. Zeroization is **best-effort on Android** (JVM GC may have copied the array during compaction) and reliable on Kotlin/Native. Stated honestly per N7.
4. No key material is ever logged, at any level, in any build. The previous version's `print("Decryption failed: $e")` leaked crypto state to logcat.
5. The DEK is dropped from memory on app background beyond a timeout, and on explicit lock.

---

## 9. Platform key custody

| | Android | iOS |
|---|---|---|
| Device KEK | Keystore, **StrongBox** when available | **Secure Enclave** |
| Access control | `setUserAuthenticationRequired(true)`, BiometricPrompt | `.biometryCurrentSet`, LocalAuthentication |
| Biometric enrolment change | Invalidates the device wrapper — password required | `.biometryCurrentSet` invalidates on change |
| Backup exclusion | `allowBackup=false`, no `auto_backup_rules` | `isExcludedFromBackup = true` |

Invalidation on biometric change is deliberate: if an attacker enrols their own fingerprint, the device wrapper dies and the password is required.

The device wrapper is a **convenience path only**. The password and recovery wrappers are the sources of truth; a device wrapper can always be destroyed and rebuilt.

---

## 10. Non-negotiables for all code built on this layer

1. No plaintext PHI is written to any persistent store, ever — that includes the SQLite WAL, temp files, caches, and share sheets.
2. No PHI in logs, crash reports, or analytics. There is no analytics SDK in this app.
3. No network call carries plaintext PHI. In v1 there is no PHI-bearing network call at all.
4. Failed authentication or decryption is surfaced to the user as an error. It never degrades, never falls back, never guesses.
5. Every new persisted field is classified — encrypted, blind-indexed, or public — before the schema is written. There is no unclassified default.

---

## 11. Known gaps, v1

Listed so they are tracked rather than forgotten:

- No duress/panic mode (N3).
- No padding of ciphertext lengths; record sizes leak (N5).
- Blind index frequency leakage on category (N4, §7.3).
- Zeroization is best-effort on Android (N7).
- iOS cannot block screenshots (N6).
- No remote wipe. The vault is local-first, so there is no server-side revocation to trigger.

---

*This specification is the contract. Where implementation and specification disagree, that is a defect in one of them — not a decision to be made at the call site.*
