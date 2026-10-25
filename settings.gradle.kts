pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ClosePaw"
include(":app")

// Optional native InputConnection contract fixture. Never included in production builds.
if (providers.gradleProperty("closepawInputFixtures").orNull == "true") {
    include(":terminal-fixture")
    project(":terminal-fixture").projectDir = file("qa/terminal-fixture")
}
