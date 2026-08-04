pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include(":ksp")

// Composite build: compile SimpleBedrockModel from local source (./sbm)
// so edits to SBM land in SBW rebuilds instead of pulling a prebuilt jar
// from jitpack. Substitutes the module com.github.mcmodderanchor:simplebedrockmodel
// (declared as jarJar dependency in build.gradle.kts) with SBM's root project.
includeBuild("sbm") {
    dependencySubstitution {
        substitute(module("com.github.mcmodderanchor:simplebedrockmodel"))
            .using(project(":"))
    }
}