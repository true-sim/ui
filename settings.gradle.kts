pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.15.0"
}

rootProject.name = "ui"

// Each module is published on its own, so a tool or server takes only what it needs. Only ui-text-native has native
// libraries (LWJGL's FreeType, HarfBuzz, msdfgen); the others need the JDK and true-sim's repositories alone.
include("ui-core", "ui-style", "ui-layout", "ui-text", "ui-text-native", "ui-controls", "ui-testkit")
project(":ui-core").projectDir = file("core")
project(":ui-style").projectDir = file("style")
project(":ui-layout").projectDir = file("layout")
project(":ui-text").projectDir = file("text")
project(":ui-text-native").projectDir = file("text-native")
project(":ui-controls").projectDir = file("controls")
project(":ui-testkit").projectDir = file("testkit")

val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "scene")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
