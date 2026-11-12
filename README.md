# Issue Tool Window – GitHub Issues für IntelliJ IDEA

Issues aus GitHub direkt in IntelliJ IDEA lesen, filtern, bearbeiten und daraus Branches erstellen – ohne Browser.
Das Tool Window **GitHub Issues** zeigt die Issues der GitHub.com-Remotes des geöffneten Projekts und verwendet das
GitHub-Konto, das in IDEA bereits für **Pull Requests** eingerichtet ist.

## Features

- **Issue-Liste** für alle GitHub.com-Remotes des Projekts, mit Auswahl von Repository und Konto
- **Filter** nach Status (Open, Closed, All), Label und Assignee – inklusive **Assigned to me**
- **Volltextsuche** in Titel und Beschreibung; `#42` öffnet Issue 42 direkt
- **Issue-Details** mit Kommentaren in einem Editor-Tab, der sich alle 30 Sekunden im Hintergrund aktualisiert
- **Issues anlegen und bearbeiten**: Titel und Markdown-Beschreibung, Labels, Assignees
- **Kommentieren, schließen und wieder öffnen**
- **Branch aus Issue erstellen**: Branch-Name aus Nummer und Titel, direkt ausgecheckt
- **Issue des aktuellen Branches in der Statusleiste**: `#42` und gekürzter Titel, ein Klick öffnet das Issue
- Keine eigene Anmeldung nötig – das GitHub-Konto aus IDEA wird wiederverwendet

## Installation

Unterstützt wird IntelliJ IDEA **2026.3 und neuer** (ab Build `263`). Geprüft wird jeweils gegen 2026.3 und die neueste
veröffentlichte IDEA-Version.

**Über den Marketplace:** In IDEA unter **Settings → Plugins → Marketplace** nach **Issue Tool Window** suchen und
installieren.

**Manuell:** Unter [GitHub Releases](https://github.com/ljunker/issue-tool-window/releases) die ZIP aus den
**Assets** herunterladen und unter **Settings → Plugins → ⚙ → Install Plugin from Disk…** auswählen (nicht entpacken).

Bei Bedarf IDEA anschließend neu starten.

## Anwendung

### Erste Schritte

1. Ein Git-Projekt mit einem GitHub.com-Remote öffnen.
2. Unter **View → Tool Windows → GitHub Issues** das Fenster öffnen.
3. Bei mehreren Repositories oder Konten die passende Auswahl treffen. **Sign In to GitHub** öffnet bei Bedarf den
   Anmeldedialog von IDEA.

### Issues finden

- Mit **Open**, **Closed** und **All** nach Status filtern. Geschlossene Issues zeigen ihren Grund: `CLOSED` für
  erledigte, `NOT PLANNED` für bewusst nicht umgesetzte und `DUPLICATE` für Duplikate.
- Die Chips **Label** und **Assignee** schränken die Liste auf ein Label des Repositorys oder eine zugewiesene Person
  ein; **Assigned to me** steht für das aktive GitHub-Konto. Jeder Chip lässt sich über sein ✕ einzeln zurücksetzen.
- Über **Search** oder Enter in Titel und Beschreibung suchen. Status-, Label- und Zuständigkeitsfilter gelten
  weiter. **Load More** lädt weitere Treffer. Das Suchfeld leeren, um zur normalen Liste zurückzukehren.
- `#42` und Enter öffnet Issue 42 direkt – auch wenn es geschlossen ist oder noch nicht in der Liste steht.
- Repository, Konto, Status, Label, Assignee und Sortierung bleiben pro Projekt erhalten, auch nach einem Neustart.
  Gibt es ein gespeichertes Label oder eine Person im Repository nicht mehr, entfällt dieser Filter. Der Suchtext wird
  nicht gespeichert.

### Issues ansehen und bearbeiten

Ein Issue in der Liste auswählen, um Details und Kommentare im Editor-Tab **GitHub Issue** zu öffnen. Solange der Tab
sichtbar ist, lädt er das Issue alle 30 Sekunden im Hintergrund nach; auf GitHub ergänzte Kommentare und Änderungen
erscheinen so ohne erneutes Öffnen. Der Refresh-Button am Ende der Aktionszeile im Tab lädt das Issue sofort neu, auch
wenn das Tool Window geschlossen ist.

| Aktion                                     | Wirkung                                                                                                                                 |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| **New Issue**                              | Öffnet das Formular für ein neues Issue.                                                                                                |
| **Edit Issue**                             | Bearbeitet Titel und Markdown-Quelltext der Beschreibung.                                                                               |
| **Edit Labels**                            | Zeigt alle Labels des Repositorys mit Filterfeld. **Save Labels** fügt nur angehakte Labels hinzu und entfernt nur abgewählte.          |
| **Edit Assignees**                         | Zeigt alle zuweisbaren Personen mit Filterfeld. **Save Assignees** ändert jede Person einzeln, parallele Änderungen auf GitHub bleiben erhalten. |
| **Assign Me**                              | Weist das aktive Konto direkt zu, sofern es zuweisbar ist.                                                                              |
| **Post Comment**                           | Fügt einen Kommentar hinzu.                                                                                                             |
| **Edit** / **Delete** am Kommentar         | Erscheint nur, wenn GitHub dem aktiven Konto das Bearbeiten bzw. Löschen erlaubt. **Edit** öffnet den Markdown-Quelltext, **Delete** fragt vorher nach. |
| **Close Issue** / **Reopen Issue**         | Schließt das Issue als erledigt bzw. öffnet es wieder. Das Ausklappmenü bietet **Close as Completed** und **Close as Not Planned**.      |

Nach dem Speichern von Assignees zeigt der Tab die von GitHub gespeicherten Zuständigkeiten und nennt abgelehnte oder
ignorierte Änderungen.

### Branch aus einem Issue erstellen

**Create Branch…** schlägt einen editierbaren Branch-Namen aus Issue-Nummer und Titel vor, etwa
`9-lokalen-git-branch-aus-einem-issue-erstellen`. Passen mehrere lokale Git-Roots zum Repository, wird der Root im
Dialog gewählt. **Create and Checkout** erstellt den Branch vom aktuellen HEAD und wechselt darauf.

- Lokale Änderungen bleiben erhalten, gepusht wird nichts.
- Ungültige oder bereits vergebene Namen sowie ein laufender Merge, Rebase, Cherry-Pick oder Revert werden direkt im
  Dialog gemeldet.

### Issue des aktuellen Branches

Beginnt der Name des aktuellen Branches mit einer Issue-Nummer, etwa `42-fix-parser` oder `feature/42-fix-parser`, zeigt
die Statusleiste `#42` und den gekürzten Titel. Ein Klick öffnet das Issue im Tab **GitHub Issue** und stellt die
Issue-Liste bei Bedarf auf das Repository des Branches um.

- Bei mehreren Git-Roots gilt der Root der gerade geöffneten Datei, wie beim Git-Branch-Widget.
- Ohne Issue-Nummer im Branch-Namen oder wenn das Repository kein Issue mit dieser Nummer hat, bleibt die Anzeige leer.
- Ohne GitHub-Konto oder Verbindung zeigt die Statusleiste nur die Nummer.
- Ausblenden lässt sich die Anzeige über das Kontextmenü der Statusleiste (**GitHub Issue of Current Branch**).

## Entwicklung

Build, Tests, Sandbox-Abnahme und Release-Prozess sind in [DEVELOPMENT.md](DEVELOPMENT.md) beschrieben.

## Lizenz

[GNU General Public License v3.0](LICENSE)
