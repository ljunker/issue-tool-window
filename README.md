# GitHub Issues für IntelliJ IDEA

Das Tool Window **GitHub Issues** zeigt Issues aus den GitHub.com-Remotes des geöffneten Projekts. Es verwendet das
GitHub-Konto aus IDEA, das auch im Fenster **Pull Requests** zur Verfügung steht.

## Installation und Nutzung

Unterstützt wird IntelliJ IDEA **2026.3** (Build-Reihe `263.*`).

1. Unter [GitHub Releases](https://github.com/ljunker/issue-tool-window/releases) das gewünschte Beta-Prerelease öffnen.
   Unter **Assets** `issue-tool-window-<Version>.zip` herunterladen und nicht entpacken.
2. In IDEA unter **Settings → Plugins → ⚙ → Install Plugin from Disk…** die ZIP auswählen. Bei Bedarf IDEA neu starten.
3. Ein Git-Projekt mit einem GitHub.com-Remote öffnen. Unter **View → Tool Windows → GitHub Issues** das Fenster öffnen.
4. Bei mehreren Repositories oder Konten die passende Auswahl treffen. **Sign In to GitHub** öffnet bei Bedarf den
   Anmeldedialog von IDEA.
5. Mit **Open**, **Closed** und **All** filtern. Über **Search** oder Enter in Titel und Beschreibung suchen; der
   Statusfilter gilt weiter. **Load More** lädt weitere Treffer. Das Suchfeld leeren, um zur normalen Liste
   zurückzukehren.
   Mit `#42` und Enter das Issue 42 direkt öffnen, auch wenn es geschlossen ist oder noch nicht in der Liste steht.
6. Ein Issue auswählen, um Details und Kommentare zu sehen. **New Issue**
   legt ein Issue an. **Edit Issue** öffnet den Titel und den Markdown-Quelltext der Beschreibung zur Bearbeitung.
   In den Details stehen auch **Post Comment**, **Close Issue** und **Reopen Issue** zur Verfügung.

## Build und Abnahme

JDK 25 als `JAVA_HOME` setzen. Der Wrapper lädt die passende IDEA-Version automatisch. Mit
`-PideaLocalPath="/Pfad/zu/IntelliJ IDEA.app"` lässt sich stattdessen eine vorhandene Installation von IDEA 2026.3
nutzen.

```sh
./gradlew test buildPlugin verifyPlugin
./gradlew runIde --args="/Pfad/zum/Testprojekt"
```

Lokal gebaute Plugin-ZIPs liegen unter `build/distributions/`. Bei Pull Requests führt GitHub Actions mit JDK 25
`test` und `buildPlugin` aus.

### Sandbox-Abnahme vor der ersten Beta

1. Mit `runIde` ein privates GitHub-Testrepository mit aktivierten Issues öffnen. Im Pull-Request-Fenster mit dem
   GitHub-Konto von IDEA anmelden.
2. In **GitHub Issues** ohne weitere Anmeldung ein Issue anlegen, kommentieren, schließen und wieder öffnen. Nach
   jedem Schritt den Titel, Kommentar oder Status direkt auf GitHub prüfen.
3. Das Konto in der Sandbox entfernen. **GitHub Issues** muss zur Anmeldung auffordern.
4. Ein Konto mit Leserechten ohne Schreibrechte verwenden. Issues müssen sichtbar sein; beim Schließen eines von
   einem anderen Konto erstellten Issues muss GitHub den Zugriff verweigern. Den unveränderten Status auf GitHub prüfen.
5. Ein lokales Git-Projekt ohne GitHub-Remote öffnen. Das Tool Window muss melden, dass kein GitHub-Remote vorhanden ist.

### Beta veröffentlichen

Nach bestandener Sandbox-Abnahme einen Beta-Tag auf dem geprüften Commit erstellen und pushen, zum Beispiel:

```sh
git tag -a v0.1.0-beta.1 -m "Beta 0.1.0-beta.1"
git push origin v0.1.0-beta.1
```

Der Tag `vMAJOR.MINOR.PATCH-beta.NUMBER` bestimmt die Version des Plugins. GitHub Actions führt Tests, Build und
`verifyPlugin` gegen die im Projekt konfigurierte IDEA-Version aus. Erst nach Erfolg erscheint das Prerelease mit
der geprüften ZIP unter **Assets**.
