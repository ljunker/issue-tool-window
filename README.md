# Issue Tool Window – GitHub Issues für IntelliJ IDEA

Issues aus GitHub direkt in IntelliJ IDEA lesen, filtern, bearbeiten und daraus Branches erstellen – ohne Browser.
Das Tool Window **GitHub Issues** zeigt die Issues der GitHub.com-Remotes des geöffneten Projekts und verwendet das
GitHub-Konto, das in IDEA bereits für **Pull Requests** eingerichtet ist.

## Features

- **Issue-Liste** für alle GitHub.com-Remotes des Projekts, mit Auswahl von Repository und Konto
- **Filter** nach Status (Open, Closed, All), Label, Assignee und Milestone – inklusive **Assigned to me** und
  **No milestone**
- **Volltextsuche** in Titel und Beschreibung; `#42` öffnet Issue 42 direkt
- **Issue-Details** mit Kommentaren in einem Editor-Tab, der sich alle 30 Sekunden im Hintergrund aktualisiert
- **Issues anlegen und bearbeiten**: Titel und Markdown-Beschreibung, Labels, Assignees, Milestone
- **Issue-Vorlagen beim Anlegen**: Markdown-Vorlagen aus `.github/ISSUE_TEMPLATE/` füllen das neue Issue vor
- **Kommentieren, schließen und wieder öffnen**
- **Branch aus Issue erstellen**: Branch-Name aus Nummer und Titel, direkt ausgecheckt
- **Issue des aktuellen Branches in der Statusleiste**: `#42` und gekürzter Titel, ein Klick öffnet das Issue
- **Issues über Search Everywhere finden**: `#42` oder Text aus Titel und Beschreibung, ohne das Tool Window zu öffnen
- **Issue-Referenzen öffnen**: `#42` in Code-Kommentaren per Strg/Cmd-Klick, in Commit-Messages über das Git-Log
- Keine eigene Anmeldung nötig – das GitHub-Konto aus IDEA wird wiederverwendet
- **Oberfläche auf Deutsch oder Englisch**: Deutsch, wenn IDEA auf Deutsch läuft (Sprachpaket), sonst Englisch.
  Die README nennt die englischen Bezeichnungen.

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
  ein; **Assigned to me** steht für das aktive GitHub-Konto. Der Chip **Milestone** bietet alle Milestones des
  Repositorys an, offene zuerst, abgeschlossene mit dem Zusatz „(closed)“, sowie **No milestone** für Issues ohne
  Milestone. Jeder Chip lässt sich über sein ✕ einzeln zurücksetzen.
- Liste und Detail-Tab zeigen den Milestone eines Issues.
- Über **Search** oder Enter in Titel und Beschreibung suchen. Status-, Label-, Zuständigkeits- und Milestone-Filter gelten
  weiter. **Load More** lädt weitere Treffer. Das Suchfeld leeren, um zur normalen Liste zurückzukehren.
- `#42` und Enter öffnet Issue 42 direkt – auch wenn es geschlossen ist oder noch nicht in der Liste steht.
- Repository, Konto, Status, Label, Assignee, Milestone und Sortierung bleiben pro Projekt erhalten, auch nach einem
  Neustart. Gibt es ein gespeichertes Label, eine Person oder einen Milestone im Repository nicht mehr, entfällt dieser
  Filter. Der Suchtext wird
  nicht gespeichert.

### Issues ansehen und bearbeiten

Ein Issue in der Liste auswählen, um Details und Kommentare im Editor-Tab **GitHub Issue** zu öffnen. Solange der Tab
sichtbar ist, lädt er das Issue alle 30 Sekunden im Hintergrund nach; auf GitHub ergänzte Kommentare und Änderungen
erscheinen so ohne erneutes Öffnen. Der Refresh-Button am Ende der Aktionszeile im Tab lädt das Issue sofort neu, auch
wenn das Tool Window geschlossen ist.

| Aktion                                     | Wirkung                                                                                                                                 |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| **New Issue**                              | Öffnet das Formular für ein neues Issue mit Titel, Beschreibung, Labels und Assignees. Hat das Repository Vorlagen, wird vorher gewählt. |
| **Edit Issue**                             | Bearbeitet Titel und Markdown-Quelltext der Beschreibung.                                                                               |
| **Edit Labels**                            | Zeigt alle Labels des Repositorys mit Filterfeld. **Save Labels** fügt nur angehakte Labels hinzu und entfernt nur abgewählte.          |
| **Edit Assignees**                         | Zeigt alle zuweisbaren Personen mit Filterfeld. **Save Assignees** ändert jede Person einzeln, parallele Änderungen auf GitHub bleiben erhalten. |
| **Milestone…**                             | Bietet die offenen Milestones des Repositorys und **Remove milestone** an. Die Auswahl wird sofort gespeichert.                         |
| **Assign Me**                              | Weist das aktive Konto direkt zu, sofern es zuweisbar ist.                                                                              |
| **Post Comment**                           | Fügt einen Kommentar hinzu.                                                                                                             |
| **Edit** / **Delete** am Kommentar         | Erscheint nur, wenn GitHub dem aktiven Konto das Bearbeiten bzw. Löschen erlaubt. **Edit** öffnet den Markdown-Quelltext, **Delete** fragt vorher nach. |
| **Close Issue** / **Reopen Issue**         | Schließt das Issue als erledigt bzw. öffnet es wieder. Das Ausklappmenü bietet **Close as Completed** und **Close as Not Planned**.      |

Hat das Repository Markdown-Vorlagen in `.github/ISSUE_TEMPLATE/` auf dem Default-Branch, bietet **New Issue** sie
zusammen mit **Blank issue** an. Eine Vorlage füllt Titel, Beschreibung, Labels und Assignees aus ihrem Frontmatter
vor; alle Felder bleiben editierbar. Labels und Assignees werden kommagetrennt eingegeben. YAML-Issue-Forms und
`config.yml` werden nicht angeboten. Ohne Vorlagen oder wenn sie nicht geladen werden können, öffnet **New Issue**
direkt das leere Formular. Vorlagen werden einmal pro Verbindung geladen; **Refresh** lädt sie neu. Ohne
Schreibrechte im Repository übernimmt GitHub Labels und Assignees eines neuen Issues nicht.

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

### Search Everywhere

**Search Everywhere** (zweimal Shift) findet Issues im Tab **GitHub Issues** und unter **All**. `#42` findet Issue 42,
offen oder geschlossen. Freier Text ab drei Zeichen sucht in Titel und Beschreibung; offene Issues stehen vor
geschlossenen. Die Auswahl eines Treffers öffnet das Issue im Tab **GitHub Issue**.

- Durchsucht wird das Repository, das das Tool Window zeigt oder zuletzt gezeigt hat, sonst das Repository des
  `origin`-Remotes. Verwendet wird dasselbe GitHub-Konto wie in der Statusleiste.
- Ohne GitHub-Remote, Konto oder Verbindung erscheinen keine Treffer und keine Fehlermeldungen.

### Issue-Referenzen in Code und Commits

`#42` in Code-Kommentaren erscheint als Link. Strg/Cmd-Klick oder **Go to Declaration** öffnet Issue 42 im Tab
**GitHub Issue**. Im Git-Log öffnet **Open GitHub Issue** im Kontextmenü eines Commits das in der Commit-Message
genannte Issue; nennt die Message mehrere Issues, folgt ein Untermenü.

- Es gilt das GitHub.com-Repository des Git-Roots der Datei bzw. des Commits: das Repository, das das Tool Window zeigt,
  sonst das des `origin`-Remotes.
- Ohne GitHub.com-Remote gibt es weder Links noch Menüeintrag.
- Nicht als Referenz gelten Nummern, die an ein Wort grenzen (`a#1`, `#1a`), und HTML-Zeichenreferenzen (`&#123;`).
- Links direkt in der Commit-Message im Git-Log stammen weiterhin aus **Settings → Version Control → Issue Navigation**
  und öffnen den Browser. Das Plugin ändert diese Einstellungen nicht.

## Entwicklung

Build, Tests, Sandbox-Abnahme und Release-Prozess sind in [DEVELOPMENT.md](DEVELOPMENT.md) beschrieben.

## Lizenz

[GNU General Public License v3.0](LICENSE)
