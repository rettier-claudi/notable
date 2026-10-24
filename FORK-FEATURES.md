# Was der Fork behalten muss

Kurzfassung der Fork-Features für Upstream-Merges. Einzelheiten und Begründungen stehen im README unter
„This fork“. Nach jedem Merge diese Liste durchgehen. ⚠ = beim Merge von upstream v0.2.7 (claudi.33)
gab es hier Konflikte; nach dem nächsten Merge zuerst diese Stellen ansehen.

## 1. Vertrag mit der Tablet-Brücke — darf nie brechen

Die Brücke auf mike verlässt sich darauf. Bricht etwas davon, gehen Seiten verloren oder werden doppelt gelesen.

- **`kind: "scratch"` im Manifest** wird gelesen, gespeichert (Room 38) und bei jedem Upload wörtlich zurückgeschrieben, auch unbekannte Werte (claudi.9).
- **Sync-Umfang:** Wurzel („Workspace“) und der Wurzelordner mit Titel `Today` (Alias `Heute`) samt Unterordnern, nicht alle Bücher (claudi.7/8).
- **Ordner-Grabsteine** `deletions/folder-<id>`; Umbenennen und Verschieben stempeln `updatedAt` (claudi.7).
- **Senden** = Sync + Webhook mit `pageId`/`notebookId`/`syncSucceeded`, danach zurück in die Bibliothek; fragt vorher „Really send?“ (claudi.6, .17).
- **Gesendete Schnellseite ist für immer gesperrt**, gesendetes Notizbuch nur markiert (claudi.6). ⚠ `PageDataManager.isPageLocked`
- **Schnellseiten** werden gesynct, nie auf Verdacht gelöscht, gesendete sind von der Löschbremse ausgenommen; eine Schnellseite mit Seite 2 wird ein Notizbuch `kind=scratch` mit derselben Id (claudi.24, `MOVED_QUICK_PAGES`).
- **Leere neue Seite** synct nie und verschwindet beim Schließen (claudi.16).
- **Fehlgeschlagener erster Download** zählt nicht als gesynct, sonst würde das Buch per Grabstein gelöscht (claudi.29).

## 2. Stift und Radieren

- **Kritzeln radiert**: eigene Geometrie (`ScribbleGeometry`, `ScribbleArea`), Stift wird live rot, wenn das Kritzeln radieren wird, und Rot heißt sicher radieren (claudi.13–15, .26, .30–.32). Tests: `ScribbleRealPagesTest`. ⚠ `OnyxInputHandler`
- **Stift-Ebene hängt nie**: nicht nach Senden (Startbildschirm), nicht nach Ausschneiden/Einfügen, nicht nach dem Radieren (claudi.13, .25, .28; `PenLayerRules.kt`, `enablePenLayer`, `resumeAfterErase`). ⚠ Upstream hat dieselben Fehler auf eigene Art behoben (`enableRawDrawing`); in claudi.33 blieb unsere Logik, dazu kamen upstreams Bündeln der Oberflächen-Einrichtung und `updateEraserTrack`. Achtung: `setupSurface` lässt die Ebene seit 0.2.7 **aus** — wer sie danach braucht, muss `enablePenLayer` rufen.
- **Handballen** ist kein zweiter Finger; **Doppeltipp** nur im oberen linken 2/3-Bereich (claudi.13, .30).
- **Öffnen beginnt mit dem ersten Stift** (claudi.19).
- **Leuchtstift** liegt immer unter der Schrift, lässt Schwarz durch, hat einen eigenen Radierer (claudi.22, .23).

## 3. Bedienung

- **Blätter-Pfeile** in der Werkzeugleiste (claudi.20), **Licht an/aus** als Knopf, Menü ☰ ausblendbar (claudi.21). ⚠ `AppSettings`, `ToolbarElementView` (upstream: UI-Skalierung)
- **Verlassen → zurück in den Ordner**, aus dem die Seite kam; **Zoom abschaltbar** (claudi.18).
- **Seitenübersicht**: „Neue Seite nach der aktuellen“, Go-home-Geste = zurück zum Dokument (claudi.27).
- **Gesten**: Zwei-Finger-Wischen, „Zurück zum Start“ und „Senden“ als Gesten-Aktionen, Finger-Halten abschaltbar, Tasten/Seitenwischen blättern, feste Seiten ohne Scrollen.
- **Bezeichnungen**: „Scratch notes“, „Workspace“, „Today“; Ordnerleiste statt Brotkrumen-Pfad (claudi.7/8).

## 4. Sync-Robustheit

- **Passwort** wird nie gelöscht, einmal pro Prozess entschlüsselt, nie mit neuem Schlüssel; Diagnose-Log auf dem Server (claudi.10, .12). ⚠ `Kv`, `SettingsViewModel`, `SyncOrchestrator` — seit claudi.33 upstreams einziger Schreibweg `updateSyncSettings` plus unser `passwordMemo` und die Diagnose.
- **WakeLock + WLAN-Lock** während jeder Runde, die Runde beim Verlassen läuft sofort im Prozess (claudi.8).
- **Sync-Auslöser**: Verlassen der App, Rückkehr in den Vordergrund, nach Aktivität statt Takt; kurze Timeouts, Abbrechen per Knopf (claudi.13).
- **Weniger Anfragen je Runde**, kein Snack bei Erfolg, Sync-Anzeige und -Knopf am Startbildschirm.
- **Vorschaubilder** folgen der Seite (claudi.11). ⚠ `PageDataManager`
- **Akku**: kein Dauer-Polling nach dem Speichern (claudi.13).

## Merge-Hinweise

- `versionCode` immer unseren (höheren) behalten und hochzählen; `versionName` auf upstream ziehen, `-claudi.N` dahinter.
- Upstream hat bis v0.2.7 keine Room-Migration — sobald eine kommt, kollidiert sie mit unserer 38.
- Nach jedem Merge: alle Unit-Tests (`./gradlew testDebugUnitTest`), dann am Gerät: Kritzeln radiert und wird rot, Senden → Startbildschirm, Stift zeichnet dort nicht, Einfügen platzieren, Schnellseite senden, Blätter-Pfeile.
