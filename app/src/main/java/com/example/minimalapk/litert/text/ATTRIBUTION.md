# Text decision engine slice — sources and integration contract

## Code and licenses

Adapted from `google-ai-edge/litert-samples` at **f9f0e000630e9cdd6d263c4cd80495e4231a2dc8**, directory
`samples/litert_model_zoo/android/app/src/main/java/com/google/ai/edge/examples/litert_model_zoo/models/typed_decisions/`.
Copyright 2026 The Google AI Edge Authors. Apache License 2.0; the license text is
[`LICENSE-APACHE-2.0.txt`](LICENSE-APACHE-2.0.txt). The upstream Android NOTICE attribution is retained below.

Ported algorithms: per-model prompt construction, tokenization, Unigram Viterbi, SentencePiece
precompiled charsmap, BPE ranked merges/added tokens, binary16 embedding lookup, label/span routing,
float32/float64 decoding, Laya calibration and action features. The adapters and graph wrapper are
changed to use this package, stricter configuration/resource/shape diagnostics, worker confinement,
closed-engine checks and complete per-request input writes. Identical JSON, table lookup and merge
algorithms are consolidated. Laya and Julia share only their **identical SHA-256 tokenizer file**;
their prompt rules, graphs, table widths, limits and decoders remain distinct. The Unigram families
retain different normalizers. Source paths and original model-host pins:

| Source family | Hugging Face repository / revision | Original Kotlin path |
|---|---|---|
| Laya | `litert-community/Laya-Multilingual-LiteRT@32f1b84d55f42a323464fad22594cfc2059e7467` | `android/app/src/main/java/com/laya/{LayaTokenizer,LayaJson,LayaEmbeddings,LayaPromptBuilder,LayaCalibration,LayaDecoder}.kt` |
| Julia | `litert-community/Julia-1-LiteRT@8f36857c56e891c023060586759c6cdc8baf6b3e` | `android/app/src/main/java/com/julia1/{JuliaTokenizer,JuliaJson,JuliaEmbeddings,JuliaRequest,JuliaDecoder}.kt` |
| GLiClass | `litert-community/GLiClass-Edge-v3.0-LiteRT@88c90950587eb951974c094eef91afa0fe3552c0` | `android/sample/app/src/main/java/com/gliclass/{GliclassTokenizer,GliclassInputs,GliclassDecoder}.kt` |
| Open-Decision | `litert-community/Open-Decision-DeBERTa-v3-Large-LiteRT@7a276235b795e8ad3ae7ac6a9f237daa2098863a` | `android/sample/app/src/main/java/com/opendecision/{DecisionTokenizer,DecisionInputs,Question,DecisionDecoder}.kt` |
| GLiNER-Decide | `litert-community/GLiNER2.5-Decide-LiteRT@db80197282d11373df084c0ceed67a54544cfa84` | `android/app/src/main/java/com/gliner25decide/{GlinerTokenizer,DecideInputs,DecideSchema,DecideDecoder}.kt` |

All these host sources are Apache-2.0. No weights, full vocabularies, dataset texts, native tokenizer
libraries or third-party source apps are bundled by this slice. Tests contain short invented
model-card examples, tokenizer probes and numerical fixture excerpts with their sources identified.

### Retained upstream attribution

**Laya multilingual.** The host contract follows Laya 0.3.4 by Convai Innovations, Apache-2.0:
https://huggingface.co/convaiinnovations/laya, checkpoint `1c5edc17a7acd8701df6fc341c0d179f1c62c982`.
The encoder derives from mmBERT-base (MIT): Marc Marone, Orion Weller, William Fleshman, Eugene Yang,
Dawn Lawrie, Benjamin Van Durme; Johns Hopkins Center for Language and Speech Processing and
collaborators. https://huggingface.co/jhu-clsp/mmBERT-base, model-card revision
`c5955035435e2bf121cde7f3c8863ef52ff35d82`. ModernBERT is a collaboration between Answer.AI, LightOn
and contributors, building on MosaicBERT under Apache-2.0: https://github.com/AnswerDotAI/ModernBERT.
Tokenizer behavior follows Hugging Face tokenizers 0.23.2 / Transformers 5.17.0 (Apache-2.0).
Retained model/component licenses: the pinned model repository's `licenses/Laya-APACHE-2.0.txt`,
`mmBERT-MIT.txt`, `ModernBERT-APACHE-2.0.txt`, `Tokenizers-APACHE-2.0.txt`,
`Transformers-APACHE-2.0.txt` and `CALIBRATION_DATASETS.md` (dataset-specific terms; no calibration
dataset text copied here).

**Julia-1.** The request contract follows Supersonic Labs' Julia runtime (Apache-2.0):
https://huggingface.co/SupersonicLabs/Julia-1, checkpoint `a85b127321d580d65176c89ced8273f305745d85`.
The encoder derives from mmBERT-small (MIT), Johns Hopkins Center for Language and Speech Processing:
https://huggingface.co/jhu-clsp/mmBERT-small. Tokenizer behavior follows Hugging Face tokenizers.
Retained component licenses: pinned `LICENSE`, `android/licenses/mmBERT-MIT.txt` and
`android/licenses/Tokenizers-APACHE-2.0.txt`.

**GLiClass-Edge v3.0.** Knowledgator's `knowledgator/gliclass-edge-v3.0`, checkpoint
`df03993a2ed98e5e4a0d2dd7efbbd105abe874cf`, Apache-2.0. Encoder `jhu-clsp/ettin-encoder-32m` is MIT.
Input building and classification postprocessing follow `gliclass` 0.1.20 by Knowledgator,
https://github.com/Knowledgator/GLiClass (Apache-2.0). The tokenizer follows Hugging Face
tokenizers/Transformers (Apache-2.0): raw/normalized added-token extraction, NFC, ByteLevel and BPE.
Retained licenses: pinned `LICENSE`, `licenses/Ettin-Encoder-MIT.txt`, `GLiClass-APACHE-2.0.txt`,
`Model-APACHE-2.0.txt`, `Tokenizers-APACHE-2.0.txt`, `Transformers-APACHE-2.0.txt`. No SemIf text is copied;
one numeric-only oracle excerpt tests decoder arithmetic.

**Open-Decision DeBERTa-v3-large.** Converted from a model published by Kotoba Labs Inc.
(Mithril, formerly Kotoba Cloud), Apache-2.0. Base encoder `microsoft/deberta-v3-large` is MIT,
Microsoft. The Kotlin tokenizer follows Hugging Face tokenizers' Unigram, Metaspace and Precompiled
behavior (Apache-2.0) and the GLiNER-Decide sample's port. Retained licenses: pinned `LICENSE`,
`android/sample/licenses/DeBERTa-MIT.txt`, `Model-APACHE-2.0.txt`, `Transformers-APACHE-2.0.txt`.

**GLiNER2.5-Decide.** Fastino AI's `fastino/GLiNER2.5-Decide`, checkpoint
`7ee5da4c2415e32259bcdc0b1a7367c32ce8d6f6`, Apache-2.0, model-card base `fastino/gliner2-large-v1`.
Encoder `microsoft/deberta-v3-large` is MIT, Microsoft. Input and decoder ports follow Fastino AI's
GLiNER2 `gliner2` 2.0.0, Apache-2.0: `processor.py`, `processing/word_splitter.py`,
`inference/runtime.py`. Tokenizer behavior follows Hugging Face tokenizers/Transformers (Apache-2.0).
Retained licenses: pinned `LICENSE`, `licenses/DeBERTa-MIT.txt`, `GLiNER2-APACHE-2.0.txt`,
`Model-APACHE-2.0.txt`, `Transformers-APACHE-2.0.txt`.

The coordinator must include these attributions/component license links in the app's packaged
notices; this slice owns no common NOTICE, README, catalog or Gradle file.

## API and resource integration

Public integration API, package `com.example.minimalapk.litert.text`:

```kotlin
TextDecisionTasks.ids: Set<String>
TextDecisionTasks.create(taskId: String, directory: File, backend: String): TextDecisionEngine
TextDecisionRequest(text: String, question: String, options: List<Pair<String, String>>)
TextDecisionRequest.parseOptions(rows: List<String>): List<Pair<String, String>>
TextDecisionEngine.decide(request): TextDecisionResult
// Result: ordered probabilities, answerKey, ms, actual backend, window, fallbackReason, details.
TextDecisionEngine.close()
```

Laya additionally implements `LayaDecisionEngine.decide(text: String, question: LayaQuestion)` returning
`LayaDecisionResult` with the official typed answer map. Use `LayaQuestion.choice(...)`, `.score(...)`
or `.yesNo(...)`. The actual graph's `qtype_onehot` selects choice/score/noul; action head always runs
on **raw** probabilities, calibration applies only to option logits. Score is the expected zero-based
level; yes/no is continuous P(true), not a manufactured boolean class.

Construct/run/close on one worker thread. Every request rewrites all graph inputs; these are encoder
graphs without KV/recurrent state. Model compilation is reusable across requests. Keep old engine
assets alive until replacement creation succeeds. Host cancellation must discard obsolete output
generations and drain an in-flight native call on its owning worker. Native calls cannot be interrupted
mid-inference. The engine rejects use after close/cross-thread use and checks interrupted workers
before/after native work. No global mutable request state exists.

`directory` holds **flattened catalog filenames**, not `host_assets/` subdirectories. All required
files are already in the pinned AssetCatalog; **no missing host/tokenizer dependency** was found.
`tokenizer_config.json`, Python libraries and native SentencePiece are not needed by these Kotlin
ports. `org.json` is not used, so JVM tests require no extra JSON dependency. LiteRT 2.3.2 and JUnit
already exist. `DecisionGraph` remains separate from the shared index-based runner for signature-name
binding, exact FLOAT32 shape validation and GPU FP32. Compile fallback reports actual CPU/backend
reason; `npu` is rejected. No readiness is inferred from model presence or a default accelerator.

| Task suffix after `text-decision-` | Installed bytes | Window | Implemented request behavior |
|---|---:|---:|---|
| `laya-multilingual` | 679,274,001 | 256 | Choice/score/yes-no, 2–20 options, label+description, per-option 48-token cap and head squeeze, right-truncated text; `details` explicitly reports losses. Two graphs, type/count calibration. |
| `julia-1` | 416,066,536 | 512 | Choice, 2–20 options, description-only, <=48 tokens/option, strict 507-token head/full-window rejection; float64 T=1 softmax. |
| `gliclass-edge` | 95,971,235 | 128 | Single-label choice, 2–25 distinct labels, description-only, question plus one space before text, no truncation; float32 softmax. English model. |
| `open-decision` | 983,786,402 | 256 | Choice, <=128 slots, description-only, text span mean routing; author's state[:256] then reject if whole request exceeds S256, float64 T=1.05. A 256-token text cannot leave room for a question. |
| `gliner-decide` | 930,884,352 | 128 | One classification task named `answer`, question as prompt, 2–32 distinct labels, per-word tokenization/lowercasing, label-marker routing, no truncation; float32 softmax. Not entity extraction. |

All text/question/options together are bounded at 32,768 UTF-16 code units before tokenization;
unpaired surrogates, empty text/question/keys, duplicate keys and invalid configuration are rejected.
The model-specific encoded token/slot limits then apply. File sizes/JSON contracts are checked before
creation; the installer remains responsible for SHA-256/revision verification and atomic replacement.
Read-only mmap tables are 39–393 MB; only gathered windows are widened to float32 on the heap. The VM
owns unmapping after close; tokenizer indexing and graph compilation still require substantial memory.

## Verification, fixtures and exact limitations

Actual graph contracts checked against all six pinned exported TFLite files (five main graphs plus
Laya action head). Main/test source compiles with `kotlinc` against the real Android LiteRT **2.3.2**
classes. No Gradle task or shared file was touched by this slice.

Final standalone JUnit result: **29 tests, 0 failures, 0 skipped with external fixtures enabled**.
Local handoff evidence: `/tmp/opencode/text-decisions-check/exported/{cases,cpu-results}.json`,
all gathered `.f32` inputs alongside them, and `/tmp/pullfrog-wrkYGe/shell-f89460f7.log` (eight real
CPU cases plus A/B/A checks). `/tmp/opencode/text-decisions-check/text-tests.jar` is the standalone
test/export runner; `text-android-smoke.jar` is the source-compiled Android smoke entrypoint. These
temporary files are verification aids, not APK assets.

`TextDecisionHostTest`, `TokenizerAlgorithmTest`, `DecisionDecoderTest`: pure JVM tests run without
assets, including every finite binary16 pattern, real nonzero PAD rows, shorter-request overwrite,
resource/config errors, Python JSON/order/rounding, ranked merges, NFC vs no NFC vs Precompiled,
Unicode added-token/unknown behavior, routing semantics and actual captured graph decoder values.

`PinnedTokenizerParityTest`: opt-in `TEXT_DECISION_FIXTURES=/path/to/task-root` (or JVM property
`text.decision.fixtures`). It verifies SHA-256 and exact IDs/markers/spans for all five real tokenizers.
Sources: Laya `fixtures/gate_rows_s256.json` ML_A01; GLiClass `fixtures/oracle_public.json` inv_02;
Open-Decision `android/sample/app/src/test/resources/tokenizer_probes.json`; GLiNER-Decide
`android/app/src/test/resources/tokenizer_probes.json` and `android/app/src/debug/assets/gate_fixtures.json`
readme_18. Julia's small literal ID fixture was independently produced with Hugging Face tokenizers
0.23.2 and the pinned strict prompt contract, not copied from this implementation's output.

`TextDecisionFixtureExporter` + `verify_text_decisions.py` + `RealGraphDecoderParityTest` form an
optional full cross-language real-model gate: actual Kotlin prompts/tokenizers/routing/mmap embeddings
are exported; Hugging Face tokenizers independently reproduces all eight literal requests and **all
input floats bit-exactly**; Python CPU CompiledModel **2.3.0** runs the actual pinned models; Kotlin
decoders are checked against the real outputs. A/B/A buffer rewrites test graph state reuse. JSON/full
outputs stay outside the checkout. `TEXT_DECISION_EXPORTED` (or `text.decision.exported`) enables the
JVM output parity test. These are CPU host checks, not Android GPU evidence.

Observed 2026-11-11 literal outputs:

| Literal fixture | Actual CPU output | Expected source / interpretation |
|---|---|---|
| Laya card Japanese duplicate charge, choice | refund .99543744, help .00456257 | Card calibrated .9954/.0046; exact 37-token request. |
| Laya ML_A01 urgency, score | probabilities [.18813054,.34236169,.46950778], expected level 1.2814 | Pinned gate raw logits + shipped calibration; gate's official T=1 answer 1.7169 is a different calibration. |
| Laya ML_A01 churn, yes/no | P(true)=.18048632, rounded .1805 | Pinned gate + shipped calibration; source T=1 P(true)=.0041. |
| Julia card team | billing .79253321, shipping .20730664, access .00016015 | Card .7925/.2073/.0002. |
| GLiClass router + actual question | connection .88470685 | Actual adapter adds `What is the problem? `; not the promptless oracle. |
| GLiClass promptless inv_02 | connection .85619229 | Official CPU FP32 .85605210; pinned FP16 table tolerance .003. |
| Open-Decision delivery, one question | shipping .75419154 | Card's literal text/options, but one S256 question here; card's .785 is a three-question S512 request, not the same input. |
| GLiNER-Decide readme_18 treaty | **yes .99626416**, no .00373582 | Published oracle also says yes (.99626333). **Known semantic error: passage implies no.** Preserve conversion parity, do not claim QA accuracy. |

`TextDecisionModelSmoke` / `TextDecisionSmokeTest` prepare a real **95,971,235-byte** fixed GLiClass
Android test, with SHA checks and oracle P=.8560521 (tolerance .003), full engine A/B/invalid/A,
close/recreate and graph stale-input rejection. No download occurs in the test. An isolated Android
2.3.2 x86_64 `app_process` attempt reached XNNPACK (670/670 nodes) but the software emulator timed out
at 120 seconds before an inference result; **not a passing Android smoke**. Coordinator owns further
Gradle, emulator and UI validation. Default instrumentation skips unless `textDecisionModelDir` is
provided; `textDecisionBackend` is cpu by default and a GPU test rejects CPU fallback.

Unverified: Android arm64 CPU/GPU numerical/driver compatibility; Android Kotlin binding end-to-end
results; GPU FP32 execution on a physical device; release/R8; foreground/background/disable memory
cleanup; sustained memory/latency; complete source benchmark suites; language accuracy beyond the
literal fixtures. API24 app/base compatibility is retained by avoiding API26-only tokenizer methods,
but upstream Android validation boundaries (API26/31 and arm64) are not expanded by desktop checks.

This slice completes only the five Zoo engines and Laya's real extra channels. Full #74 legacy BERT/
average-word classification, GLiNER entities, semantic search and all local-LM recipes remain separate.
