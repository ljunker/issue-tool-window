plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.issuetoolwindow"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

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
        ideaVersion {
            sinceBuild = "263"
            untilBuild = "263.*"
        }
    }
    pluginVerification {
        ides { current() }
    }
}
