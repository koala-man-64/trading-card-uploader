# Implementation Plan: Scanner + Android App Enhancement Program

Status: DRAFT — awaiting approval (no development starts until approved)
Design: [scanner-app-enhancements.md](../design/scanner-app-enhancements.md)
(approved 2026-07-05, Themes A–F)

Repos:
- **Scanner**: `C:\Users\rdpro\Projects\trading-card-scanner` (Python Azure
  Functions; ruff/mypy/pytest gates)
- **Uploader**: `C:\Users\rdpro\Projects\trading-card-uploader` (Android
  Kotlin app + `api-sas-issuer` Python BFF; detekt/ktlint/gradle-test gates,
  lines ≤120)

---

## Stage 0 — Shared contract (blocks both teams, done first)

### WI-CONTRACT-001: `scanner-metadata-v1` contract doc
One agent writes the contract, mirrored in both repos per each repo's
shared-contract routing rules:
- Scanner: `docs/contracts/scanner-metadata-v1.md` (owning copy — scanner
  owns the blob schemas and its own endpoints)
- Uploader: `docs/contracts/scanner-metadata-v1.md` (mirror + BFF endpoint
  additions, which the uploader owns)

Contents:

**Metadata blob** — `metadata/<sha256(sourceBlobName)>.json` in the
processed container (same hashing scheme as lineage):

```json
{
  "schemaVersion": 1,
  "sourceBlobName": "raw/cap-123.jpg",
  "processedAtUtc": "2026-07-05T12:00:00.000Z",
  "modelId": "detr-...",
  "durationMs": 1234,
  "cardCount": 3,
  "ocrRan": true,
  "cards": [
    {
      "index": 1,
      "cropBlobName": "cap-123_1.jpg",
      "segmentedBlobName": null,
      "thumbBlobName": "thumbs/cap-123_1.webp",
      "confidence": 0.97,
      "bboxNorm": [0.1, 0.2, 0.4, 0.8],
      "name": "Pikachu",
      "price": "$12.50",
      "ocrErrors": []
    }
  ],
  "errors": []
}
```

`name`/`price`/`ocrErrors` are null/empty when `ocrRan` is false.
`segmentedBlobName`/`thumbBlobName` are null until Phase 3 ships; readers
must tolerate null and unknown extra fields (forward compat).

**Status blob** — `status/<sha256(sourceBlobName)>.json`:

```json
{
  "schemaVersion": 1,
  "sourceBlobName": "raw/cap-123.jpg",
  "state": "processing | done | failed",
  "error": null,
  "updatedAtUtc": "2026-07-05T12:00:01.000Z"
}
```

**Scanner endpoint additions** (all admin-auth, same `_require_gallery_admin`
path as existing admin routes):
- `GET /api/v1/admin/gallery/metadata?sourceBlobName=` → metadata doc or
  404 `metadata_missing`.
- `GET /api/v1/admin/gallery/source-status?sourceBlobName=` → status doc or
  404 `status_missing`.
- `AdminGalleryImages` items gain `cardCount: int|null`,
  `hasMetadata: bool`, `state: string|null`, `thumbUrl: string|null`.
- `POST /api/v1/admin/gallery/actions/reprocess-source` accepts optional
  `conf: float`, `modelVariant: string`, `perspectiveCorrect: bool`.

**BFF endpoint additions** (uploader-owned, Entra-validated like existing):
- `GET v1/admin/gallery/metadata?sourceBlobName=` (proxy)
- `GET v1/admin/scanner/source-status?sourceBlobName=` (proxy)
- `POST v1/uploads/precheck` — body: image bytes; proxies scanner
  `POST /api/layout?extract_crops=false`; returns
  `{cardCount, imageWidth, imageHeight, boxes: [{bboxNorm, confidence}]}`.
- Reprocess proxy passes the three optional tuning fields through.

**Fixtures**: contract doc links canonical example JSON committed to both
repos (`tests/fixtures/scanner_metadata_v1/*.json` scanner-side;
`android-app/app/src/test/resources/scanner_metadata_v1/*.json` app-side) so
the app team codes against fixtures before the scanner ships.

Acceptance: both repo copies identical for shared shapes; fixtures parse
against the documented schema; reviewed before Stage 1 starts.

---

## Scanner team work items (repo: trading-card-scanner)

### Phase 1

**SCN-META-001 — Pipeline metadata capture** (Theme A)
- New `card_processor/pipeline_metadata.py`: dataclasses for the metadata
  and status documents, blob-name helpers (`metadata_blob_name`,
  `status_blob_name` reusing the sha256 scheme from
  `function_app._lineage_blob_name`), serializers, and a
  `build_metadata_document(...)` assembler.
- `card_processor/process_utils.py`: add
  `extract_cards_with_details_from_image_bytes(image_bytes, *, max_crops,
  settings)` returning crops **plus** the post-processed `DetectedCard`
  elements and `model_info` (today `extract_card_crops_from_image_bytes`
  discards everything but bytes). Existing function becomes a thin wrapper
  to avoid breaking callers.
- `function_app._process_blob_bytes`: use the new extractor; when
  `settings.ocr_enabled`, run name/price extraction per crop through the
  existing `text_extraction` client (respecting `ocr_max_workers`); write
  the metadata blob after crop upload; enrich the lineage manifest with
  `cardCount`. OCR failure of an individual crop records `ocrErrors` for
  that card but does not fail the pipeline.
- Telemetry: `log_event` fields `card_count`, `ocr_ran`, `duration_ms`.
- Tests: new `tests/test_pipeline_metadata.py` (document shape vs fixture,
  hashing, OCR-disabled and OCR-partial-failure paths) + extend
  `tests/test_upload_processed_cards.py` for the new extractor.

**SCN-STATUS-001 — Status markers** (Theme B, depends on SCN-META-001's
helpers)
- `function_app.process_blob`: write `state=processing` on entry;
  `state=done` after metadata write; `state=failed` with the exception
  string in the failure path (wrap body in try/except that re-raises after
  writing, preserving current retry semantics).
- Reprocess (`admin_gallery_reprocess_source` and the internal
  `_process_blob_bytes` path) resets status the same way.
- Tests: state transitions incl. failure re-raise, in
  `tests/test_pipeline_metadata.py`.

**SCN-API-001 — Metadata + status endpoints, list enrichment** (Theme A/B)
- `function_app.py`: `AdminGalleryMetadata`, `AdminGallerySourceStatus`
  routes (mirror `AdminGalleryImages` auth/error conventions).
- `_admin_gallery_items`: build a metadata index (single listing pass over
  `metadata/` analogous to `_lineage_source_index`) and attach `cardCount`,
  `hasMetadata`, `state` to items; `thumbUrl` null until SCN-THUMB-001.
- Tests: extend `tests/test_http_endpoints.py`; 404 codes per contract.
- Update `postman/` collection with the new routes.

### Phase 3

**SCN-REPROC-001 — Parameterized reprocess** (Theme E)
- `request_validation.py`: validate optional `conf` (0–1), `modelVariant`
  (must resolve via `resolve_model_id`), `perspectiveCorrect` from the
  reprocess JSON body.
- Thread overrides through `_process_blob_bytes` →
  `detect_cards_from_image_bytes`.
- Tests: bad values rejected with 400; overrides reach detection (fake
  model).

**SCN-SEG-001 — Real segmented outputs** (Theme E)
- When `perspective_correction_enabled` (or per-request override), upload
  perspective-corrected crops under `segmented/<folder>/…`; record in
  lineage `outputsByCategory.segmented` (today hardcoded `[]`) and in
  metadata `segmentedBlobName`.
- Tests: lineage/metadata record segmented names; gallery `segmented`
  category lists them (existing prefix plumbing already supports it).

**SCN-THUMB-001 — Thumbnails** (Theme E)
- New settings: `thumbnail_max_edge` (default 256), written as lossy WebP
  to `thumbs/` alongside each crop; `thumbBlobName` in metadata; `thumbUrl`
  in admin listing (served through existing `gallery_image` with a
  `thumbs`-aware prefix check).
- Tests: thumb generated per crop, size bound, listing exposes URL.

---

## App team work items (repo: trading-card-uploader)

### BFF (api-sas-issuer) — Phase 1 unless noted

**TCU-BFF-META-001** — `v1/admin/gallery/metadata` +
`v1/admin/scanner/source-status` proxies: routes in
`api-sas-issuer/function_app.py`, forwarding helpers in
`shared/gallery.py` (reuse the existing scanner-proxy machinery used by
`admin_scanner_status`); `scanner_not_configured` error contract preserved.
Tests in `api-sas-issuer/tests/test_gallery.py`.

**TCU-BFF-PRECHECK-001** (Phase 2) — `POST v1/uploads/precheck`: streams
the body to scanner `/api/layout?extract_crops=false&extract_text=false`,
maps the response to the contract shape, enforces the same max-size limit
as uploads. Tests: size limit, scanner-down mapping, shape mapping.

**TCU-BFF-REPROC-001** (Phase 3) — pass `conf`/`modelVariant`/
`perspectiveCorrect` through the reprocess proxy; validate types only
(scanner owns semantics).

### Android — Phase 1

**TCU-APP-META-001 — Metadata/status models + repository**
- `model/CardMetadata.kt`, `model/SourceStatus.kt` (Moshi, tolerant of
  nulls/unknowns per contract); parse tests against the shared fixtures in
  `app/src/test/resources/scanner_metadata_v1/`.
- `data/SasIssuerClient.kt`: `getSourceMetadata`, `getSourceStatus`;
  `data/GalleryRepository.kt`: wrappers following existing
  `requireBody`/error-detail conventions.

**TCU-APP-DETAIL-001 — Card detail screen** (depends TCU-APP-META-001)
- `ui/CardDetailScreen.kt` + `viewmodel/CardDetailViewModel.kt`: full-size
  crop (existing preview request specs), extracted name/price, confidence,
  "view source photo" navigation; graceful "no metadata yet" state.
- Entry: tap on a processed tile in `GalleryScreen` navigates to a new
  `card_detail` route in `MainActivity` NavHost (args: category, blob name,
  sourceBlobName).
- Reducer/state tests; navigation arg tests.

**TCU-APP-CAPTURE-STATUS-001 — Post-upload processing state** (depends
TCU-APP-META-001)
- `viewmodel/CaptureViewModel.kt`: after an upload completes, poll
  `getSourceStatus` (bounded backoff, stop on done/failed/timeout);
  `UploadStatusUi` gains `Scanning`, `Processed(cardCount)`, and
  `ProcessingFailed` presentations. Strings in `values/strings.xml`.
- Tests extend `UploadStatusUiTest` and `viewmodel` tests with a fake
  repository clock.

**TCU-APP-MONITOR-001 — Monitor enrichment**
- `MonitorModels.buildSourceActivities` consumes `cardCount`/`state` from
  the enriched list response; failed sources render distinctly with the
  existing reprocess action; card names (first N) shown on rows when
  metadata is present.
- Tests extend `MonitorModelsTest`.

### Android — Phase 2

**TCU-APP-PREP-001 — Preprocessing analyzer + engine** (Theme F, no
backend dependency — can start any time)
- New package `preprocess/`:
  - `ImageMetrics.kt`: pure-Kotlin metrics over an `IntArray` of luminance
    values from a ≤512px downsample — mean/percentile exposure, Laplacian
    variance (blur), resolution, EXIF rotation. No Android types in the
    math so it is JVM-testable.
  - `PreprocessSuggestions.kt`: metrics → suggestions mapping with
    documented thresholds (dark → brightness; low contrast → contrast;
    huge → downscale; EXIF ≠ 0 → rotate; blurry → retake warning only).
  - `ImagePreprocessor.kt`: applies selected ops via
    `Bitmap`/`ColorMatrix`/`Matrix` (rotate 90/180/270, brightness,
    contrast, auto-enhance, downscale), writes JPEG to app-private storage.
- Tests: metrics + suggestion mapping on synthetic arrays (dark image,
  flat image, sharp/blurred gradients); preprocessor via Robolectric only
  if already available, otherwise instrumentation-free seam and manual
  verification note.

**TCU-APP-PREP-002 — "Prepare photo" UI** (depends TCU-APP-PREP-001)
- `ui/PreparePhotoSheet.kt`: bottom sheet after capture/selection showing
  the suggestion checklist (suggested ops pre-checked), before/after
  preview, Apply / Skip. Applying swaps the pending upload's file for the
  processed copy before `repository.enqueue`.
- `MainActivity` capture/selection flows route through the sheet;
  `CaptureViewModel` gains the intermediate "preparing" state.
- Reducer tests; string resources; keep neutral+accent styling.

**TCU-APP-PRECHECK-001 — Pre-upload detection check** (Theme C, depends
TCU-BFF-PRECHECK-001 + TCU-APP-PREP-002)
- "Check card detection" action in the prepare sheet: posts the (possibly
  preprocessed) image to `v1/uploads/precheck`; renders count + bbox
  overlay; zero-detection warning with "upload anyway".
- `ui/BoundingBoxOverlay.kt`: shared composable drawing `bboxNorm` rects
  over an image (reused by TCU-APP-OVERLAY-001).

**TCU-APP-OVERLAY-001 — Gallery bbox overlay** (depends TCU-APP-META-001)
- Raw-category full-screen preview gains a toggle to draw the source's
  card boxes from metadata via `BoundingBoxOverlay`.

**TCU-APP-SEARCH-001 — Search/filter/sort**
- `GalleryUiState` + `GalleryStateReducers`: name filter (client-side over
  loaded pages using metadata names), sort newest/card-count; search field
  in `GalleryScreen` header. Reducer tests.

**TCU-APP-EXPORT-001 — Share crops as zip**
- Client-side zip of a source's already-downloaded crops (no new backend):
  gallery selection gains "Share" → zips crops to cache dir → Android share
  sheet via existing FileProvider. Avoids re-detection cost of
  `/api/process`.

**TCU-APP-GRID-001 — Grid density + pinch zoom**
- Density toggle (2/3/4 columns) persisted in `SharedPreferences`;
  pinch-to-zoom in full-screen preview (`Modifier.graphicsLayer` +
  transform gestures). UI-state tests for the toggle.

### Android — Phase 3

**TCU-APP-REPROC-001 — Tuned reprocess sheet** (depends SCN-REPROC-001 +
TCU-BFF-REPROC-001): reprocess action opens options (confidence slider,
model variant if >1 allowed, perspective toggle); plumbs through repository.

**TCU-APP-THUMB-001 — Thumbnail adoption** (depends SCN-THUMB-001): grid
tiles prefer `thumbUrl` when present, full image in detail; extends
`galleryPreviewRequestSpecs` fallback chain (thumb → full → admin endpoint).

---

## Execution order & dependency graph

```
Stage 0: WI-CONTRACT-001 (single agent, both repos)
           │
Stage 1 (parallel):
  Scanner team: SCN-META-001 → SCN-STATUS-001 → SCN-API-001
  App team:     TCU-BFF-META-001 ─┐
                TCU-APP-META-001 ─┼→ TCU-APP-DETAIL-001,
                (fixtures only)   │  TCU-APP-CAPTURE-STATUS-001,
                                  │  TCU-APP-MONITOR-001
                TCU-APP-PREP-001 → TCU-APP-PREP-002   (no backend dep)
           │
Checkpoint 1: integration smoke — app against deployed scanner Phase 1
           │
Stage 2 (parallel):
  App team:     TCU-BFF-PRECHECK-001 → TCU-APP-PRECHECK-001
                TCU-APP-OVERLAY-001, TCU-APP-SEARCH-001,
                TCU-APP-EXPORT-001, TCU-APP-GRID-001
  Scanner team: starts Phase 3 early — SCN-REPROC-001, SCN-SEG-001,
                SCN-THUMB-001 (independent of Stage 2 app work)
           │
Checkpoint 2: integration smoke — precheck + overlays
           │
Stage 3:
  App team:     TCU-BFF-REPROC-001 → TCU-APP-REPROC-001, TCU-APP-THUMB-001
           │
Final: full regression on both repos + docs updates
```

## Agent team topology

Two teams, one coordinator (this session):

- **Scanner team** — agents run with cwd `trading-card-scanner`, one
  feature branch per work item (`feature/scn-meta-001` …), sequenced per
  the graph; each item lands with tests green (`pytest`, `ruff`, `mypy`)
  before the next dependent item starts. Per that repo's AGENTS.md: no
  feature flags/toggles beyond existing settings; fail fast on missing
  config.
- **App team** — agents run in this repo's worktrees, branches
  `feature/tcu-…`; gates: `gradle test`, `detekt`, `ktlint` (gradle 8.10.2
  from %LOCALAPPDATA%\Temp with Android Studio JBR, lines ≤120). BFF items
  additionally run `pytest` under `api-sas-issuer`.
- Coordinator merges Stage 0 first, then dispatches team stages, runs
  checkpoint smokes, and is the only place cross-repo contract questions
  get resolved (contract change ⇒ update both mirrored docs in the same
  stage, never drift).
- Commits follow the existing `TCU-…`/work-item-ID convention; no pushes
  or PRs without explicit user go-ahead, per both repos' AGENTS.md.

## Test & verification strategy

- **Contract**: shared fixtures are the single source of truth; scanner
  serializer tests and Android Moshi parse tests both consume the same
  JSON shapes (copied, byte-identical, checked at review).
- **Scanner**: unit tests with fake container clients (existing pattern in
  `tests/`); no live storage (respect
  `test_no_live_storage_by_default.py`). New pipeline tests cover
  OCR-off, OCR-partial-failure, zero-card, and failure-status paths.
- **BFF**: pytest with faked scanner responses; error-mapping and size
  limit cases.
- **Android**: JVM unit tests for all reducers/models/analyzer math;
  fixture-driven parsing; no new instrumentation-test infrastructure in
  this program (manual smoke on device for camera/share flows, noted per
  work item).
- **Checkpoints**: coordinator runs an end-to-end smoke (upload → status
  transitions → metadata → detail screen) against a deployed or locally
  hosted scanner before advancing stages.

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| OCR in the blob pipeline adds latency/cost per upload | Gated by existing `ocr_enabled`; per-crop workers already bounded (`ocr_max_workers`); individual OCR failures never fail the pipeline |
| Blob-trigger timeout with OCR on many-card photos | `max_crops` already bounds work; log `duration_ms`; if observed near limits, follow-up to move OCR to a queue — out of scope now |
| Old sources have no metadata/status blobs | All readers treat missing docs as "no metadata" (`hasMetadata=false`); app renders image-only exactly as today |
| Contract drift between repos | Single Stage-0 doc mirrored byte-identical for shared shapes; coordinator owns changes |
| Status blob write failures masking real pipeline errors | Status writes are best-effort (logged), never swallow or replace the original exception |
| Preprocessing degrades detection (over-brightening etc.) | Ops are opt-in with preview; original file kept until upload completes; precheck lets the user verify before uploading |
| Polling status from Capture drains battery/data | Bounded backoff with hard stop (~2 min), only for uploads completed while the screen is active |

## Out of scope (unchanged from design)

External price lookups, multi-user collections, DB internals in UI, new
instrumentation-test infrastructure, queue-based OCR offload.
