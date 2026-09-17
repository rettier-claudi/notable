<!-- markdownlint-configure-file {
  "MD013": {"code_blocks": false, "tables": false},
  "MD033": false,
  "MD041": false
} -->

<div align="center">

[![License][license-shield]][license-url]
[![Total Downloads][downloads-shield]][downloads-url]
[![Discord][discord-shield]][discord-url]

![Notable App][logo]

# Notable (Fork)

A maintained and customized fork of the archived [olup/notable](https://github.com/olup/notable) project.

[![🐛 Report Bug][bug-shield]][bug-url]
[![Download Latest][download-shield]][download-url]
[![💡 Request Feature][feature-shield]][feature-url]

<a href="https://github.com/sponsors/ethran">
  <img src="https://img.shields.io/badge/Sponsor_on-GitHub-%23ea4aaa?logo=githubsponsors&style=for-the-badge" alt="Sponsor on GitHub">
</a>

<a href="https://ko-fi.com/rethran" target="_blank">
  <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi">
</a>

</div>

---
<details>
  <summary>Table of Contents</summary>

- [About This Fork](#about-this-fork)
- [Features](#features)
- [Download](#download)
- [Gestures](#gestures)
- [System Requirements and Permissions](#system-requirements-and-permissions)
- [Export and Import](#export-and-import)
- [Roadmap](#roadmap)
- [Troubleshooting and FAQ](#troubleshooting-and-faq)
- [Bug Reporting](#bug-reporting)
- [Screenshots](#screenshots)
- [Working with LaTeX](#working-with-latex)
- [App Distribution](#app-distribution)
- [For Developers & Contributing](#for-developers--contributing)

</details>


---


## This fork (rettier-claudi/notable)

Personal fork for a Boox Note Air 5C that is one end of a WebDAV bridge (the other end writes
notebooks on the server). Everything below is on top of upstream `main`; upstream is tracked as
the `upstream` remote and merged in as it moves.

- **Scribble-to-erase, double tap, sync cancel, pen layer on the home screen, battery**
  (v0.2.6-claudi.13).
  - *Scribble to erase* (`ScribbleGeometry.kt`, pure Kotlin with tests). Upstream took any stroke
    with 15 points, two direction changes and a long enough path as a scribble — "mmm" written
    under a line qualified and erased the line above along with what was just written — and then
    erased by bounding-box overlap: i-dots and commas between the zig-zags stayed, and a long
    line had to be scribbled over for a fifth of its length. Now a scribble must retrace along
    one axis (path ≥ 3.5 × its extent, ≥ 3 reversals) *and* lie over ink (≥ 40 % of its columns,
    60 % for vertical zig-zags, which look like handwriting), with a 300 ms pause before it.
    It erases what lies under the swept area (per 10 px column, top to bottom of the pen's
    track): strokes with ≥ 40 % of their length inside, strokes with a continuous piece inside
    at least 60 % of the scribble's width and 1.2 × its height (so a short scribble takes a
    whole long line), and small marks (≤ 30 px) within one scribble height above/below it.
    Sideways the area ends at the pen's turning points (+4 px, claudi.15): it used to reach a
    full scribble height sideways too, so scribbling out the last letter took the small letters
    next to it. A comma after a word only goes if the scribble runs over it.
  - *Double tap* counts only in the top-left 2/3 of the editor (width and height), with the
    second tap within 40 dp of the first and itself a tap (lifts in place). The writing hand
    rests bottom right.
  - *Sync*: DNS 5 s, connect 8 s, read/write 20 s of inactivity (upstream: no DNS bound,
    30/60/60 s). Tapping the sync chip or toolbar button while a sync runs cancels it: the
    immediate WorkManager runs are dropped (the periodic schedule stays) and every network call
    of the round in flight is cancelled, including in-process rounds (`SyncCancellation`, an
    OkHttp event listener; blocking `execute()` ignores coroutine cancellation). No retry follows
    a cancelled round. Nothing is left half-done: downloads land via `.part` + rename, uploads
    are single PUTs recorded only after the server answered.
  - *Pen layer after Send*: the Onyx raw-drawing layer could stay on over the home screen, so
    the pen drew instead of navigating. `surfaceDestroyed` never called `closeRawDrawing()`
    (it compared the canvas hash with the input handler's hash), and a delayed "drawing on"
    could arrive after the editor had closed. Now the canvas closes raw drawing when detached,
    raw drawing is only enabled on a live surface, and the delayed "on" is dropped once the
    editor is inactive.
  - *Battery*: the time-window `chunked` flow operator polled in a tight loop, spinning a core
    for a second after every page save; it now suspends. The idle ("settle") sync only runs
    when something was written since the last one, and reads the sync settings at most every
    10 s instead of on each activity pulse. A preview on screen re-checks its thumbnail at most
    every 5 minutes per page.
- **The sync password is decrypted once per process, never with a fresh key, and failures are
  recorded** (v0.2.6-claudi.12). On 2026-09-15 the chip said the password was gone while the
  stored one was fine: the server log shows syncs all morning, and Settings found it again. The
  app decrypted the password with the Android Keystore on *every* read of the sync settings —
  each touch pause (activity pulse), each chip update, each sync step — so one passing Keystore
  failure blanked it for that caller. Now the first successful decrypt is kept in memory for
  the exact stored ciphertext (`PasswordMemo` in `KvProxy`); later reads don't touch the Keystore.
  A failed decrypt is retried twice (150 ms, 600 ms). `CryptoHelper.decrypt` no longer "ensures
  the key exists": on Android 12+ `KeyStore.containsAlias` returns `false` for *any* Keystore
  error, not just a missing key (AOSP `AndroidKeyStoreSpi.getKeyMetadata`), so a hiccup there
  generated a new key over the old one and the stored password could never be read again. That
  is a second possible cause for the loss of 2026-09-14. Keys are only created when a password
  is encrypted. When the stored password can't be read, the chip says *Sync paused: password
  unreadable* rather than *no password*. Every failed or retried decrypt goes into a log in the
  KV table (`SyncPasswordDiagnostics`, key `SYNC_PASSWORD_EVENTS`, last 200 lines, with pid and
  process uptime, never the password). It also shows in the in-app sync log, and the next
  successful full sync uploads it to `diagnostics/sync-password.log` on the server (only when
  there are new lines, so normally no extra request). The bridge doesn't read that directory.
- **Thumbnails follow the page** (v0.2.6-claudi.11). Upstream only ever rendered a thumbnail
  that was *missing*: once a file existed, the library, the scratch-note tiles and the page
  overview showed it forever. Leaving the editor never saved one either — the save was started
  on the editor's own coroutine scope, which is cancelled in the same moment — so only a page
  change inside a notebook refreshed anything. Now: leaving a page (next/previous page, closing
  the editor) re-renders its thumbnail from the DB once the page's pending writes have landed
  (`PageDataManager.refreshThumbnailAfterWrites`, on the manager's own scope); every preview
  shown asks for a silent re-render when the page was edited after its thumbnail
  (`ThumbnailBackfillQueue.refresh`, no snackbar — the missing-thumbnail backfill keeps its
  progress snack); a page replaced by a sync download drops its thumbnail and gets a new one
  (`ThumbnailGenerator.invalidate`, then `AppEvent.PageDownloaded`), because its `updatedAt`
  is then the server's edit time and may be older than the old thumbnail. Stale means
  *thumbnail mtime < page `updatedAt`*; a DB-rendered thumbnail is stamped with the render's
  start time so an edit during the render still counts as newer (upstream's one-minute slack is
  gone). The thumbnail is no longer cut from the editor's on-screen bitmap, which was only the
  visible window of a zoomed or scrolled page.
- **Sync password survives a failed decrypt; a missing password shows; finger hold can be off**
  (v0.2.6-claudi.10). After every successful full sync, and whenever a sync setting was toggled
  with the password field blank, the app used to decrypt the stored password and write it back;
  when the Keystore failed to decrypt, that saved an empty password and every later sync was
  skipped without a word (seen 2026-09-14: last request 07:16, then silence). Both writes now keep
  the encrypted password as stored (`KvProxy.updateSyncSettingsKeepingPassword`, sent upstream as
  Ethran/notable#322). If sync is on and no password is available, the home screen's sync chip
  says *Sync paused: no password*. *Settings → Gestures → Finger Hold Action* exposes the
  existing `holdAction` (default *Select*); *None* keeps a resting finger from entering selection
  mode, which otherwise lets a finger pick up and move the images the bridge puts on a page.
- **Scratch-note notebooks: `"kind": "scratch"` in the manifest, no subfolders** (v0.2.6-claudi.9).
  Every folder view — `Today`, `Yesterday`, a day folder, any folder — has the same two sections
  as the Workspace: *Scratch notes* on top (a row of tiles), *Notebooks* below (the grid). Which
  notebooks are tiles in the scratch row is decided by one marker the server side writes:
  **`kind`, a top-level string field in `manifest.json`, value `scratch`; absent for an ordinary
  notebook.** The app reads it on download, stores it (`Notebook.kind`, Room 37 → 38), and writes
  it back **verbatim** on every upload of that manifest — also a value it does not know — so the
  marker survives this device editing the notebook. Any value other than `scratch`
  (case-insensitive, trimmed) is kept but means nothing here: such a notebook stays in the grid.
  `kind` is never set by the app; nothing in the UI creates or changes it. A scratch-kind tile
  looks like a real scratch note (100 dp preview, tap opens the **first** page), with a notebook's
  badges — checked box when sent and unchanged since, sync state — and a notebook's long-press
  settings (rename, delete, export); a conflicted one opens the resolution dialog like a grid
  card; the page count only shows when there is more than one page. A scratch-kind notebook
  with no pages stays in the grid, where the empty-notebook warning handles it. In the
  Workspace the scratch row shows this device's own scratch notes (`quickpages/`) as before, plus
  any scratch-kind notebook that happens to be in the root (the server side puts none there). The
  subfolders `Today/Scratch notes` and `Today/Notebooks` are no longer created by the server side;
  the code that keeps subfolders of `Today` in the sync scope and draws them in the folder bar
  stays as it is and simply sees none. **What the server side has to know:** write `kind` when it
  creates the copy; when it rewrites a manifest (move, rename), write the whole manifest back so
  `kind` is kept; a device still on claudi.8 drops `kind` the first time it re-uploads the
  manifest (its serializer ignores unknown keys), so the marker is only stable once claudi.9 is
  installed.
- **Names on screen: "Scratch notes", "Workspace", "Today"** (v0.2.6-claudi.8). What upstream
  calls *Quick pages* is *Scratch notes* everywhere in the UI (home screen row, settings, sync
  log lines); the root of the library is *Workspace* (was *Ablage*); the always-synced folder is
  the root folder titled `Today` (was `Heute`). Internal names are unchanged on purpose:
  `quickpages/` on the server, `QuickPage…` in the code, `home_quick_pages` in the resources — the
  sync protocol does not know about the rename. **What the server side has to know:**
  - The always-synced folder is matched by **title**, case- and whitespace-insensitive, among the
    **root** folders only. Accepted titles: `Today` and, as an alias for the transition, `Heute`
    (`ALWAYS_SYNCED_FOLDER_TITLES`). A subfolder called `Today` somewhere else does not count.
  - **Subfolders of `Today` are in the default scope**, however deep: the server side creates
    `Today/Scratch notes` and `Today/Notebooks` (`parentFolderId` = the id of `Today`, ordinary
    entries in `folders.json`), and the same two below each day folder `dd.mm.yyyy`. A notebook
    in any of them gets its conditional manifest GET every round, exactly like one in the root;
    when the day folder is renamed away from `Today` at night its subfolders leave the scope with
    it (same ids, new parent title). The home screen's sync button inside a folder syncs that
    folder *and* its subfolders.
  - The folder bar sorts `Today` / `Heute` first, `Yesterday` / `Gestern` second, then
    `dd.mm.yyyy` newest first, then the rest alphabetically — root folders only. Inside `Today`
    (or a day folder) the bar continues with that folder's subfolders, alphabetical, drawn with a
    "↳" icon; inside `Today/Scratch notes` both siblings stay visible with the open one filled.
    Subfolders are only ever displayed and opened; *New folder* still creates a root folder.
- **Sync round holds a wake lock and a Wi-Fi lock; the leave-the-app round runs in-process**
  (v0.2.6-claudi.8). Every full round and every single-notebook round takes a partial
  `WakeLock` and a `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) for its duration
  (`SyncPowerGuard`, released in `finally`, 5-minute backstop timeout), and the round started
  when the app leaves the screen no longer goes through WorkManager but runs at once in the
  application scope, locks held from the first millisecond. Why: with the system's *turn Wi-Fi
  off in sleep* on, the round that starts as the tablet goes to sleep lost the race against the
  radio. **What this can and cannot do:** the locks stop the *framework* from powering the radio
  down and the process from being frozen mid-round, and cut the WorkManager start latency to
  zero; they cannot stop a firmware that explicitly disables Wi-Fi from its own power manager —
  Android does not let an app veto that, and Onyx's SDK offers no lock either (its `wifiLock` /
  `setWifiLockTimeout` on `BaseDevice` are empty stubs, `WifiAdmin.setWifiEnabled` is the plain
  framework call, which is a no-op for apps targeting Android 10+). **Not built, on purpose:** a
  "switch Wi-Fi off N minutes after sleep" setting. `WifiManager.setWifiEnabled` is dead for this
  app (targetSdk 35; only `targetSdk` ≤ 28 or a system/device-owner app may still call it), the
  firmware's own sleep/Wi-Fi timeouts live behind `android.onyx.hardware.DeviceController`
  (standby and power-off timeouts only, reached via reflection) and `WRITE_SECURE_SETTINGS`,
  neither of which a sideloaded app has. If the in-process round still loses on the device, the
  fix is on the system side: keep Wi-Fi on in sleep (Boox *Settings → Power*), and let the
  device's own inactivity timeouts handle the battery.
- **Sync scope: the root and "Today", not every notebook** (v0.2.6-claudi.7; folder names and
  subfolders as of claudi.8 above). A full round —
  wake-up, activity, leaving the app, the periodic job, the home screen's sync button in the
  root — checks the manifests of the notebooks in the root ("Workspace") and in the folder titled
  `Today` (then `Heute`) only, plus any notebook with local changes not yet on the server (never synced, edited
  since, last sync in error) — pushing costs requests only when there is something to push, so a
  book moved out of `Heute` still gets its move up. The folder is matched by **title**, not id,
  because the server renames its day folders every night (`Heute` → `Gestern` → `dd.mm.yyyy`).
  Inside another folder the home screen's sync button syncs exactly that folder (`SyncAll(folderId)`);
  the editor's sync button and *Send* always include the open notebook (`SyncAll(notebookId)`),
  whatever folder it is in. Everything else in a round is scope-independent and fixed-cost:
  folders.json, tombstones, *new* remote notebooks (downloaded whatever their folder — a notebook
  is new only once), local deletions, quick pages. Idle round with 6 notebooks before: 12
  requests (root PROPFIND, notebooks PROPFIND, folders.json GET + PUT, deletions PROPFIND,
  quickpages PROPFIND, 6 × conditional manifest GET); after: 6 + one GET per notebook in scope.
  A server-side change to a notebook outside the scope arrives when its folder is synced, or —
  for a move into the root or `Heute` — the moment its folder becomes `Heute`. `folders.json` is
  still written back every round: the server side reads its `serverTimestamp` as this device's
  heartbeat; drop that PUT only together with that.
- **Folder tombstones** (v0.2.6-claudi.7): `deletions/folder-<folderId>`, zero bytes, in the same
  directory as notebook tombstones (same listing — no extra request — same 90-day prune, same
  resurrection rule). A folder with a tombstone is dropped from the folders.json merge on both
  sides and deleted locally, *unless* the local copy's `updatedAt` is after the tombstone's
  `Last-Modified` — then it is kept, re-uploaded, and the tombstone removed. Contents still in a
  server-deleted folder move to the root (notebooks with a fresh `updatedAt`, so the move
  reaches the server; quick pages untouched); nothing is deleted with the folder. Tombstones are
  listed *before* the folder merge, otherwise the union merge would re-upload the folder in the
  same round. Deleting a folder in the app writes the tombstone (`UploadFolderDeletion`) and,
  as upstream, cascades to its notebooks (which are then tombstoned by the next round). What the
  server side has to do: put the tombstone first, then rewrite folders.json without the folder;
  before "restoring" a folder missing from folders.json, check for its tombstone.
- **Folder rename stamps `updatedAt`** (v0.2.6-claudi.7): the merge is last-writer-wins per
  folder, and upstream's rename kept the old timestamp, so the server's copy of the title won
  the next round and the rename silently reverted. A rename — here or on the server — is a
  newer `updatedAt` with a new title on the same id; no notebook is involved, so nothing is
  re-transferred and nothing conflicts. A renamed folder never comes back under its old name:
  the older timestamp loses. Whoever wants to rename it back has to write a newer `updatedAt`.
- **Moves.** A notebook moved to another folder here gets `updatedAt = now` and uploads its
  manifest (plus one `PROPFIND` of `pages/`; no page is re-uploaded). A notebook moved on the
  server (new `parentFolderId`, `updatedAt` more than 1 s newer, manifest published by tmp +
  `MOVE` so page ETags stay put) is downloaded as a manifest plus one `pages/` listing; no page is
  fetched. A tied `updatedAt` with a different `parentFolderId` is a structural conflict
  (dialog) — the server must make its `updatedAt` clearly newer. A manifest pointing at a folder
  this device does not have is placed in the root with a log line instead of failing the download.
- **Sent quick pages are exempt from the wipe guard** (v0.2.6-claudi.7): a locked (sent) quick
  page whose server file vanished is the consumer's expected "ingested, done", never evidence of
  a misread listing, and is deleted locally whatever the count. `looksLikeQuickPageWipe`
  (≥ 3 and more than half) now counts only never-sent pages against never-sent rows. The server
  side may remove any number of *sent* pages at once (those it received a `sync-and-notify`
  webhook for, `notebookId: null`, `syncSucceeded: true`); for pages it ingested without a send
  the old limit of two per device round still applies.
- **Folder bar instead of breadcrumb + folder list** (v0.2.6-claudi.7): the home screen and every
  folder show the same bar at the top — *Workspace* (the root; *Ablage* until claudi.7) and every
  root folder, the open one filled black; tap to switch, long-press a folder for rename/delete.
  Order: `Today`, `Yesterday` (German titles accepted),
  day folders `dd.mm.yyyy` newest first, then the rest alphabetically (Room's insertion order
  was meaningless once folders get renamed in place). No nesting is offered: *New folder* always
  creates a root folder; a nested folder from before is still reachable (its path and children are
  appended to the bar while it is open). *New folder* and *Open file* (PDF/xopp import) are
  icon-only buttons next to the sync chip; the grid tile only creates notebooks now. Both top
  rows have fixed heights: the old breadcrumb put a 24 dp chevron next to 20 sp text inside a
  folder only, which made the bar a pixel or two taller there than on the home screen.
- **Fixed pages** — *Settings → General → "Fixed pages — disable scrolling"*: the canvas never
  moves at 100 % zoom (no drag scroll, no flick, no scroll indicators). Panning stays possible
  while zoomed so a zoomed page remains reachable.
- **Physical buttons / system gestures turn pages** — *Settings → Gestures → "Physical buttons
  turn pages"* (on by default): Volume up/down, Page up/down and D-pad keys go to the previous/next
  page while a notebook is open. On Boox, map a system side-swipe gesture to "page turn" or
  "volume" and it lands here as one of these keys. Keys keep their normal meaning in the library.
- **Sync when the app comes back to the foreground** — *Settings → Sync*: a full sync on every
  resume (device wake-up, app switch), rate-limited to one request per 30 s and skipped offline.
  Previously only a fresh app start synced, so a tablet that merely woke up never pulled changes.
- **Activity-driven syncing instead of a poll** (*Settings → Sync*): one sync a configurable
  number of minutes after the last activity in the app (default 2), and one when activity resumes
  after a longer pause (default 10 min). Nothing periodic runs in the foreground; while you write,
  no sync is expected, and the toolbar button covers the "now, please" case. Activity means any
  touch/key the window sees plus every committed stroke (Onyx's raw pen path bypasses touch
  dispatch, so strokes report separately). The ≥ 15 min WorkManager job stays the background
  fallback.
- **Fewer requests per round.** An idle round with four notebooks went from 14 requests to 8: the
  preflight's root listing now answers the `folders.json` / `deletions` / `quickpages` existence
  questions (three HEADs, one of them a 301 + retry on nginx, and a `MKCOL` per round), the orphan
  collector reuses the run's notebook listing instead of repeating the `PROPFIND`, and the
  quick-page round makes no request at all when there is nothing to upload and nothing was ever
  uploaded. What remains is the root probe, two listings, the tombstone listing and one conditional
  manifest GET per notebook.
- **Deliberately not done: skipping the per-notebook manifest GET via the directory's
  `getlastmodified`.** On a server without ETag propagation (nginx) the notebook directory's mtime
  does move when the manifest is published through tmp-PUT + `MOVE`, which would save one
  conditional GET per notebook per idle round. It is not worth it: the mtime does *not* move when
  a manifest is overwritten in place, so any writer that does so — an SSH one-liner, an rsync, a
  future tool — would become invisible to the device, silently and with no error anywhere. Four
  304 responses are the wrong thing to trade a silent failure mode for. Decided 2026-09-11 with
  the bridge side, which uses tmp+MOVE anyway but must not be something the device depends on.
- **No snack for a sync that simply worked.** Success, "skipped", "already running" and
  cancellation are silent — each snack is an e-ink repaint that leaves ghosting, and the status is
  already on the library chip and the toolbar button. Failures still interrupt.
- **Quick pages carry the same sync badge as notebooks** in the library.
- **Sync indicator and button on the home screen** (top right, next to settings): state of the
  engine, time of the last successful sync, count of notebooks with unsynced edits or conflicts.
  Tap = "Sync now" / retry.
- **Toolbar buttons** `SYNC` (status icon: syncing = pressed, ✓ after success, ⚠ after an error;
  tap = sync now) and `SYNC_NOTIFY` (sync, wait for it to finish, then HTTP POST a small JSON to
  *Settings → Sync → Notify URL*; hidden without a URL). Both are in the default pinned zone and
  placeable via *Settings → Toolbar* for custom layouts. Body:
  `{"source":"notable","event":"sync-and-notify","pageId":…,"notebookId":…,"syncSucceeded":true,"time":"…Z"}`.
- **Send = sync and notify, then back to the library** (v0.2.6-claudi.6). Pressing `SYNC_NOTIFY`
  (or the gesture action "Send") returns to the home screen at once; sync and notification run on
  in the background, the home sync chip shows them, a snack reports the result. The editor's
  sync-on-close is skipped while a send runs (the send's own full sync covers it, and the two would
  only collide on the engine's lock). The send repeats the sync up to three times until the page's
  *current* content is confirmed on the server — a request folded into a run that started before
  the last strokes, or one that bounced off a sync already in progress, no longer counts as done.
  - `syncSucceeded` in the body now means exactly that: the page's (quick page) or notebook's
    current state is confirmed on the server. Before claudi.6 it only meant "the sync job ended
    without a hard failure", which was also true for a skipped run or one that bounced off
    another sync. Payload shape and fields are unchanged.
- **A sent quick page is locked for good** — read-only on the device: pen, eraser, undo/redo,
  paste, image, background, clear and selection are refused (the pen is switched off, every content
  write is guarded in `PageView`). There is no way back by editing; *Duplicate* in the page menu
  gives an editable copy (a new quick page with new stroke ids, uploaded like any other). The
  page shows *Sent · locked* top right, the library a lock next to the sync badge. Delete still
  works.
  - Only a send that got the content confirmed on the server **and** a 2xx from the notify URL
    locks. Anything else locks nothing and the error stays visible (snack, sync chip).
  - The lock is device state (`SENT_MARKS` in the app's key-value table, i.e. in
    `Documents/notabledb`), not part of the page: it does not touch `Page.updatedAt`, the page
    JSON, or the sync rows. So locking uploads nothing, and the server-side "delete
    `quickpages/<id>.json` → the device deletes the page" works exactly as before (it asks "edited
    since the last upload?", which a lock does not change). Opening, closing, the sync button and
    the sync-on-close do not write to a quick page either; only content edits move its
    `updatedAt`. The lock of a deleted page is dropped.
- **A sent notebook is marked, not locked**: *Sent* top right in the editor and a checked box
  next to the sync badge in the library, until the next local change (stroke, new page, rename …)
  — then the mark is dropped for good. A server copy replacing the notebook (download) is not a
  local change and keeps the mark.
- **Two-finger swipe left / right** as own gestures (*Settings → Gestures*, default none). Only at
  100 % zoom: two fingers moving mostly sideways are then the swipe instead of a pan (which cannot
  move the page sideways there anyway); vertical two-finger movement still pans and pinch still
  zooms; zoomed in or out, two fingers pan as before. Needs the full one-finger swipe distance,
  since it may be "Send". With both unassigned nothing changes. The existing "three finger swipe"
  settings stay as they are. (A three-finger swipe whose third finger the panel misses reads as a
  two-finger swipe — unavoidable.)
- **Gesture actions "Back to Home Screen" and "Send"** — selectable for every gesture in
  *Settings → Gestures*. "Send" is exactly the `SYNC_NOTIFY` button, lock/mark included; without
  sync or a notify URL it only shows a hint.
- **Sync when leaving the app** (*Settings → Sync*, default on): a full sync on `onStop`, not
  rate-limited, so the last strokes are on the server before the tablet sleeps.
- **Quick pages never deleted on a guess.** Deleting a local quick page because its file is gone
  from the server needs positive evidence on every count: a listing that actually succeeded (an
  unknown listing decides nothing), a sync row proving the page reached the server, and no local
  edit since. A deletion that would take at least three pages and more than half of everything ever
  uploaded is refused as a misread. Every such deletion writes the page to
  `Documents/notabledb/quickpages-deleted/<id>.json` first, so it is recoverable by hand. This
  replaces the v0.2.6-claudi.2/.3 behaviour that could delete local quick pages, which it did.
- **Quick pages sync** (*Settings → Sync*, default on): pages without a notebook are uploaded
  one-way to `notable/quickpages/<pageId>.json` (images under `quickpages/images/`) whenever they
  change; a quick page deleted on the device is deleted on the server. The only thing taken from
  the server is a deletion: remove the page file there and the page disappears on the device —
  unless it was edited since its last upload, then it is re-uploaded instead. Meant for a server-side
  consumer that ingests scribbled notes and then removes them.
- **Page replaced by a download while open**: within 10 s after start/wake-up (the resume sync)
  the canvas reloads silently; later a snack offers "Reload", so the wake-up sync never turns
  into a conflict with an untouched page.
- Sync bug fixes found with a server-side writer:
  - A notebook downloaded in a sync run was tombstoned and deleted from the server one second
    later (`detectAndUploadLocalDeletions` compared sync-state rows against the pre-download
    snapshot only) and then re-uploaded by the next run.
  - Synced image backgrounds rendered white: the relative name stored by sync was opened as-is.
    Relative names now resolve against the managed `backgrounds/` folder.
  - Synced image elements ("cannot load PNG"): sync stores a plain absolute path, which the
    content resolver cannot open. Scheme-less paths are decoded straight from the file.
  - Sync log keeps 300 lines instead of 50.

Build: `./gradlew assembleRelease` with `STORE_FILE`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`
set (own keystore; the APK is not installable over the upstream build, uninstall that first —
notebooks live in `Documents/notabledb` and survive, sync credentials must be re-entered).
Releases: https://github.com/rettier-claudi/notable/releases

## About This Fork
This project began as a fork of the original Notable app and has since evolved into a continuation of it. The architecture is largely the same, but many of the functions have been rewritten and expanded with a focus on practical, everyday use. Development is active when possible, guided by the principle that the app must be fast and dependable — performance comes first, and the basics need to feel right before new features are introduced. Waiting for things to load is seen as unacceptable, so responsiveness is a core priority.

Future plans include exploring how AI can enhance the app, with a focus on solutions that run directly on the device. A long-term goal is local handwriting conversion to LaTeX or plain text, making advanced features available without relying on external services.

---

## Features
* ⚡ **Fast page turns with caching:** smooth, swift page transitions, including quick navigation to the next and previous pages.
* ↕️ **Infinite vertical scroll:** a virtually endless canvas for notes with smooth vertical scrolling.
* 📝 **Quick Pages:** instantly create a new page.
* 📒 **Notebooks:** group related notes and switch easily between notebooks.
* 📁 **Folders:** organize notes with folders.
* 🤏 **Editor mode gestures:** [intuitive gesture controls](#gestures) to enhance editing.
* 🌅 **Images:** add, move, scale, and remove images.
* ➤ **Selection export:** export or share selected handwriting as PNG.
* ✏️ **Scribble to erase:** erase content by scribbling over it (disabled by default) — contributed by [@niknal357](https://github.com/niknal357).
* 🔄 **Auto-refresh on background change:** useful when using a tablet as a second display — see [Working with LaTeX](#working-with-latex).

---

## Download
**Download the latest stable version of the [Notable app here.](https://github.com/Ethran/notable/releases/latest)**

Alternatively, get the latest build from the main branch via the ["next" release](https://github.com/Ethran/notable/releases/next).

Open the **Assets** section of the release and select the `.apk` file.

<details><summary title="Click to show/hide details">❓ Where can I see alternative/older releases?</summary><br/>
You can go to the original olup <a href="https://github.com/olup/notable/tags" target="_blank">Releases</a> and download alternative versions of the Notable app.
</details>

<details><summary title="Click to show/hide details">❓ What is a 'next' release?</summary><br/>
The "next" release is a pre-release and may contain features implemented but not yet released as part of a stable version — and sometimes experiments that may not make it into a release.
</details>

---

## Gestures
Notable features intuitive gesture controls within Editor mode to optimize the editing experience:

#### ☝️ 1 Finger
* **Swipe up or down:** scroll the page.
* **Swipe left or right:** change to the previous/next page (only available in notebooks).
* **Double tap:** undo (fork: top-left 2/3 of the screen, both taps close together).
* **Hold and drag:** select text and images.

#### ✌️ 2 Fingers
* **Single tap:** switch between writing and eraser modes.
* **Pinch:** zoom in and out.
* **Drag:** move the canvas.

#### 🤟 3 Fingers
* **Swipe left or right:** show or hide the toolbar.
* **Swipe up:** open QuickNav (can be disabled in Gesture settings).

> ⚠️ **Caveat:** on Onyx devices, a three-finger swipe is also the system's screenshot gesture, which can steal the touch before Notable sees it. Enabling **"Block system gestures while in Notable"** in Gesture settings fixes this, but as a side effect, edge-navigation swipes (e.g. back/recent-apps) stop working while the app is open.

#### 🔲 Selection
* **Drag:** move the selection.
* **Double tap:** copy the selected writing.

---

## System Requirements and Permissions
The app targets Onyx BOOX devices and requires Android 10 (SDK 29) or higher. Limited support for Android 9 (SDK 28) may be possible if [issue #93](https://github.com/Ethran/notable/issues/93) is resolved. Handwriting functionality is currently not available on non-Onyx devices. Enabling handwriting on other devices may be possible in the future but is not supported at the moment.

Storage access is required to manage notes, assets, and to observe PDF backgrounds, which need “all files access”. The database is stored at `Documents/notabledb` to simplify backups and reduce the risk of accidental deletion. Exports are written to `Documents/notable`.

---

## Export and Import

The app supports the following formats:

- **PDF** — export and import supported. You can also link a page to an external PDF so that changes on your computer are reflected live on the tablet (see [Working with LaTeX](#working-with-latex)).  
- **PNG** — export supported for handwriting selections, individual pages, and entire books.  
- **JPEG** — export supported for individual pages.  
- **XOPP** — export and import partially supported. Only stroke and image data are preserved; tool information for strokes may be lost when files are opened and saved with [Xournal++](https://xournalpp.github.io/). Backgrounds are not exported.  


---

## Roadmap
This is a rough, unordered list of ideas and in-progress work, not a committed schedule — priorities shift depending on what's needed at the time.

- Currently being worked on:
  - Customizable toolbar: allow modifying which tools appear and in what order.
  - Non-Onyx device support: make Notable usable on other Android devices, without depending on the Onyx SDK.
- Sync: groundwork already exists in the codebase, but it's not ready for use yet.
- Better selection tools:
  - Stroke editing (color, size, etc.)
  - Rotate and flip selection
  - Auto‑scroll when dragging a selection near screen edges
  - Easier selection movement, including dragging while scrolling
- PDF improvements:
  - Allow saving annotations back to the original PDF
  - Improved rendering and stability across devices
- PDF annotation enhancements:
  - Display annotations from other programs
  - Additional quality‑of‑life tools for annotating imported PDFs
- Bookmarks, tags, and internal links — see [issue #52](https://github.com/Ethran/notable/issues/52), including link export to PDF.
- Figure and text recognition — see [issue #44](https://github.com/Ethran/notable/issues/44):
  - Searchable notes
  - Automatic creation of tag descriptions
  - Shape recognition
  - Handwriting to Latex

---

## Troubleshooting and FAQ
**What are “NeoTools,” and why are some disabled?**
NeoTools are components of the Onyx E-Ink toolset, made available through Onyx’s libraries. However, certain tools are unstable and can cause crashes, so they are disabled by default to ensure better app stability. Examples include:

* `com.onyx.android.sdk.pen.NeoCharcoalPenV2`
* `com.onyx.android.sdk.pen.NeoMarkerPen`
* `com.onyx.android.sdk.pen.NeoBrushPen`

---

## Bug Reporting

If you encounter unexpected behavior, please include an app log with your report. To do this:  
1. Navigate to the page where the issue occurs.  
2. Reproduce the problem.  
3. Open the page menu.  
4. Select **“Bug Report”** and either copy the log or submit it directly.  

This will open a new GitHub issue in your browser with useful device information attached, which greatly helps in diagnosing and resolving the problem.  

Bug reporting with logs is currently supported only in notebooks/pages. Issues outside of writing are unlikely to require this level of detail.  

---


## Screenshots

<div style="display: flex; flex-wrap: wrap; gap: 10px;">
  <img src="https://github.com/user-attachments/assets/c3054254-043b-4cce-8524-43d10505ad0b" alt="Writing on a page" width="200"/>
  <img src="https://github.com/user-attachments/assets/c23119b7-cdae-4742-83f2-a4f39863c571" alt="Notebook overview" width="200"/>
  <img src="https://github.com/user-attachments/assets/9f3e7012-69e4-4125-bf69-509b52e1ebaf" alt="Gestures and selection" width="200"/>
  <img src="https://github.com/user-attachments/assets/24c8c750-eb8e-4f01-ac62-6a9f8e5f9e4f" alt="Image handling" width="200"/>
  <img src="https://github.com/user-attachments/assets/4cdb0e74-bfce-4dba-bc21-886a5834401e" alt="Toolbar and tools" width="200"/>
  <img src="https://github.com/user-attachments/assets/f37ec6c9-fda3-41d1-8933-940c2806c6b0" alt="Page management" width="200"/>
  <img src="https://github.com/user-attachments/assets/e8304495-dbab-4d7a-987a-b76bf91a3a74" alt="PDF viewing" width="200"/>
  <img src="https://github.com/user-attachments/assets/38226966-0e19-45c9-a318-a8fd9d8edf02" alt="Customization" width="200"/>
  <img src="https://github.com/user-attachments/assets/df29f77c-94a8-4c56-bbd4-d7285654df30" alt="Settings" width="200"/>
</div>

---

## Working with LaTeX

The app can be used as a **primitive second monitor** for LaTeX editing — previewing compiled PDFs
in real time on your tablet.

### Steps:

- Connect your device to your computer via USB (MTP).
- Set up automatic copying of the compiled PDF to the tablet:
  <details>
  <summary>Example using a custom <code>latexmkrc</code>:</summary>

  ```perl
  $pdf_mode = 1;
  $out_dir = 'build';

  sub postprocess {
      system("cp build/main.pdf '/run/user/1000/gvfs/mtp:host=DEVICE/Internal shared storage/Documents/Filename.pdf'");
  }

  END {
      postprocess();
  }
  ```
  
  It was also tested with `adb push`, instead of `cp`.

  </details>
- Compile, and test if it copies the file to the tablet.
- Import your compiled PDF document into Notable, and choose to observe the PDF file.

> After each recompilation, Notable will detect the updated PDF and automatically refresh the view.

---

## App Distribution
Notable is not distributed on Google Play or F-Droid. Official builds are provided exclusively via [GitHub Releases](https://github.com/Ethran/notable/releases).

---

## For Developers & Contributing

- Project file layout: see [docs/file-structure.md](./docs/file-structure.md)  
- Data model and stroke encoding: see [docs/database-structure.md](./docs/database-structure.md)  
- Additional documentation will be added as needed  
  Note: These documents were AI-generated and lightly verified; refer to the code for the authoritative source.

### Development Notes

- Edit the `DEBUG_STORE_FILE` in `/app/gradle.properties` to point to your local keystore file. This is typically located in the `.android` directory.
- To debug on a BOOX device, enable developer mode. You can follow [this guide](https://imgur.com/a/i1kb2UQ).

Feel free to open issues or submit pull requests. I appreciate your help!

---

<!-- MARKDOWN LINKS -->
[logo]: https://github.com/Ethran/notable/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true "Notable Logo"
[contributors-shield]: https://img.shields.io/github/contributors/Ethran/notable.svg?style=for-the-badge
[contributors-url]: https://github.com/Ethran/notable/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/Ethran/notable.svg?style=for-the-badge
[forks-url]: https://github.com/Ethran/notable/network/members
[stars-shield]: https://img.shields.io/github/stars/Ethran/notable.svg?style=for-the-badge
[stars-url]: https://github.com/Ethran/notable/stargazers
[issues-shield]: https://img.shields.io/github/issues/Ethran/notable.svg?style=for-the-badge
[issues-url]: https://github.com/Ethran/notable/issues
[license-shield]: https://img.shields.io/github/license/Ethran/notable.svg?style=for-the-badge

[license-url]: https://github.com/Ethran/notable/blob/main/LICENSE
[download-shield]: https://img.shields.io/github/v/release/Ethran/notable?style=for-the-badge&label=⬇️%20Download
[download-url]: https://github.com/Ethran/notable/releases/latest
[downloads-shield]: https://img.shields.io/github/downloads/Ethran/notable/total?style=for-the-badge&color=47c219&logo=cloud-download
[downloads-url]: https://github.com/Ethran/notable/releases/latest

[discord-shield]: https://img.shields.io/badge/Discord-Join%20Chat-7289DA?style=for-the-badge&logo=discord
[discord-url]: https://discord.gg/rvNHgaDmN2
[kofi-shield]: https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ko--fi-ff5f5f?style=for-the-badge&logo=ko-fi&logoColor=white
[kofi-url]: https://ko-fi.com/rethran

[sponsor-shield]: https://img.shields.io/badge/Sponsor-GitHub-%23ea4aaa?style=for-the-badge&logo=githubsponsors&logoColor=white
[sponsor-url]: https://github.com/sponsors/rethran

[docs-url]: https://github.com/Ethran/notable
[bug-url]: https://github.com/Ethran/notable/issues/new?template=bug_report.md
[feature-url]: https://github.com/Ethran/notable/issues/new?labels=enhancement&template=feature-request---.md
[bug-shield]: https://img.shields.io/badge/🐛%20Report%20Bug-red?style=for-the-badge
[feature-shield]: https://img.shields.io/badge/💡%20Request%20Feature-blueviolet?style=for-the-badge
