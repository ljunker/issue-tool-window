# GitHub Issues für IntelliJ IDEA

Das Tool Window **GitHub Issues** zeigt Issues aus den GitHub.com-Remotes des geöffneten Projekts. Es verwendet das
GitHub-Konto aus IDEA, das auch im Fenster **Pull Requests** zur Verfügung steht.

## Installation und Nutzung

1. In IntelliJ IDEA 2026.3 unter **Settings → Plugins → ⚙ → Install Plugin from Disk…** die Datei
   `build/distributions/issue-tool-window-0.1.0.zip` auswählen. Bei Bedarf IDEA neu starten.
2. Ein Git-Projekt mit einem GitHub.com-Remote öffnen. Unter **View → Tool Windows → GitHub Issues** das Fenster öffnen.
3. Bei mehreren Repositories oder Konten die passende Auswahl treffen. **Sign In to GitHub** öffnet bei Bedarf den
   Anmeldedialog von IDEA.
4. Mit **Open**, **Closed** und **All** filtern. Ein Issue auswählen, um Details und Kommentare zu sehen. **New Issue**
   legt ein Issue an. In den Details stehen **Post Comment**, **Close Issue** und **Reopen Issue** zur Verfügung.

## Build und Abnahme

JDK 25 als `JAVA_HOME` setzen. Der Wrapper lädt die passende IDEA-Version automatisch. Mit
`-PideaLocalPath="/Pfad/zu/IntelliJ IDEA.app"` lässt sich stattdessen eine vorhandene Installation von IDEA 2026.3
nutzen.

```sh
./gradlew test buildPlugin verifyPlugin
./gradlew runIde --args="/Pfad/zum/Testprojekt"
```

Für die Live-Abnahme in der Sandbox zuerst im Pull-Request-Fenster anmelden und dann ein privates GitHub-Testprojekt in
**GitHub Issues** öffnen. Ohne weitere Anmeldung ein Issue anlegen, kommentieren, schließen und wieder öffnen. Auf
GitHub prüfen, ob alle Änderungen angekommen sind. Anschließend den Zustand ohne Konto, ein Konto ohne Schreibrechte und
ein Projekt ohne GitHub-Remote prüfen.
