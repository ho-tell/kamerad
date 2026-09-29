pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext, vendored from Github Packages (see libs/README.md)
        maven {
            url = uri(settingsDir.resolve("libs/maven"))
            content { includeGroup("io.hammerhead") }
        }
    }
}

rootProject.name = "Kamerad"
include("app")
