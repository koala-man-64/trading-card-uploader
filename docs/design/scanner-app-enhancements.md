# Design: Scanner + Android App Enhancement Program

Status: APPROVED 2026-07-05 (all themes; Theme F added at approval time)
Scope: `trading-card-scanner` (Python Azure Functions) and `trading-card-uploader`
(Android app + `api-sas-issuer` BFF)

## 1. Current-state review

### Scanner (`trading-card-scanner`)
- DETR-based card detection with confidence scores, bounding boxes, NMS,
  aspect/area plausibility filtering (`card_processor/detection*.py`,
  `process_utils.py`).
- Perspective correction support (`perspective.py`, settings-gated).
- OCR name/price extraction via Azure Document Intelligence
  (`text_extraction.py`) — **only reachable through the synchronous
  `/api/layout` endpoint**, never used in the async blob pipeline.
- Blob-triggered pipeline: `input/raw/*` → detect → crop → upload crops to
  `processed` → write lineage manifest (`lineage/<sha256>.json`) mapping
  source → output blob names. Manifest stores names only; card count,
  confidence, bboxes, timings, and errors are discarded.
- HTTP surface: `/api/layout` (detection w/ params: model_variant, imgsz,
  conf, iou, extract_crops, crop_format, extract_text, perspective_correct),
  `/api/process` (output=none|return|upload; zip/json), gallery page + JSON +
  image serving, admin gallery API (list w/ lineage join, delete-by-source,
  delete-image, reprocess-source) with Entra auth, `/api/health`,
  `/api/ready` (model warmup state, storage, OCR config).
- `segmented` gallery category exists in config but lineage always writes
  `"segmented": []` — dead category today.

### Uploader (`trading-card-uploader`)
- Android Compose app, three tabs:
  - **Capture**: camera + photo picker → Room queue → WorkManager → MSAL
    token → SAS from BFF → direct blob PUT. Retry/remove pending uploads.
    Status ends at "uploaded" — no visibility into what the scanner did.
  - **Gallery**: raw/processed categories, paging, multi-select,
    delete-image, delete-source-group, reprocess-source, swipe navigation,
    async previews with bearer-authenticated Coil requests.
  - **Monitor**: scanner health card (ready/warmup/unreachable) + source
    activity list joining raw uploads to processed crop counts via lineage.
- BFF (`api-sas-issuer`): SAS issuance, admin gallery proxies, scanner
  status proxy.

### The core gap
The pipeline discards nearly everything the scanner knows (count,
confidence, bboxes, OCR name/price, timings, failures), so the app can only
show images. Users get no card identity, no pipeline progress, and no
detection quality signal.

## 2. Design themes

### Theme A — Card metadata end-to-end (flagship)
Make the async pipeline persist what it learns, and surface it in the app.

- **Scanner**: during `ProcessBlob`, capture per-card detection metadata
  (label, confidence, bbox_xyxy/bbox_norm, crop blob name) and, when OCR is
  configured, run name/price extraction on each crop. Write one metadata
  document per source: `metadata/<sha256(source)>.json` in the processed
  container, versioned schema:

  ```json
  {
    "schemaVersion": 1,
    "sourceBlobName": "raw/....jpg",
    "processedAtUtc": "...",
    "modelId": "...",
    "cardCount": 3,
    "durationMs": 1234,
    "ocrRan": true,
    "cards": [
      {
        "cropBlobName": "abc_1.jpg",
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

- **Scanner**: `GET /api/v1/admin/gallery/metadata?sourceBlobName=` returns
  the document; list endpoint gains `cardCount`/`hasMetadata` fields.
- **BFF**: proxy `GET v1/admin/gallery/metadata`.
- **App**: card detail screen (tap a processed crop → full-size image,
  extracted name/price, confidence, link to source photo); gallery tiles
  show name badge when available; Monitor activity rows show names.

### Theme B — Pipeline status visibility
- **Scanner**: write a status marker per source at pipeline start/end:
  `status/<sha256(source)>.json` → `{state: processing|done|failed,
  error?, updatedAtUtc}` (done state can be folded into the metadata doc;
  failed keeps the error string). Reprocess resets it.
- **BFF**: fold status into the existing activity/list proxy or a new
  `v1/admin/scanner/source-status` endpoint.
- **App**: Capture screen upgrades the terminal state from "uploaded" to
  "uploaded → scanning → N cards found / failed"; Monitor shows failed
  sources distinctly with one-tap reprocess (action already exists).

### Theme C — Pre-upload assist
Use the existing synchronous endpoints before committing an upload.

- **BFF**: `POST v1/uploads/precheck` proxying scanner `/api/layout`
  (`extract_crops=false`) with the user's photo; returns count + boxes.
- **App**: optional "Check first" action after capture: show detected card
  count and bounding-box overlay on the photo; warn on zero detections
  before wasting an upload/processing cycle. Off by default on metered
  connections; photo still uploads unmodified.

### Theme D — Gallery & UX upgrades (app-heavy)
- Bounding-box overlay on raw source images in the gallery preview (boxes
  from Theme A metadata).
- Search/filter by extracted card name; sort by newest / card count.
- Export/share: "Share crops" for a source using scanner `/api/process
  output=return format=zip` (or a new export-by-source endpoint that zips
  existing crops — cheaper, no re-detection).
- Grid density toggle (2/3/4 columns) and pinch-zoom in the full-screen
  preview.

### Theme F — On-device preprocessing with suggestions
Help detection succeed before bytes ever leave the phone. Android-only; no
new backend surface.

- **Analyzer**: after capture/selection, the app analyzes a downsampled copy
  (≤512px) of the photo on-device and computes cheap metrics: luminance
  histogram (under/over-exposure), Laplacian variance (blur), resolution,
  and EXIF orientation. Pure-Kotlin math on pixel arrays so it is unit
  testable without a device.
- **Suggestions**: metrics map to pre-checked entries in a "Prepare photo"
  sheet — e.g. "Photo looks dark → Boost brightness", "Very large photo →
  Downscale", "Rotation looks off → Rotate", "Photo looks blurry → Retake
  recommended" (blur is warn-only; it cannot be fixed after the fact).
- **Operations list** (user picks any combination, sees a preview, applies):
  rotate 90°/180°/270°, brightness boost, contrast boost, auto-enhance
  (brightness+contrast from histogram), downscale to a max edge. Implemented
  with Android `Bitmap`/`ColorMatrix` — no OpenCV or other heavy deps.
- **Flow**: applying writes a new JPEG in app-private storage which becomes
  the upload payload; skipping uploads the original unchanged. Pairs with
  Theme C: the precheck can run on the preprocessed copy to confirm the fix
  helped.

### Theme E — Scanner ops & quality
- Reprocess with parameters: extend `reprocess-source` to accept optional
  `conf`, `model_variant`, `perspectiveCorrect` so bad detections can be
  retried with different settings from the app's selection sheet.
- Populate the `segmented` category for real: perspective-corrected crops
  land under `segmented/` when correction is enabled, lineage records them.
- Thumbnails: scanner writes ~256px WebP thumbs alongside crops
  (`thumbs/` prefix); gallery list returns `thumbUrl`; app loads thumbs in
  grids and full images only in detail view (big perf win on mobile data).

## 3. Contract-first parallelization

Both teams depend only on the metadata/status schema and the new endpoint
shapes. Step 1 of the implementation plan is a shared contract doc
(`docs/contracts/scanner-metadata-v1.md`, mirrored in both repos per each
repo's shared-contract routing rules). After that lands, teams work fully in
parallel; the app team codes against fixture JSON until the scanner ships.

- **Scanner team** (repo: `trading-card-scanner`): Themes A/B scanner side,
  E entirely.
- **App team** (repo: `trading-card-uploader`): BFF proxies + all Android
  work (A/B app side, C, D).

## 4. Proposed phasing

| Phase | Content | Value |
|-------|---------|-------|
| 1 | Theme A + Theme B | Card identity + pipeline progress; unblocks everything else |
| 2 | Theme D (overlay, search, export) + Theme C + Theme F | Daily-use UX and capture quality |
| 3 | Theme E (tunable reprocess, segmented, thumbnails) | Quality/perf |

Non-goals for this program: pricing lookups against external market APIs
(explicitly excluded at approval), multi-user collections, and any
DB-visible internals in the UI (per UI design direction: clean neutral +
single accent).
