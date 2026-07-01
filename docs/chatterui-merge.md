# drmd × ChatterUI — full merge design (archive → local RAG → offline chat)

Status: **design / phase-2 findings**. This document is executable in a real RN
dev environment (this sandbox can't clone ChatterUI — its git proxy only serves
`hren4073-cpu/drmd` — nor build a forked Expo/NDK app).

## Goal

One app: scrape a blog/forum with our multithreaded engine → feed ChatterUI's
on-device vector RAG → chat with a local GGUF LLM about it, fully offline.

- **drmd/lj2pdf** = acquisition/ETL (scanner + HTTP/jsoup downloader + cleaner + chunker).
- **ChatterUI** = consumption (llama.cpp local LLM + sqlite-vec RAG + embeddings).

User decisions: **full merge**; **embeddings computed by ChatterUI** (our side
returns clean chunked text only).

## ChatterUI facts (from the v0.8.8 APK + the `master` source)

- Expo SDK 53, React-Native (new arch), Hermes, TypeScript. Package
  `com.Vali98.ChatterUI`. **License: AGPL-3.0** → the merged fork must be
  published as source; keep standalone lj2pdf (PDF/EPUB) as a separate repo.
- Local LLM via `cui-llama.rn` (llama.cpp GGUF; `librnllama*.so`, bundled
  `llama3tokenizer.gguf`). Remote backends: OpenAI/Ollama/KoboldCpp/Claude.
- **RAG uses raw sqlite-vec**: the APK bundles `libsqlitevec.so` and its strings
  show a `vec0` virtual table (`… sample_embedding float`, `WHERE sample_embedding
  MATCH …`, `topK`). Crucially, `db/schema.ts` (Drizzle) has **no** vector table —
  only characters, chats, `chatAttachments` (type ∈ audio|image|document),
  lorebooks, models, instructions. So the vector store is created with **raw SQL**
  (`CREATE VIRTUAL TABLE … USING vec0`) inside `lib/` code, not the typed schema.
- Source layout (`master`): `app/` (expo-router screens), `db/` (`db.ts`,
  `schema.ts`, `migrations/`), `lib/{engine,state,storage,hooks,markdown,…}`.
  `lib/engine/` is inference only (`API/`, `Local/`, `Inference.ts`,
  `LocalInference.ts`, `Tokenizer.ts`). Uses expo-sqlite + Drizzle + MMKV.

## The one thing to pin from a clone (blocked here)

Locate ChatterUI's RAG ingestion path in a checkout and grep:
```
grep -rniE "vec0|sample_embedding|CREATE VIRTUAL TABLE|MATCH|embedding|topK|sqlite-?vec" lib db app
```
Identify the function that: (a) creates/opens the `vec0` + documents tables,
(b) chunks a document, (c) computes its embedding (which GGUF embedding model
ChatterUI loads via `cui-llama.rn`), (d) inserts rows. That function (or a direct
`vec0` insert if none is exported) is our **ingestion target**. Note it may be
behind a flag / experimental in 0.8.8.

## Integration architecture

**Native module — an Expo Module in Kotlin** (`modules/archiver/`, using
`expo-modules-core`, already bundled). API:
- `scan(base, opts) : Promise<[{id,title,url}]>`
- `archive(base, opts) : Promise<[{id,title,url,text}]>` — scan→download→clean
  (→optional chunk); emits `onProgress {done,total,bytes,bps,threads,pingMs,etaSec}`.
- `cancel()` — sets the cancel flag.
- Events via the module's event emitter.

**Reused engine** (copy from `android/lj2pdf/app/src/main/java/com/drmd/lj2pdf/`,
adapt): `Http.kt`, `SiteScan.kt`, `HtmlArchiver.kt` (images OFF on the RAG path —
text only), `RagExporter.chunk()`, optional `Translator.kt`. Replace `ConvertBus`
with a small `Progress` interface (`log/progress/scanProgress/bytes/active/cancel`)
that the module bridges to `sendEvent(...)`. **Do not port**: `ConvertService`,
`MainActivity`, `Projects`, `PdfRenderer2`, `EpubBuilder`, `BookBuilder`,
`WebViewPdfRenderer`, layouts. Kotlin coroutines drive parallelism (as today).

**TypeScript screen** (`app/ArchiveScreen.tsx` + expo-router route + menu entry):
URL + settings (threads, translator, target lang) + Start/Cancel; subscribes to
`onProgress` → the live counters we already designed (traffic/speed/ping/threads/
ETA); calls `Archiver.archive(url, opts)`. On completion, for each returned doc,
call the pinned RAG ingestion (→ ChatterUI chunks/embeds/stores in `vec0`). Then
the user selects that knowledge base in a chat with a local model.

## Build / CI

Fork ChatterUI @ the v0.8.8 tag into a repo we control (AGPL). New CI job:
`npm ci && npx expo prebuild -p android && ./gradlew assembleDebug` — heavier than
the current native jobs (Node + Expo + Android NDK for llama.cpp); cache
node_modules + Gradle + prebuilt `.so`. **Phase 1 = prove the vanilla fork builds
green** before adding the module.

## Phases

1. Fork ChatterUI @ v0.8.8; vanilla build green in CI; add AGPL NOTICE for our additions.
2. Pin the sqlite-vec ingestion function (grep above); write the contract here.
3. Add the Kotlin Expo Module wrapping the engine (Progress/cancel shim); callable from JS.
4. `ArchiveScreen.tsx` + route + counters; wire completion → ingestion (phase 2).
5. End-to-end: archive `protomarius.livejournal.com` → knowledge base → offline
   chat grounded on the archive; verify cancel + counters.

## Environment note

This sandbox can't execute phases 1–5 (no external clone via the git proxy; no
RN/Expo/NDK toolchain). Run them on a dev machine (or a CI with open network) that
can `git clone https://github.com/Vali-98/ChatterUI`, `npm ci`, `expo prebuild`,
and `gradlew assembleDebug`.

## Sources
- ChatterUI (AGPL-3.0, Expo/RN): https://github.com/Vali-98/ChatterUI
- sqlite-vec local vector search: https://github.com/asg017/sqlite-vec
