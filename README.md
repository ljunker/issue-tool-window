# Issue Tool Window – GitHub Issues für IntelliJ IDEA

Das Tool Window **GitHub Issues** zeigt Issues aus den GitHub.com-Remotes des geöffneten Projekts. Es verwendet das
GitHub-Konto aus IDEA, das auch im Fenster **Pull Requests** zur Verfügung steht.

## Installation und Nutzung

Unterstützt wird IntelliJ IDEA **2026.3** (Build-Reihe `263.*`).

1. In IDEA unter **Settings → Plugins → Marketplace** nach **Issue Tool Window** suchen und installieren. Alternativ unter
   [GitHub Releases](https://github.com/ljunker/issue-tool-window/releases) die ZIP aus den **Assets** herunterladen
   und unter **Settings → Plugins → ⚙ → Install Plugin from Disk…** auswählen (nicht entpacken).
2. Bei Bedarf IDEA neu starten.
3. Ein Git-Projekt mit einem GitHub.com-Remote öffnen. Unter **View → Tool Windows → GitHub Issues** das Fenster öffnen.
4. Bei mehreren Repositories oder Konten die passende Auswahl treffen. **Sign In to GitHub** öffnet bei Bedarf den
   Anmeldedialog von IDEA.
5. Mit **Open**, **Closed** und **All** filtern. Die Chips **Label** und **Assignee** schränken die Liste auf ein Label
   des Repositorys oder eine zugewiesene Person ein; **Assigned to me** steht für das aktive GitHub-Konto. Jeder Chip
   lässt sich über sein ✕ einzeln zurücksetzen. Über **Search** oder Enter in Titel und Beschreibung suchen; Status-,
   Label- und Zuständigkeitsfilter gelten weiter. **Load More** lädt weitere Treffer. Das Suchfeld leeren, um zur normalen Liste
   zurückzukehren.
   Mit `#42` und Enter das Issue 42 direkt öffnen, auch wenn es geschlossen ist oder noch nicht in der Liste steht.
6. Ein Issue in der linken Liste auswählen, um Details und Kommentare im Editor-Tab **GitHub Issue** zu öffnen.
   **New Issue** öffnet dort das Formular für ein neues Issue. **Edit Issue** öffnet den Titel und den
   Markdown-Quelltext der Beschreibung zur Bearbeitung. **Edit Labels** zeigt alle Labels des Repositorys mit
   Filterfeld; **Save Labels** fügt nur die angehakten Labels hinzu und entfernt nur die abgewählten. **Edit Assignees**
   zeigt alle zuweisbaren Personen des Repositorys mit Filterfeld; **Save Assignees** ändert jede Person einzeln, sodass
   auf GitHub parallel geänderte Zuständigkeiten erhalten bleiben. **Assign Me** weist das aktive Konto direkt zu, wenn
   es zuweisbar ist. Danach zeigt der Tab die von GitHub gespeicherten Zuständigkeiten und nennt abgelehnte oder
   ignorierte Änderungen. Im Tab stehen
   auch **Post Comment**, **Close Issue** und **Reopen Issue** zur Verfügung.
7. **Create Branch…** schlägt einen editierbaren Branch-Namen aus Issue-Nummer und Titel vor, etwa
   `9-lokalen-git-branch-aus-einem-issue-erstellen`. Passen mehrere lokale Git-Roots zum Repository, wird der Root im
   Dialog gewählt. **Create and Checkout** erstellt den Branch vom aktuellen HEAD und wechselt darauf. Lokale Änderungen
   bleiben erhalten, gepusht wird nichts. Ungültige oder bereits vergebene Namen sowie ein laufender Merge, Rebase,
   Cherry-Pick oder Revert werden direkt im Dialog gemeldet.

## Build und Abnahme

JDK 25 als `JAVA_HOME` setzen. Der Wrapper lädt die passende IDEA-Version automatisch. Mit
`-PideaLocalPath="/Pfad/zu/IntelliJ IDEA.app"` lässt sich stattdessen eine vorhandene Installation von IDEA 2026.3
nutzen.

```sh
./gradlew test buildPlugin verifyPlugin
./gradlew runIde --args="/Pfad/zum/Testprojekt"
```

Lokal gebaute Plugin-ZIPs liegen unter `build/distributions/`. Bei Pull Requests führt GitHub Actions mit JDK 25
`test`, `buildPlugin` und `verifyPlugin` aus.

### Sandbox-Abnahme vor einem Release

1. Mit `runIde` ein privates GitHub-Testrepository mit aktivierten Issues öffnen. Im Pull-Request-Fenster mit dem
   GitHub-Konto von IDEA anmelden.
2. In **GitHub Issues** ohne weitere Anmeldung ein Issue anlegen, kommentieren, schließen und wieder öffnen. Nach
   jedem Schritt den Titel, Kommentar oder Status direkt auf GitHub prüfen.
3. Das Konto in der Sandbox entfernen. **GitHub Issues** muss zur Anmeldung auffordern.
4. Ein Konto mit Leserechten ohne Schreibrechte verwenden. Issues müssen sichtbar sein; beim Schließen eines von
   einem anderen Konto erstellten Issues muss GitHub den Zugriff verweigern. Den unveränderten Status auf GitHub prüfen.
5. Ein lokales Git-Projekt ohne GitHub-Remote öffnen. Das Tool Window muss melden, dass kein GitHub-Remote vorhanden ist.

### Release und Versionierung

Releases entstehen automatisch beim Push auf `main` (Workflow `.github/workflows/release.yml`). Die Version leitet
`.github/scripts/next-version.sh` aus den [Conventional Commits](https://www.conventionalcommits.org/) seit dem
letzten Tag `vX.Y.Z` ab:

| Commits seit dem letzten Release                | Versionssprung |
|-------------------------------------------------|----------------|
| `BREAKING CHANGE:` im Body oder `typ!:`         | Major          |
| `feat:`                                         | Minor          |
| `fix:` oder `perf:`                             | Patch          |
| nur andere Typen (`docs:`, `chore:`, `ci:`, …)  | kein Release   |

Bei einem Release testet, verifiziert und signiert der Workflow das Plugin, veröffentlicht es im JetBrains
Marketplace und legt Tag und GitHub-Release mit ZIP und Release-Notes an. Die Änderungsliste erscheint auch als
Change-Notes im Marketplace. Über **Actions → Release → Run workflow** lässt sich ein Release mit festem
Versionssprung erzwingen.

Benötigte Repository-Secrets:

| Secret                   | Zweck                                                                    |
|--------------------------|--------------------------------------------------------------------------|
| `PUBLISH_TOKEN`          | Marketplace-Token ([Profil → Tokens](https://plugins.jetbrains.com/author/me/tokens)); ohne Token kein Marketplace-Upload |
| `CERTIFICATE_CHAIN`      | Zertifikatskette (PEM) für die [Plugin-Signatur](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) |
| `PRIVATE_KEY`            | Privater Schlüssel (PEM) zur Zertifikatskette                            |
| `PRIVATE_KEY_PASSWORD`   | Passwort des privaten Schlüssels                                         |

Ohne Signatur-Secrets wird das Plugin unsigniert veröffentlicht. Die **erste** Version muss einmalig manuell auf
[plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/add) hochgeladen werden; erst danach kann der Workflow
neue Versionen per Token veröffentlichen. Dafür `PUBLISH_TOKEN` zunächst weglassen, die ZIP des ersten automatisch
erstellten GitHub-Release manuell hochladen und das Secret erst danach anlegen.

## Lizenz

[GNU General Public License v3.0](LICENSE)
