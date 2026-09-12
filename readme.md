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
* **Double tap:** undo.
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
