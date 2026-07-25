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

includeBuild("../wire/kotlin-contract") {
    dependencySubstitution {
        substitute(module("app.momoding:wire-kotlin-contract"))
            .using(project(":"))
    }
}

rootProject.name = "momoding-android"
include(":app")
