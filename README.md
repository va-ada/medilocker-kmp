# MediLocker — rewrite

Ground-up rebuild. The security layer is built first; no feature code exists yet, by design.

- **[SECURITY.md](SECURITY.md)** — the specification. Threat model, key hierarchy, wire formats, and the explicit list of what is *not* defended against. Read this before touching `core-crypto`.
- **`core-crypto/`** — the implementation. 64 tests, all passing.


## Tech stack

Chosen against the constraints that actually bind: Android first with iOS following, a ~3 GB
on-device model in v2, and a security core that must not be re-implemented per platform.

| Layer | Choice | Why this one |
|---|---|---|
| Language | **Kotlin** | The domain and crypto layers are already written in it, and it is the only language that reaches Android natively *and* compiles to an iOS framework later. |
| UI | **Jetpack Compose** + Material 3 | Declarative, and Compose Multiplatform carries the same screens to iOS without a rewrite. |
| Crypto | **libsodium** — XChaCha20-Poly1305 + Argon2id | Bound through interfaces, so the desktop build (JNA) and the Android build (JNI) share one implementation. Same library binds on iOS through cinterop, giving byte-identical vault formats across platforms. |
| Storage | SQLCipher + encrypted blobs | Database keyed from a subkey of the vault; documents stored as chunked ciphertext. |
| OCR (v1) | **ML Kit Text Recognition** | On-device, sub-second, handles Devanagari, no large model download. |
| OCR (v2) | ONNX Runtime | Returns when iOS arrives and evaluation numbers must be comparable across platforms. |
| LLM (v2) | llama.cpp — Vulkan on Android, Metal on iOS | Same GGUF files on both. GBNF grammars constrain output to real medicine names. |
| Widget | Glance | Compose for home-screen widgets. |
| Build | Gradle 8.13 · AGP 8.12.3 · Kotlin 2.1.0 · compileSdk 36 · minSdk 26 | |

**What is deliberately *not* in the stack:** no dependency-injection framework, no networking
library, no analytics, no crash reporter. v1 makes no network call at all, so most of a normal
app's dependency list has nothing to do.

**On Kotlin Multiplatform:** `core-crypto` and `core-domain` are plain Kotlin/JVM modules today,
written without platform types except `java.time` and `java.io`. Converting them to KMP source
sets is a mechanical refactor and is deferred until iOS work actually starts — restructuring the
build before there is a working app buys nothing and risks a lot.

## Building

```
./gradlew test              # 86 tests, JVM, ~10s
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Status

| Layer | State |
|---|---|
| Security specification | Written |
| Crypto core (vault, field, file, blind index) | Implemented, tested on JVM |
| Android app — full v1 loop (capture → OCR → review → digest → cabinet → reminders) | **Built and tested on emulator** — see [docs/TESTING.md](docs/TESTING.md) |
| Biometric unlock (Keystore/StrongBox, biometric-gated) | **Built** — needs a real finger to verify |
| iOS platform bindings (Secure Enclave, cinterop) | Not started |
| Database, UI, OCR, NER, on-device LLM | Not started |

## Running the tests

Requires JDK 17 and Gradle 8.13.

```
gradle :core-crypto:test
```

Runs in about 7 seconds. Argon2id is exercised at its accepted floor rather than the
production default so the suite stays fast — it is the real KDF, not a stub.

## What `core-crypto` gives you

```kotlin
val p = LibsodiumPrimitives()

// New vault. Persist both wrappers; show the recovery code exactly once.
val created = Vault.create("a-long-enough-passphrase".toCharArray(), p)
store(created.passwordWrapper.blob, created.recoveryWrapper.blob)
displayOnce(created.recoveryCode.formatted(p))

// Later, on any device.
val vault = Vault.unlockWithPassword(password, WrappedKey.parse(blob), p)

// Fields are bound to their record and column; a ciphertext cannot be relocated.
val sealed = vault.fieldCipher().encrypt("Chest X-Ray", recordId = "rec-1", fieldName = "title")

// Documents stream in chunks; truncation and reordering are rejected.
vault.fileCipher().encrypt(source, sink, fileId = "doc-1")

// Equality search without deterministic ciphertext.
val idx = vault.blindIndex().computeHex("Lab Reports", BlindIndex.IndexedField.CATEGORY)

vault.close()   // zeroizes every key
```

## Design rules this layer enforces

These are structural, not conventions to remember:

- There is **no API that returns a raw key**, and no cipher can be built without an unlocked vault.
- There is **no field-encryption call that skips the record/field binding** — an unbound overload would get used whenever binding was inconvenient.
- Blind indexing is restricted to an **enum of approved fields**, not a free-form string, so it cannot spread to titles or notes without a deliberate change.
- **Every failure throws.** Nothing returns null, a placeholder, or the input. The previous version's decrypt returned its input on failure, showing users base64 as their record title.

## Porting to Android and iOS

`CryptoPrimitives` is the only platform seam. Everything above it — formats, key hierarchy, binding, chunking — is platform-independent and already written.

- **Android** — `LibsodiumPrimitives` runs as-is.
- **iOS** — bind the same libsodium via `cinterop`; HKDF and HMAC come from CryptoKit. Wire formats are byte-identical because the AEAD and password KDF are the same library on both sides.

`FileCipher` is the one place with a JVM-shaped API (`InputStream`/`OutputStream`). The KMP port swaps those for okio `Source`/`Sink` without touching the chunking logic.
