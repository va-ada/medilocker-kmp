# On-device test log — 20 Aug 2026

Environment: Android emulator (Pixel 6 profile, API 36 arm64, host-GPU), debug build
driven over adb; release build sanity-checked last. All screenshots inspected by eye.

## Verified end-to-end

| Flow | Result |
|---|---|
| First run → create vault (Argon2id 64 MiB on device) | ✅ recovery code issued |
| Recovery code screen — forced acknowledgement | ✅ Continue gated on checkbox |
| **Password lost → recovery code unlock → new password re-wrap** | ✅ all records survived; old password dead, new one works |
| Lock → unlock round trip | ✅ |
| Vault survives reinstall (`adb install -r`) | ✅ asks to unlock, not to create |
| Sample prescription → ML Kit OCR → extraction → review | ✅ brand/generic/strength/grid/frequency all correct |
| Review screen flags low-confidence fields | ✅ "check this one" on incomplete meds |
| Confirm → merge → digest on home | ✅ counts, taking-now list, lab series |
| **Duplicate therapy: Dolo 650 + Calpol (both paracetamol)** | ✅ flagged, not merged, both cited |
| Lab report → values, ranges, out-of-range flags | ✅ HbA1c and glucose flagged red |
| Follow-up parsing → homepage check-up alerts | ✅ "after 2 weeks/1 week" → dated alerts, incl. overdue |
| Cabinet auto-seeded from prescriptions | ✅ counts seeded from course length |
| **Symptom search "fever" → only paracetamol surfaces** | ✅ alias paracetamol↔acetaminophen works; retrieval-not-recommendation notice shown |
| Records list | ✅ |
| Release build: FLAG_SECURE | ✅ screencap is pure black (brightness 0) |

## Bugs found by testing and fixed

1. **No recovery-code UI existed.** Exposed when the create-flow password was corrupted
   by flaky `adb input text`. Built the full recover-and-rewrap path; it then saved the
   vault for real.
2. **Block OCR splits prescription lines.** ML Kit emitted "1. Tab. Dolo 650 …" and
   "TDS X 5 days, after food" as separate, distant lines. Extractor now treats the tail
   as optional and re-pairs orphan schedule lines positionally, capped at MEDIUM
   confidence. Regression test added (`SplitLineOcrTest`).
3. **OCR inserts spaces inside dates** ("30 /07/2026") — date regex tolerates spaces now.
4. **Dual-script OCR read `0` as a Bengali zero** (U+09E6) inside a reference range —
   all Indic digits fold to ASCII before extraction; duplicate analyte lines collapse,
   keeping the most complete range. Regression tests added (`OcrQuirkTest`).
5. **Sample renderer clipped long lines** (fixed width/font); durations and dates were
   silently lost before extraction.

## Environment quirks (not app bugs)

- `adb shell input text` drops/doubles characters on long strings; all test input is
  now per-character. This is also what corrupted the original test password.
- Under swiftshader the emulator ANRs during input storms (main thread Runnable inside
  TextField recomposition — confirmed via bugreport ANR trace). Host-GPU fixed it.
  Argon2id at 64 MiB takes seconds on the emulated CPU; sub-second on real hardware.

## Not yet tested

- Real phone hardware, camera capture path, gallery EXIF rotation, Devanagari OCR on a
  real handwritten prescription, biometric unlock (no hardware), backup-exclusion rules.


---

# On-device test log — 21 Aug 2026 (real hardware)

Device: **Samsung SM-F966B (Galaxy Z Fold), Android 16, arm64-v8a**, foldable with two
displays. Driven over adb; every screen inspected by eye. Release build verified last.

## Verified on the phone

| Flow | Result |
|---|---|
| Create vault — Argon2id 64 MiB ×2 | ✅ **1.49 s wall-clock** (emulator took far longer) |
| Recovery code issued, acknowledgement gated | ✅ |
| Lock → unlock → vault intact | ✅ |
| Vault survives reinstall | ✅ asks to unlock, not to create |
| Wrong password rejected | ✅ clear message, no crash |
| **Camera capture** (untestable on emulator) | ✅ opens, captures, no permission prompt |
| Empty document → honest "nothing recognisable" | ✅ |
| **Gallery pick → real ML Kit OCR** | ✅ |
| Real prescription: date, doctor, reg-no, ICD-10 | ✅ all four correct |
| Real prescription: 3 medicines, hyphenated brands | ✅ Pan-D, Meftal-Spas, Gelusil |
| Merge → digest → follow-up alert from "after 3 weeks" | ✅ dated 2026-09-04 |
| Release build FLAG_SECURE | ✅ screencap is pure black on hardware |
| 16 KB zip alignment | ✅ `zipalign -c -P 16` passes |

## Bugs found on hardware and fixed

1. **App crashed on Camera.** `SecurityException: revoked permission android.permission.CAMERA`.
   The manifest DECLARED `CAMERA`, which makes Android enforce it — but we hand capture to
   the system camera app via `ACTION_IMAGE_CAPTURE` and never open the camera ourselves.
   Fix: remove the permission entirely. The app now requests *fewer* permissions and shows
   no prompt at all.
2. **Dark-mode theme was broken.** The phone is in dark mode; Compose picked dark colours
   but `themes.xml` hard-coded a light window background, so every screen not built on a
   `Scaffold` showed dark components on a light ground. Fix: a `Surface` painting the theme
   background, plus a `values-night/` window background. Invisible on the emulator, which
   was in light mode.
3. **Floating action button was Material default purple.** Undefined `primaryContainer`
   role fell back to the M3 baseline palette. Fix: define the container roles.
4. **Content drew under the status bar.** Android 15+ is edge-to-edge by default; the
   section label sat under the clock. Fix: `safeDrawingPadding()`.
5. **Gallery picking silently did nothing.** Two stacked causes, found by logging the
   contract result:
   - `GetContent`/`PickVisualMedia` returned null because Google Photos serves the picker
     in multi-select and delivers via `clipData`, while both contracts read only
     `intent.data`. Fix: a custom contract reading both.
   - Then `decodeScaled` still failed: `openInputStream(uri)?.use { decodeStream(...) } ?: return null`
     — with `inJustDecodeBounds = true`, `decodeStream` returns null **by design**, so the
     elvis tested the decode result rather than the stream and aborted on every image.
     Fix: guard the stream explicitly. This path had never run before.
6. **Hyphenated Indian brand names were truncated.** `Pan-D` → `Pan`, `Meftal-Spas` →
   `Mefta`: the extractor treated any hyphen as the medicine/schedule separator. Indian
   brands are full of tight hyphens. Fix: an ASCII hyphen only separates when it has
   whitespace on both sides; em/en dashes stay permissive. **Corpus drug-name accuracy rose
   93.03% → 93.92%, strength 90.33% → 91.71%.**
7. **Dual-script OCR read the page twice**, producing 4 medicines for a 3-medicine
   prescription at differing quality ("Mefta-Spas" and "Meftal-Spas"). Fix: collapse entries
   sharing an Rx line number whose names are within a length-scaled edit distance, keeping
   the most complete reading. Entries naming clearly different drugs are both kept — showing
   a duplicate is safer than dropping a medicine.
8. **Not 16 KB page-size compatible.** Android 16 flagged four unaligned native libraries.
   Device here uses 4 KB pages so it ran fine, but 16 KB devices and Play Store submission
   require alignment. Fix: `useLegacyPackaging = false`; APK now passes `zipalign -P 16`.

All eight are covered by tests where testable — `RealPrescriptionOcrTest` pins the verbatim
ML Kit output from this device, mistakes included ("0D", "S0s", "af ter f00d", a danda read
for an "l"), so these regressions cannot return silently.

## Still not tested

Biometric unlock, a genuinely handwritten prescription, Devanagari on real paper, the inner
foldable display (the app ran on the cover screen throughout), backup-exclusion behaviour.


## Biometric unlock — built 21 Aug 2026, NOT yet verified on hardware

Fingerprint / face unlock is implemented and shipping in the APK, but it is the one
feature I could not test myself, and that limit is worth stating plainly: **there is no
way to inject a fingerprint into a physical phone over adb.** The emulator can fake one
(`adb emu finger touch`); a real handset cannot. Verification needs a human finger.

### How it is built

Android Keystore keys are deliberately non-exportable, so the vault cannot be handed
"the device key" as bytes. Hence two layers:

```
Keystore key (hardware-backed, biometric-gated, never leaves the TEE/StrongBox)
     │ AES-GCM
     ▼
device secret (32 random bytes; on disk only as ciphertext)
     │ XChaCha20-Poly1305, via the vault's existing device-wrapper format
     ▼
DEK
```

The crypto core is unchanged — as far as `WrappedKey` is concerned this is an ordinary
device-type wrapper. Only custody of the secret is delegated to hardware. StrongBox is
requested and falls back to the TEE if the device has no dedicated security chip.

### Failure behaviour, by design

- New fingerprint enrolled on the device → `setInvalidatedByBiometricEnrollment(true)`
  destroys the Keystore key, cipher init throws `KeyPermanentlyInvalidatedException`, the
  device wrapper is deleted and the password is required. Enrolling a finger must never
  silently grant access to an existing vault.
- Prompt cancelled → treated as a choice, not an error. No message, password field stays.
- Anything else fails → `disable()` then fall back to the password.

**The vault is never at risk from this path.** Every failure route ends at the password
field, and the password and recovery code remain the sources of truth — the device wrapper
is a convenience copy that can always be destroyed and rebuilt.

### What to check on the phone

1. Create a vault → after the recovery code, the offer screen should appear.
2. Enable → system fingerprint prompt → land on Home.
3. Lock → the unlock screen should show "Use fingerprint or face".
4. Enrol a new fingerprint in Android Settings → the app should quietly fall back to the
   password with an explanation. This is the important one; it is the security property.
