# MediLocker — Build Plan

**Version** 1 · 18 August 2026
**Supersedes** the Flutter prototype in `MediLocker-main/`
**Reads with** [SECURITY.md](../SECURITY.md) (the crypto contract, already implemented and tested)

| | |
|---|---|
| Course | Digital Product Development (Prof. Pooja Gurjar) · Natural Language Processing (Dr. Vaqar Ansari) |
| Programme | T.E. Artificial Intelligence & Machine Learning, SFIT |
| Team | Aditya Soni (CEO/CIO) · Prashant Jha (CTO/DBA) · Aveel Lewis (CMO) · Mariam Badure (CFO) |
| North Star | **Weekly Understood Records** — documents per week that become structured, verified records in a user's own timeline |


---

## Scope: ship a usable app first

Revised 18 Aug 2026, after the goal changed to *"an app I use in everyday life."*

The 21-week research pipeline and a daily-usable app are not the same deliverable, and building them in that order means nothing is usable for months. So the order is inverted: ship the smallest app that is genuinely worth opening every day, then layer the research on top of a working product.

| | Scope | Why here |
|---|---|---|
| **v1 — daily driver** | Unlock · capture · OCR · rule-based extraction · Review & Confirm · **Health Digest with merge** · search · timeline · Emergency Card | Everything a person actually opens the app for. **No LLM.** Fully offline, sub-second OCR, no 3 GB download |
| **v2 — the assistant** | On-device LLM: digest narration, MediAssist chat with citations, semantic search | The differentiator, and the research contribution. Layered onto a product that already works |
| **v3 — iOS** | KMP port; the domain layer is already platform-independent | Once v1 has proven itself in daily use |

**What this changes technically.** v1 uses **ML Kit Text Recognition** on Android rather than ONNX — it is on-device, sub-second, needs no multi-hundred-MB model, and handles Devanagari. That trades the cross-platform error-distribution consistency argued for in Phase 2.4; the ONNX engine returns in v2 when iOS arrives and when evaluation numbers must be comparable across platforms. For a v1 that has to feel instant on one platform, ML Kit is the right call.

**What this does not change.** The security layer is unchanged and non-negotiable. The digest merge stays deterministic. Nothing enters the digest unconfirmed.

**The point of v1.** Most of the daily value — *find that report, what am I currently on, is this lab value drifting, what did the doctor say in March* — comes from extraction and the digest, and needs no language model at all. The LLM makes it conversational. It does not make it useful.

---

## Problem Statement

Indian patients hold their medical history as photographs. A scanned prescription in a cloud drive is no more useful than the paper it came from: it cannot be searched, questioned, or checked for problems. MediLocker converts each uploaded document into structured medical data on the patient's own phone, accumulates it into one continuously-updated health summary, and answers questions about it with citations — with no network involved in any of the reading.

**Who the user is.** A patient aged 25–55 managing a chronic condition or frequent specialist visits, on a mid-range Android phone, in India, often on poor connectivity. Secondary: a caregiver managing a parent's records; a first responder reading an offline card.

**What "done" looks like.** The user photographs a prescription. Within 30 seconds, offline, the app has filed it, read it, extracted the medicines and dosages, shown them for confirmation, written a per-document text artifact, and folded the new facts into a running Health Digest that cites every claim back to the document it came from.

---

## The flow this plan is built around

```
  ┌────────────┐
  │  CAPTURE   │  camera / file / share-sheet
  └─────┬──────┘
        ▼
  ┌────────────┐
  │  ENCRYPT   │  AES before anything else touches disk   [core-crypto, DONE]
  └─────┬──────┘
        ▼
  ┌────────────┐
  │  CLASSIFY  │  prescription | lab | discharge | scan | bill | vaccination
  └─────┬──────┘
        ▼
  ┌────────────┐
  │    OCR     │  text + per-word confidence + layout boxes
  └─────┬──────┘
        ▼
  ┌────────────┐
  │  CORRECT   │  repair OCR against a fixed medicine/test vocabulary
  └─────┬──────┘
        ▼
  ┌────────────┐
  │  EXTRACT   │  drugs · doses · frequencies · durations · diagnoses ·
  └─────┬──────┘  lab names · values · units · ranges · dates · doctor
        ▼
  ┌────────────┐
  │ NORMALISE  │  brand→generic, RxNorm / LOINC / ICD-10, relative→absolute dates
  └─────┬──────┘
        ▼
  ┌────────────┐
  │   REVIEW   │  user confirms; low-confidence fields flagged for correction
  └─────┬──────┘
        ▼
  ┌────────────┐
  │  ARTIFACTS │  raw.txt · structured.json · card.md          ← "generate txt files"
  └─────┬──────┘
        ▼
  ┌────────────┐
  │   MERGE    │  fold into the running Health Digest          ← "add into existing summary"
  └─────┬──────┘
        ▼
  ┌────────────┐
  │  NARRATE   │  on-device LLM writes prose for changed sections only
  └────────────┘
```

Every stage runs on the device. Stages 3–8 involve no network call of any kind.

---

## The central design decision: the merge is deterministic, the LLM only narrates

This is the part worth arguing about, so it is stated plainly.

**The obvious approach is wrong.** Concatenating every document summary and asking the model to re-summarise fails four ways: a 4B model at 4k context cannot hold 200 documents; the summary changes wording every run, so the user cannot tell a real change from a rephrasing; it costs 30+ seconds per upload; and provenance dissolves into the prose.

**What we do instead.** The Health Digest is a *typed data structure*, not a block of text. Merging a new document is a set of deterministic operations on typed collections. No language model participates in the merge.

```
HealthDigest
├── medications[]   (generic, brand, dose, frequency, route, started, ended, status, sources[])
├── conditions[]    (icd10, label, firstSeen, lastSeen, status, sources[])
├── labs{}          LOINC → observation[] (date, value, unit, refLow, refHigh, flag, source)
├── allergies[]     (substance, reaction, severity, sources[])
├── procedures[]    (label, date, sources[])
├── careTeam[]      (name, specialty, regNo, seenOn[], sources[])
└── narrative{}     section → { prose, generatedAt, fromFactHash }
```

Every leaf carries `sources[]` — a document id, page, and bounding box. **Citation is structural, not generated**, which is how the PRD's "100% of AI statements carry a verifiable source" becomes an architectural property rather than a hope.

### Merge rules

| Situation | Rule |
|---|---|
| Same generic, non-overlapping dates | Append as a separate course |
| Same generic, overlapping dates, same dose | Extend the existing course; add the source |
| Same generic, overlapping dates, **different** dose | Newer supersedes; older marked `superseded`, never deleted |
| **Different brands, same generic, overlapping** | **Flag `DUPLICATE_THERAPY`.** Do not merge. This is the Dolo 650 / Calpol case — the exact error the product exists to catch |
| Lab value, same LOINC | Insert into the time series by date; recompute trend |
| Lab value, missing reference range | Store the value, mark range `unknown`, do not infer one |
| Condition already present | Update `lastSeen`, append the source |
| Any field the user corrected at Review | User value wins permanently and is never overwritten by a later extraction |

Nothing is ever deleted by a merge. Superseded facts stay, with a reason, so the timeline remains auditable.

### Narration

After the merge, exactly one thing is asked of the language model: write prose for the sections whose `factHash` changed. A prescription upload re-narrates `medications` and leaves `labs` untouched.

Generation is grammar-constrained (GBNF) to a template that can only emit sentences referencing fact ids already present in the digest. **The model cannot introduce a medicine, a value, or a claim that is not in the structure** — a materially stronger guarantee than instructing it not to.

Consequences worth stating: incremental cost is one short generation instead of a full re-summary; the prose is stable between uploads; and when the model is unavailable (low-memory tier), the digest still works — the user sees the structured facts without the prose, which degrades gracefully instead of failing.

---

## Where each discipline does real work

Not decoration. Each is load-bearing, and each has an owner and an evaluation number.

| Discipline | Where it runs | Evaluated by |
|---|---|---|
| **Text analysis** (classical NLP) | Devanagari/code-mixed preprocessing (already written); dosage-grid parsing (`1-0-1`, `BD`, `TDS`, `HS`, `SOS`); unit and range parsing; date normalisation; document section segmentation | Exact-match accuracy on the synthetic corpus, which carries ground truth |
| **ML** (classical) | Document classification baseline (TF-IDF + linear SVM); extraction confidence calibration; brand→generic candidate **reranking** (char n-grams + edit distance + length priors → gradient-boosted reranker) | Macro-F1; top-1 and top-5 resolution accuracy |
| **DL** | OCR (transformer OCR / CRNN); medical NER (PubMedBERT or DistilBERT, quantised to ONNX); OCR-correction seq2seq | Entity F1; CER/WER before and after correction |
| **Language modelling** | On-device GGUF (4B class, ~3 GB) for narration, MediAssist answers, and grammar-constrained OCR repair | Citation validity rate (target 100%); abstention correctness |
| **AI** (systems) | Retrieval over the medical library and the user's own records; the merge conflict engine; the answer-or-abstain decision; consent and access logic | Retrieval hit-rate@k; conflict-flag precision |

The synthetic prescription corpus (24,961 records, already generated) supplies pre-annotated ground truth for NER and relation extraction. Its documented defects — contraindications read as indications, approximate ICD-10 — mean it trains **extraction only**, never clinical knowledge. That boundary is enforced by keeping it out of the retrieval library entirely.

---

## Screens (17)

| # | Screen | Notes |
|---|---|---|
| 1 | Unlock | Biometric, password fallback, auto-lock timeout |
| 2 | First-run / Recovery code | Shown once, forced acknowledgement, never re-displayable |
| 3 | Home / Vault | Search, recent, categories, digest entry point |
| 4 | Capture | Camera with edge detection, file picker, share-sheet target |
| 5 | Processing | Live stage feedback — the 30s wait must be legible, not a spinner |
| 6 | **Review & Confirm** | Extracted fields with confidence; low-confidence highlighted for correction. **Nothing enters the digest unconfirmed** |
| 7 | Record Detail | Preview, extracted entities, per-document card, source highlighting |
| 8 | **Health Digest** | The living summary. Sections, prose, every claim tappable to its source |
| 9 | Timeline | Chronological, assembled from dates inside documents |
| 10 | MediAssist chat | Cited answers; permitted to say "I don't know" |
| 11 | Search | Semantic + blind-index category filter |
| 12 | Categories | Folder view |
| 13 | Emergency Card | Offline QR, readable while locked |
| 14 | Share / Consent | QR or OTP, scoped, time-bound, with identifier stripping |
| 15 | Access log | Who saw what, when; revoke |
| 16 | Profile | Personal and medical basics |
| 17 | Security settings | Change password, rotate recovery code, device bindings, panic-lock |

Screen 6 is the one most likely to be cut under time pressure and must not be. It is where the app is honest about uncertainty, where the user's correction becomes permanent ground truth, and where the "AI-generated — review before saving" obligation is discharged.

---

## Assumptions

1. Android is the primary target; iOS follows via Kotlin Multiplatform sharing the domain layer.
2. No real patient data is used at any point. Development runs on the synthetic corpus and public datasets.
3. The on-device model is downloaded once on first run, verified by hash, and stored encrypted. It is not shipped in the app bundle.
4. The vault is local-first. There is no server holding plaintext, and in v1 no PHI-bearing network call exists.
5. The team has four members and roughly 20 working weeks.
6. `core-crypto` is the only crypto in the product; no feature adds its own.

## Dependencies

| Need | Status |
|---|---|
| JDK 17, Android SDK, Xcode | Present |
| Gradle 8.13 | Present (scratchpad; needs project wrapper) |
| libsodium via lazysodium | Working, tested |
| llama.cpp + Vulkan/Metal | **Not yet integrated — the biggest unknown** |
| ONNX Runtime Mobile | Not yet integrated |
| SQLCipher + sqlite-vec | **Compatibility unverified — de-risk in week 1** |
| Tesseract/PaddleOCR Devanagari data | Not yet fetched |
| Apple increased-memory-limit entitlement | **Not yet applied for — apply now, approval is not instant** |
| RxNorm (via UMLS), LOINC, SNOMED CT India | Licences not yet obtained — apply now |

---

# Execution

Each phase ends with a checkpoint. A checkpoint has a synthetic test (Pass 1) and a real-data test (Pass 2). No phase is done without both.

## Phase 0 — De-risk the unknowns
**Goal:** find out in week one whether the two load-bearing integrations work at all.

[PARALLEL]
- [ ] 0.1 — Build a throwaway Android app that loads a 4B Q4_K_M GGUF via llama.cpp NDK + Vulkan and generates one token. Record tokens/sec and peak RSS.
- [ ] 0.2 — Same on iOS via Kotlin/Native `cinterop` against `llama.h`. Record the same numbers.
- [ ] 0.3 — Open a SQLCipher database and load the `sqlite-vec` extension into it. Insert and query one vector.
- [ ] 0.4 — Apply for the Apple `increased-memory-limit` entitlement; apply for UMLS, LOINC and SNOMED CT India licences.
- [ ] 0.5 — Generate the project Gradle wrapper so the build stops depending on a scratchpad download.
[/PARALLEL]

**Checkpoint**
- Pass 1: a unit test asserts the GGUF loader returns a non-empty token for a fixed prompt and seed.
- Pass 2: run on a real 8 GB Android device and a real iPhone; record tokens/sec and peak memory in `docs/DEVICE_MATRIX.md`.
- **If 0.3 fails**, switch the store to ObjectBox (KMP, encrypted, built-in vector search) before any schema is written. Deciding this later is expensive.

## Phase 1 — Encrypted store and document model
**Goal:** documents can be added, encrypted, listed and deleted, with nothing readable at rest.

[SERIES]
- [ ] 1.1 — SQLCipher opened with `vault.databasePassphrase()`. Verify the file has no plaintext by grepping the raw bytes for a known string.
- [ ] 1.2 — Schema: `documents`, `artifacts`, `facts`, `digest_sections`, `audit_log`. Every column classified encrypted / blind-indexed / public before it is written. No unclassified default.
- [ ] 1.3 — `DocumentRepository`: add, list, open, delete. File bytes go through `FileCipher`; metadata through `FieldCipher`; category through `BlindIndex`.
- [ ] 1.4 — Deletion removes the DB row **and** the encrypted blob. (The prototype orphaned every file forever — regression test this.)
- [ ] 1.5 — Android StrongBox device-key binding and BiometricPrompt unlock. `FLAG_SECURE` on every screen showing PHI.
[/SERIES]

**Checkpoint**
- Pass 1: repository tests — round trip, deletion removes both artefacts, wrong-vault access throws.
- Pass 2: add 50 real PDFs and images on a device; pull the DB file over ADB and confirm no readable strings; confirm app-private storage is excluded from backup.

## Phase 2 — Capture, classify, OCR
**Goal:** a photograph becomes text with confidence scores, on-device.

[SERIES]
- [ ] 2.1 — Capture screen: camera with document edge detection, file picker, share-sheet intent.
- [ ] 2.2 — Image preprocessing: deskew, perspective correction, adaptive threshold, denoise.
- [ ] 2.3 — Document classifier. Baseline TF-IDF + linear SVM on the synthetic corpus plus scraped public samples; upgrade to DistilBERT only if the baseline misses the 90% target.
- [ ] 2.4 — OCR via ONNX Runtime — **the same engine on both platforms**, not ML Kit on Android and Vision on iOS. Two engines mean two error distributions for stage 3 to learn and non-comparable evaluation numbers.
- [ ] 2.5 — Devanagari and code-mixed handling: wire in the existing `preprocessing_regional.py` logic, ported to Kotlin, with its test vectors carried across.
- [ ] 2.6 — Persist `raw.txt` (text + per-word confidence + boxes) as an encrypted artifact.
[/SERIES]

**Checkpoint**
- Pass 1: classifier macro-F1 ≥ 0.90 on a held-out synthetic split; ported Devanagari tokeniser passes the same assertions as the Python original.
- Pass 2: 30 real photographs — phone camera, varied lighting, at least 10 handwritten and 5 Devanagari. Record CER and per-document wall-clock on the target device. Inspect outputs by eye; do not only assert.

## Phase 3 — Understanding: correct, extract, normalise
**Goal:** text becomes typed facts with confidence and provenance.

[SERIES]
- [ ] 3.1 — Build the closed vocabulary: ~5,000 medicine names, ~2,000 test names, from the drug reference data already downloaded.
- [ ] 3.2 — OCR correction with **grammar-constrained decoding** against that vocabulary. The model is structurally unable to emit a medicine that does not exist.
- [ ] 3.3 — Medical NER: fine-tune PubMedBERT/DistilBERT on the synthetic corpus (which ships pre-annotated spans), quantise to ONNX, deploy.
- [ ] 3.4 — Rule-based extraction for the parts rules handle better than models: dosage grids, frequencies, durations, units, ranges.
- [ ] 3.5 — Relation extraction — bind each dose/frequency/duration to its drug. Pre-annotated in the corpus.
- [ ] 3.6 — Date normalisation: "review after 2 weeks" → an absolute date, anchored to the document date.
- [ ] 3.7 — **Indian brand→generic resolution** (the team's stated technical contribution): mapping assembled from CDSCO, the National Formulary of India and the Jan Aushadhi index, plus a reranker tolerant of misspelling, abbreviation and OCR damage.
- [ ] 3.8 — Standardisation to RxNorm / LOINC / ICD-10, gated on the licences from 0.4.
- [ ] 3.9 — Per-field confidence, calibrated so "low confidence" means something at the Review screen.
[/SERIES]

**Checkpoint**
- Pass 1: entity F1 ≥ 0.85, dosage-to-drug matching ≥ 0.80, brand resolution ≥ 0.80 top-1 and ≥ 0.95 top-5, all on held-out synthetic data.
- Pass 2: run the 30 real documents from Phase 2 end to end. Hand-score every extraction against the paper. Log every miss — this list drives Phase 3 iteration and is the honest number for the report.

## Phase 4 — Artifacts and the Digest merge engine
**Goal:** the flow's core. Each document yields text artifacts, and facts fold into a running summary.

[SERIES]
- [ ] 4.1 — Artifact writer: `raw.txt`, `structured.json`, `card.md` per document, each encrypted, each linked to its document id.
- [ ] 4.2 — `HealthDigest` data model with the typed collections above; every leaf carries `sources[]`.
- [ ] 4.3 — **Merge engine** implementing the rule table. Pure functions, no IO, no model — so it is exhaustively unit-testable.
- [ ] 4.4 — Conflict detection: duplicate therapy, dose disagreement, superseded courses. Flags surface in the UI; the app never resolves a clinical conflict on its own.
- [ ] 4.5 — `factHash` per section, so narration knows precisely what changed.
- [ ] 4.6 — User corrections from Review recorded as pinned facts that no later merge overwrites.
- [ ] 4.7 — Health Digest screen: sections, flags, every claim tappable through to the source document with the region highlighted.
[/SERIES]

**Checkpoint**
- Pass 1: merge-engine unit tests covering every row of the rule table, plus idempotence (merging the same document twice changes nothing), commutativity where order should not matter, and the Dolo/Calpol duplicate-therapy flag.
- Pass 2: ingest 10 real documents for one synthetic patient in sequence. After each, inspect the digest by hand and confirm it matches what a person would conclude from the same papers. Confirm every claim traces to a real source.

## Phase 5 — On-device language model
**Goal:** the narration and chat run locally, within the device budget.

[SERIES]
- [ ] 5.1 — Model download on first run, hash-verified, stored encrypted, excluded from backup.
- [ ] 5.2 — Device tiering: 8 GB+ → 4B Q4_K_M · 6 GB → 1.5–2B Q4 · 4 GB → extraction-only, no chat.
- [ ] 5.3 — llama.cpp bound through `CryptoPrimitives`-style `expect`/`actual`: JNI + Vulkan on Android, `cinterop` + Metal on iOS.
- [ ] 5.4 — Inference inside a foreground service (Android) / `BGProcessingTask` (iOS), with mmap and a quantised KV cache.
- [ ] 5.5 — GBNF narration grammar that can only reference fact ids present in the digest.
- [ ] 5.6 — Unload NER and embedding models before the LLM loads. Never hold two.
[/SERIES]

**Checkpoint**
- Pass 1: narration of a fixed digest at a fixed seed produces prose whose every claim maps to a fact id; a mutation test injecting a fake medicine into the prompt confirms the grammar rejects it.
- Pass 2: on a real 8 GB device — narrate after each of 10 uploads, measure wall-clock and peak RSS, and confirm no OOM kill after 30 minutes of backgrounding.

## Phase 6 — MediAssist with citations
**Goal:** questions answered from the user's own records, with sources, offline.

[SERIES]
- [ ] 6.1 — Embeddings (BGE-small or MiniLM via ONNX) over artifacts and digest facts; vectors in sqlite-vec.
- [ ] 6.2 — Retrieval: semantic + structured filters (date range, document type, LOINC code).
- [ ] 6.3 — Answer generation constrained to retrieved passages, each answer carrying its citations.
- [ ] 6.4 — Abstention: "I don't know" is a correct answer and is scored as one.
- [ ] 6.5 — The two enforced notices, attached by the app rather than the model.
- [ ] 6.6 — Chat screen with tappable citations opening the source document at the cited region.
[/SERIES]

**Checkpoint**
- Pass 1: a 50-question set over a known digest — 100% of answers carry a valid citation; questions with no supporting record are abstained on.
- Pass 2: each team member asks 20 unscripted questions of a real vault. Log every uncited or wrong answer. An uncited claim is a defect, not a quality issue.

## Phase 7 — Timeline, search, Emergency Card
[SERIES]
- [ ] 7.1 — Timeline from document-internal dates.
- [ ] 7.2 — Semantic search plus blind-index category filter.
- [ ] 7.3 — Lab trend charts per LOINC with reference bands.
- [ ] 7.4 — Emergency Card: offline QR from structured facts, readable while locked, no vault unlock required.
[/SERIES]

**Checkpoint** — Pass 1: QR encodes and decodes the expected fields; timeline ordering tests. Pass 2: scan the card from a second phone with the source phone locked and in airplane mode.

## Phase 8 — Consent, sharing, audit, anonymisation
[SERIES]
- [ ] 8.1 — Scoped, time-bound share tokens; revocation.
- [ ] 8.2 — Identifier stripping before external sharing.
- [ ] 8.3 — Append-only audit log.
- [ ] 8.4 — Export and delete-everything, as DPDP requires.
[/SERIES]

**Checkpoint** — Pass 1: revoked tokens fail; stripping removes every seeded identifier. Pass 2: run a real share to a second device, revoke mid-session, confirm access dies and the log records it.

## Phase 9 — Evaluation and launch
[SERIES]
- [ ] 9.1 — Full evaluation against the PRD targets; publish the real numbers including misses.
- [ ] 9.2 — Device matrix across the three tiers.
- [ ] 9.3 — Play Store staged rollout; monitor the model download.
- [ ] 9.4 — SFIT demonstration; Mumbai clinic pilot onboarding.
[/SERIES]

---

## Final verification

- [ ] Every Pass 1 suite green
- [ ] Every Pass 2 run recorded with actual observed output, misses included
- [ ] No PHI in any log, crash report, or analytics — verified by inspection, not assumed
- [ ] No PHI-bearing network call — verified by packet capture during a full session
- [ ] 100% of AI statements carry a resolvable citation
- [ ] Full pipeline under 30s per document on the mid tier
- [ ] Recovery code path verified end to end on a wiped device

## Targets

| Metric | Target |
|---|---|
| Document classification | 90% |
| Entity extraction F1 | 85% |
| Dosage-to-medicine matching | 80% |
| Medicine name resolution | 80% top-1 · 95% top-5 |
| Identifier removal before sharing | 95% |
| **AI statements with a verifiable source** | **100%** |
| On-device processing per document | < 30s |
| Model size | < 3 GB |

## Risks

| Risk | Handling |
|---|---|
| **llama.cpp on iOS is harder than expected** | Phase 0.2 finds out in week one, not month three |
| **SQLCipher + sqlite-vec incompatible** | Phase 0.3; fall back to ObjectBox before any schema exists |
| Model too large for mid-range phones | Three-tier model plan; the 4 GB tier still delivers structured records without chat |
| Terminology licences delayed | Phases 1–4 do not depend on them; only 3.8 gates |
| Merge engine gets clinical logic wrong | It flags and cites; it never resolves. Conflicts go to the user |
| Synthetic corpus defects leak into clinical claims | Corpus trains extraction only; structurally excluded from the retrieval library |
| Scope too large for 20 weeks | Phases 1–4 are the product. 5–6 are the differentiator. 7–9 can be reduced without breaking it |

## Open questions

1. **Does any real data exist in the old Firebase project?** If yes, it needs migrating off the zero-IV scheme before that project is touched again. If it is a few test accounts, delete them and skip it.
2. **Which mentor deliverable takes priority** — a working demo (favours Phases 1–2 and UI) or Section 12 evaluation numbers (favours Phases 3–4)? With a rewrite this size, both cannot be polished.
3. **Is the clinic pilot still in scope this semester?** It implies a provider-side surface that this plan does not currently cover.
