# Watch Audio Recorder

Sofort-Audioaufnahme für die Galaxy Watch 6 per **Doppelklick auf die Hometaste** – ohne weiteren Tap. Die Aufnahme läuft im Hintergrund, unten am Watchface zeigt ein kleines Mikrofon-Icon mit Timer den Status an. Ein zweiter Doppelklick stoppt die Aufnahme und überträgt sie automatisch aufs Handy nach `Downloads/WatchRecordings`.

## Bestandteile

| Modul | Gerät | Aufgabe |
|---|---|---|
| `wear/` | Galaxy Watch 6 | Aufnahme (Start/Stopp per App-Start), Timer-Icon, Übertragung |
| `mobile/` | Handy | Empfang im Hintergrund, Speichern in `Downloads/WatchRecordings`, Benachrichtigung |

## APKs bekommen

Bei jedem Push baut GitHub Actions beide Debug-APKs: Im Reiter **Actions → Build APKs → (neuester Lauf) → Artifacts** liegen `watch-app` und `phone-app` zum Download.

> **Wichtig:** Immer beide APKs aus **demselben** Actions-Lauf installieren. Die Data-Layer-Übertragung funktioniert nur, wenn Watch- und Handy-App mit demselben Zertifikat signiert sind – und der Debug-Keystore wird pro CI-Lauf neu erzeugt.

Alternativ lokal mit Android Studio / Android SDK: `./gradlew :wear:assembleDebug :mobile:assembleDebug`

## Installation

### Handy
1. `mobile-debug.apk` aufs Handy kopieren und installieren („Unbekannte Quellen" erlauben).
2. App einmal öffnen und die Benachrichtigungs-Berechtigung bestätigen.

### Watch (Sideload per ADB)
1. Auf der Watch: *Einstellungen → Info zur Uhr → Softwareinformationen* → 5× auf **Softwareversion** tippen → Entwicklermodus aktiv.
2. *Einstellungen → Entwickleroptionen → ADB-Debugging* **und** *Debugging über WLAN* aktivieren. Die Watch zeigt eine IP-Adresse an (Watch und PC müssen im selben WLAN sein).
3. Am PC (mit [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)):
   ```
   adb connect <WATCH-IP>:5555
   adb install wear-debug.apk
   ```
   Die Verbindungsanfrage auf der Watch bestätigen.

### Doppelklick der Hometaste zuweisen
Auf der Watch: *Einstellungen → Erweiterte Funktionen → Tasten anpassen → Zweimal drücken* → **Audio Recorder** auswählen.

## Benutzung

1. **Doppelklick** auf die Hometaste → Aufnahme startet sofort. (Nur beim allerersten Mal muss die Mikrofon-Berechtigung bestätigt werden.)
2. Arm senken, Watch normal benutzen – die Aufnahme läuft im Hintergrund weiter. Unten am Watchface zeigt das Mikrofon-Icon mit laufendem Timer den Status.
3. **Doppelklick** erneut (oder Stop-Button in der App) → Aufnahme stoppt und wird ans Handy übertragen. Auf dem Handy erscheint eine Benachrichtigung; die Datei liegt unter `Downloads/WatchRecordings/aufnahme_<Datum>_<Uhrzeit>.m4a`.

Ist das Handy gerade nicht erreichbar, bleibt die Aufnahme auf der Watch gespeichert und wird beim nächsten Start/Stopp automatisch nachgesendet.

## Technik-Checkliste für den ersten Test

- [ ] Doppelklick → App öffnet sich, Mikrofon-Berechtigung bestätigen (nur 1. Mal)
- [ ] Doppelklick → Timer läuft auf dem Screen, nach Arm senken erscheint das Icon unten am Watchface
- [ ] Doppelklick erneut → „Übertrage ans Handy…" → „An Handy gesendet ✓"
- [ ] Handy: Benachrichtigung „Aufnahme von der Watch empfangen", Datei in `Downloads/WatchRecordings` abspielbar
- [ ] Test mit Handy in Flugmodus: Watch meldet „wird nachgeholt", nächste Übertragung sendet beide Dateien

## Aufnahmeformat

AAC (`.m4a`), 44,1 kHz, mono, 96 kbps – gute Sprachqualität bei ~43 MB pro Stunde.
