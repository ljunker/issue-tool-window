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
        bundledPlugin("org.jetbrains.plugins.github")
        pluginVerifier()
    }
    val commonmarkVersion = "0.32.0"
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-autolink:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-task-list-items:$commonmarkVersion")
    testImplementation(kotlin("test-junit"))
}

kotlin { jvmToolchain(25) }

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        version = project.version.toString()
        // Release-Workflow übergibt die Änderungen seit dem letzten Release als HTML.
        changeNotes = providers.gradleProperty("pluginChangeNotes")
        ideaVersion {
            sinceBuild = "263"
            untilBuild = "263.*"
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
        ides { current() }
    }
}
