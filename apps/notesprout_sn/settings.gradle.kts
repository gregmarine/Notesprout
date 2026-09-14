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

rootProject.name = "notesprout_sn"
include(":app")
include(":sn-screen")
include(":markdown")
include(":extension-api")
include(":ext-mlkit")
include(":ext-ink")
include(":ext-scratchpad")
include(":ext-soil")
include(":ext-pdf")
include(":ext-document")
include(":ext-tags")
include(":ext-calendar")
include(":ext-cloud")
include(":ext-image")
// Extensions that live OUTSIDE this app folder, at the monorepo root under `extensions/` (arc 37 /
// B0, the user's call): included here by explicit projectDir so they compile against the same
// `:extension-api` / `:sn-screen` sources as every in-folder module — no published artifact, no
// second Gradle root, nothing to drift.
include(":ext-bible")
project(":ext-bible").projectDir = file("../../extensions/bible")
