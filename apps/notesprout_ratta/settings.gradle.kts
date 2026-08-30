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
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "notesprout_ratta"
include(":app")
include(":sn-screen")
include(":extension-api")
include(":ext-mlkit")
include(":ext-scratchpad")
include(":ext-soil")
include(":ext-pdf")
