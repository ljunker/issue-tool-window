# Entwicklung

Hinweise zu Build, Abnahme und Release des Plugins. Zur Installation und Nutzung siehe [README](README.md).

## Voraussetzungen

- JDK 25 als `JAVA_HOME`
- Der Gradle-Wrapper lädt die passende IDEA-Version (2026.3, Build-Reihe `263.*`) automatisch. Mit
  `-PideaLocalPath="/Pfad/zu/IntelliJ IDEA.app"` lässt sich stattdessen eine vorhandene Installation von IDEA 2026.3
  nutzen.

## Build und Test

```sh
./gradlew test buildPlugin verifyPlugin
./gradlew runIde --args="/Pfad/zum/Testprojekt"
```

Lokal gebaute Plugin-ZIPs liegen unter `build/distributions/`. Bei Pull Requests führt GitHub Actions mit JDK 25
`test`, `buildPlugin` und `verifyPlugin` aus.

## Sandbox-Abnahme vor einem Release

1. Mit `runIde` ein privates GitHub-Testrepository mit aktivierten Issues öffnen. Im Pull-Request-Fenster mit dem
   GitHub-Konto von IDEA anmelden.
2. In **GitHub Issues** ohne weitere Anmeldung ein Issue anlegen, kommentieren, schließen und wieder öffnen. Nach
   jedem Schritt den Titel, Kommentar oder Status direkt auf GitHub prüfen.
3. Das Konto in der Sandbox entfernen. **GitHub Issues** muss zur Anmeldung auffordern.
4. Ein Konto mit Leserechten ohne Schreibrechte verwenden. Issues müssen sichtbar sein; beim Schließen eines von
   einem anderen Konto erstellten Issues muss GitHub den Zugriff verweigern. Den unveränderten Status auf GitHub prüfen.
5. Ein lokales Git-Projekt ohne GitHub-Remote öffnen. Das Tool Window muss melden, dass kein GitHub-Remote vorhanden ist.

## Release und Versionierung

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

### Repository-Secrets

| Secret                   | Zweck                                                                    |
|--------------------------|--------------------------------------------------------------------------|
| `PUBLISH_TOKEN`          | Marketplace-Token ([Profil → Tokens](https://plugins.jetbrains.com/author/me/tokens)); ohne Token kein Marketplace-Upload |
| `CERTIFICATE_CHAIN`      | Zertifikatskette (PEM) für die [Plugin-Signatur](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) |
| `PRIVATE_KEY`            | Privater Schlüssel (PEM) zur Zertifikatskette                            |
| `PRIVATE_KEY_PASSWORD`   | Passwort des privaten Schlüssels                                         |

Ohne Signatur-Secrets wird das Plugin unsigniert veröffentlicht.

### Erste Veröffentlichung

Die **erste** Version muss einmalig manuell auf [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/add)
hochgeladen werden; erst danach kann der Workflow neue Versionen per Token veröffentlichen. Dafür `PUBLISH_TOKEN`
zunächst weglassen, die ZIP des ersten automatisch erstellten GitHub-Release manuell hochladen und das Secret erst
danach anlegen.
