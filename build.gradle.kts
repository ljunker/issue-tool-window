import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.issuetoolwindow"
version = providers.gradleProperty("pluginVersion").getOrElse("0.0.0-SNAPSHOT")

val ideaVersion = "2026.3"
val localIdea = providers.gradleProperty("ideaLocalPath")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        if (localIdea.isPresent) local(localIdea.get()) else intellijIdea(ideaVersion)
        bundledPlugin("Git4Idea")
        // GitRepository erweitert com.intellij.dvcs.repo.Repository aus diesem Plugin.
        bundledPlugin("com.intellij.platform.vcs")
        bundledPlugin("org.jetbrains.plugins.github")
        pluginVerifier()
    }
    val commonmarkVersion = "0.32.0"
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-autolink:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-task-list-items:$commonmarkVersion")
    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Keine Kompatibilitäts-Bridges für Interface-Defaults erzeugen; sonst ruft z. B. die
        // ToolWindowFactory die veralteten isApplicable/isDoNotActivateOnStart auf (Plugin Verifier).
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        version = project.version.toString()
        // Release-Workflow übergibt die Änderungen seit dem letzten Release als HTML.
        changeNotes = providers.gradleProperty("pluginChangeNotes")
        ideaVersion {
            sinceBuild = "263"
            // Keine Obergrenze: Folgeversionen mit kompatibler API dürfen das Plugin installieren.
            untilBuild = provider { null }
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginChannel").map { listOf(it) }.orElse(listOf("default"))
    }
    pluginVerification {
        // Interne API prüft verifyPlugin unten gegen eine Allowlist statt pauschal (#37).
        failureLevel = listOf(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.OVERRIDE_ONLY_API_USAGES)
        ides {
            current()
            // Zusätzlich den neuesten Release jeder IDEA-Hauptversion ab 263 prüfen.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "263"
            }
        }
    }
}

// Der Plugin Verifier kann interne API nicht gezielt erlauben. Daher darf sie nur in der Allowlist stehen;
// jede andere Nutzung interner API lässt verifyPlugin scheitern.
tasks.verifyPlugin {
    val reports = verificationReportsDirectory
    val allowlist = layout.projectDirectory.file("verifier-allowed-internal-api.txt")
    inputs.file(allowlist)
    // Berichte früherer Läufe, etwa zu inzwischen entferntem Code, sollen das Ergebnis nicht verfälschen.
    doFirst { reports.get().asFile.deleteRecursively() }
    doLast {
        val allowed = allowlist.asFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .map(::Regex)
        val reportFiles = reports.get().asFile.walk().filter { it.name == "internal-api-usages.txt" }.toList()
        val ideReports = reports.get().asFile.listFiles { file -> file.isDirectory }.orEmpty()
        if (ideReports.isEmpty()) throw GradleException("Keine Berichte des Plugin Verifiers in ${reports.get().asFile}")
        val forbidden = reportFiles.flatMap { report ->
            report.readLines().filter(String::isNotBlank)
                .filter { usage -> allowed.none { it.matches(usage) } }
                .map { "${report.relativeTo(reports.get().asFile).path}: $it" }
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "Nicht erlaubte Nutzung interner API (siehe verifier-allowed-internal-api.txt):\n" +
                    forbidden.joinToString("\n"),
            )
        }
    }
}
