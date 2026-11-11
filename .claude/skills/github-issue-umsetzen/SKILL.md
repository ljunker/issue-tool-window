---
name: github-issue-umsetzen
description: Wählt in einem GitHub-Repository ein umsetzbares Issue aus oder übernimmt ein genanntes Issue, implementiert es im Feature-Branch und erstellt einen geprüften Pull Request. Wartet auf die Freigabe des Nutzers, mergt über den PR und räumt den Feature-Branch auf. Verwenden für den vollständigen Issue-Workflow und seine Fortsetzung nach Freigabe.
---

# GitHub-Issue umsetzen

Kommuniziere knapp im Caveman-Modus. Verwende Git und die authentifizierte GitHub CLI. Beachte die Vorgaben des
Repositorys.

## Issue auswählen und umsetzen

1. Prüfe das GitHub-Repository, den Default-Branch und den Arbeitsbaum. Lies das genannte Issue. Wenn der Nutzer die
   Auswahl delegiert, prüfe offene Issues und PRs. Wähle genau ein offenes Issue mit klarem Ergebnis, überschaubarem
   Umfang und prüfbarer Umsetzung. Meide Issues mit Blockern, laufender Umsetzung oder einem passenden offenen PR. Nenne
   das gewählte Issue und den Grund kurz.
2. Hole den aktuellen Default-Branch vom Remote und erstelle darauf einen Feature-Branch. Verwende `feature/` als
   Standardpräfix und halte fremde lokale Änderungen aus dem Branch heraus. Verwende bei Bedarf einen eigenen Worktree.
3. Setze das Issue vollständig um und führe die passenden Projektprüfungen aus. Prüfe den Diff. Jeder selbst verfasste
   Commit erhält eine Überschrift, eine Leerzeile und eine Liste der Änderungen.
4. Pushe den Branch und erstelle einen Pull Request gegen den Default-Branch. Verlinke das vollständig gelöste Issue im
   PR-Text mit `Closes #<Nummer>`. Beschreibe Änderungen, durchgeführte Prüfungen und offene Punkte. Prüfe die CI und
   behebe Fehler, die zur Änderung gehören.

## Auf Freigabe warten

Übergib den PR mit Link, Branch, aktuellem Head-Commit und Prüfstatus. Bitte den Nutzer um eindeutige Freigabe zum Merge
und beende den Turn. Ein grüner Check, ein fremdes Review oder allgemeines Lob ersetzt die Freigabe des Nutzers nicht.
Eine Antwort wie „Approve“ oder „Merge den PR“ gilt, wenn sie sich eindeutig auf den vorgestellten PR bezieht.

Wenn der Nutzer ausdrücklich auf GitHub freigeben will, verifiziere ein `APPROVED`-Review seines eindeutig zugeordneten
Accounts für denselben Head-Commit.

Bei Änderungswünschen aktualisiere denselben PR und übergib den neuen geprüften Stand. Eine Freigabe gilt für den
vorgestellten Head-Commit. Wenn sich dieser seit der Übergabe geändert hat, lege den aktualisierten Stand zur Freigabe
vor.

## Nach Freigabe mergen und aufräumen

1. Prüfe PR, Head-Commit, Pflichtchecks und Mergefähigkeit erneut. Bei einem bereits gemergten PR setze mit der
   Bereinigung fort. Merge sonst über GitHub mit einer im Repository erlaubten Methode. Bevorzuge einen Merge-Commit,
   wenn er der Projektpraxis entspricht. Sichere den geprüften Head beim Merge mit
   `gh pr merge --match-head-commit <SHA>`. Verwende für einen selbst verfassten Merge- oder Squash-Commit ebenfalls
   Überschrift und Änderungsliste.
2. Verifiziere den Merge auf GitHub. Aktualisiere den lokalen Zielbranch per Fast-Forward. Lösche danach den
   Feature-Branch dieses Ablaufs lokal und auf dem Remote, wenn er noch auf dem gemergten PR-Stand steht. Entferne einen
   eigens angelegten Worktree nur bei sauberem Arbeitsbaum. Bereinige veraltete Remote-Refs und schütze fremde
   Änderungen.
3. Prüfe den Arbeitsbaum, die Branch-Bereinigung und den Status des Issues. Melde PR, Merge-Commit und Abschlussstatus.
   Wenn eine Repository-Regel den Merge oder lokale Änderungen die Bereinigung blockieren, benenne den konkreten offenen
   Schritt.
