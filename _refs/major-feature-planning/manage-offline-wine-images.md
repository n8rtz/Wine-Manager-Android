# Implementation Plan: Manage Offline Wine Images

## Goal

Give the user a visible, explicit way to inspect and remove app-private offline image copies so the configurable storage budget remains manageable. Removing a copy must never delete the wine, inventory, tasting notes, consumption history, or the image's source/provenance record. No image may be evicted automatically.

## Implementation status

The first management pass is implemented in the source: Profile has a scrollable manager, removal is individually confirmed, the Room path is conditionally cleared before a validated file delete, and importer-save/removal/orphan-cleanup mutations are serialized. Provenance stays in Room and removed assets remain eligible for the reviewed import queue. An instrumented repository test covers path clearing, metadata, bottle/note preservation, storage totals, stale requests, and the management-list projection. The app/test code has not been compiled or run in this workspace; device verification remains pending.

## Current architecture to reuse

- `WineImageAsset` is one Room row per `WineDefinition`; it already has the relative file path, source and contributor, product title, selected kind, encoded dimensions/size/hash, and import time.
- `WineImageAssetDao.getStoredBytes()` already counts only rows with a non-null `relativePath`.
- `WineImageImportRepository.cleanupOrphanFiles()` reconciles missing files and removes unreferenced files. The importer already treats a provenance row with `relativePath == null` as eligible for a new reviewed candidate search.
- `WineImageFileStore.localFile()` restricts paths to the app-private `wine-images/` directory; `deleteLocalImage()` deletes only a validated relative path.
- `ProfileScreen` already shows used bytes and the soft budget. The new entry point belongs there and should be separate from the image-import queue.

## UX scope

1. Add a **Manage offline images** action beside the Profile storage summary.
2. Open a dedicated, scrollable management view (a Profile destination or full-screen sheet) with a lazy list. Do not put an unbounded list inside a compact alert dialog.
3. Show each *verified local copy*: producer and wine name, bottle/label classification, source/contributor, compressed size, imported date, and a fit-scaled local thumbnail.
4. Provide a per-image **Remove offline copy** action. Require a confirmation that names the wine and size and explains that the wine/provenance remain while the image will no longer be available offline.
5. Canceling confirmation must make no changes. There is no bulk delete in the first version.
6. After removal, update the usage total and list immediately; explain that another offline copy can be obtained only through **Review Missing Images**, with a new explicit candidate approval. Keep the provider source link/provenance visible in Wine Detail.
7. If an asset has no local path (for example, it was restored from backup or its file was already missing), do not offer a delete action. Show it as not stored or direct the user to the existing reviewed-import flow. Do not fetch its source URL automatically.

## Persistence and file-lifecycle design

- Add a Room projection for an image asset plus its `WineDefinition`, and a DAO query for assets with a non-null local path, ordered newest first. Either observe the query as a `Flow` or refresh it after each management action.
- Before presenting the list, await the existing startup/orphan reconciliation so stale paths are nulled before the UI labels an image as stored. If the filesystem check finds a missing file, retain provenance, clear only the stale path, and let the reviewed importer handle it later.
- Add a repository operation such as `removeOfflineCopy(definitionId, expectedRelativePath)`. In a short database transaction, clear `relativePath` only if it still equals the path the user confirmed. Preserve the asset row, source fields, kind, size/hash provenance, and `WineDefinition.imageUrl`.
- After the database reference is cleared, delete the old file only if no Room row still references that path. Serialize this mutation with approved-image saving (or otherwise guard the same-path race); the importer names files by definition and hash, so a replacement may reuse the same path.
- If the Room update fails, leave the file untouched. If unlinking succeeds but filesystem deletion fails, keep the row safely unreferenced, report that space may not be freed immediately, and let orphan cleanup retry. Startup orphan cleanup remains the recovery path after interruption.
- Recompute `usedBytes` from referenced local assets after removal. No LRU, age-based, budget-based, or background deletion is permitted.
- **Room schema/migration:** this management feature alone does not need another schema version because `WineImageAsset.relativePath` is already nullable; removal uses `relativePath = null` to represent “provenance retained, no offline copy.” The database is now at v22: keep both the v20→v21 image-asset migration and v21→v22 color migration registered. Any future persisted management fields/tables must increment the Room version again, add/register the next migration, and test the full supported upgrade path.
- Existing backup behavior continues to export metadata only, and Android backup rules continue excluding image bytes.

## Implementation steps

1. **Data query/model:** add a one-to-one `WineImageAssetWithDefinition` Room projection and DAO Flow/query. Keep the existing one-row-per-definition relationship; do not add an image table or alter wine/inventory entities.
2. **Repository safety:** implement conditional path clearing, reference-count check, safe delete, and mutation serialization with `saveApprovedImage()`. Make delete outcomes explicit enough to distinguish “already absent” from “could not free space.”
3. **ViewModel state:** expose management rows, loading/error state, and one remove operation. On success, refresh rows and storage totals; on failure, leave the UI row visible and show a recoverable error. Wait for startup cleanup before first load/action.
4. **Profile UI:** add the Manage action and scrollable list, confirmation, progress state for the selected row, empty state, and a clear link/message for missing copies. Do not reuse importer candidate state or merge management into camera OCR.
5. **Manual checklist:** add remove/cancel/restart/backup/re-import checks to `_refs/THINGS_TO_CHECK.md`.

## Test plan for when Android tooling is available

### DAO/repository tests

- Seed two wine definitions and valid asset rows; confirm the management query joins the correct wine and returns only local-path rows after reconciliation.
- Remove one copy and assert that only its `relativePath` becomes null; source name, product ID/title, source URLs, attribution/license, kind, dimensions, hash, and wine `imageUrl` remain unchanged.
- Assert `getStoredBytes()` decreases by exactly the removed derivative size and leaves other images counted.
- Assert the local file is deleted only after its database reference is cleared. A stale/changed expected path must not clear a newer asset or delete its file.
- Exercise already-missing files and deletion failure; metadata must remain safe, no wine data changes, and orphan cleanup must be able to retry.
- Confirm the removed asset appears in the reviewed missing-offline queue; no URL request or durable download occurs until the user selects and approves a new candidate.

### Migration and upgrade compatibility

- Open a database created with the pre-importer v20 schema using the registered `MIGRATION_20_21`. Verify Room creates `wine_image_assets`, validates its primary/foreign keys, and preserves existing definitions, bottles, notes, ratings, and consumption events.
- Confirm every supported older-version migration chain still reaches v22 and that `MIGRATION_20_21` and `MIGRATION_21_22` remain registered in the database builder.
- Test an upgraded v22 database containing an approved image, then remove it. Since this feature changes no further schema, the Room version remains 22; the asset row should retain provenance with a null path and the local file should be deleted safely.
- Test Android backup/device restore where Room metadata survives but `filesDir/wine-images/` is excluded. Startup reconciliation must null stale paths, preserve wine/history data, and expose the asset to reviewed re-import.
- If implementation adds any persisted field/table beyond this plan, stop and add/register the next migration (v22→v23) plus migration tests before merging; do not rely on destructive migration.

### UI/manual checks

- Profile shows correct storage totals; the management list identifies image kind, size, and source.
- Cancel confirmation and back navigation preserve the image, file, and totals.
- Confirm removal; the offline copy disappears from the list, the total drops, the wine and all cellar/history/note data remain, and Wine Detail retains source/provenance without auto-fetching the remote image.
- Restart the app and verify the removal persists. Re-import through the existing review queue and confirm the new file again requires explicit bottle/label approval.
- Test in airplane mode and with low free storage. Removal remains local and available offline; no existing image is ever removed without its own confirmation.
- Export/restore JSON and verify metadata is preserved but local paths/bytes are not; Android cloud/device-transfer backups continue excluding `filesDir/wine-images/`.

## Acceptance criteria

- The user can identify the local images using storage and explicitly remove one copy at a time.
- Canceling does nothing; confirming affects only the selected image file/path and storage total.
- Provenance remains in Room/backup, and the image becomes eligible for a fresh reviewed import rather than silently reverting to network display.
- No auto-eviction or bulk deletion is introduced, and wines, bottles, quantities, tasting notes, ratings, and consumption events are unchanged.
- The operation is safe under repeated taps, app interruption, missing files, and a concurrent/repeated import of the same image.
