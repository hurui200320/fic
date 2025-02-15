import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("org.graalvm.buildtools.native") version "0.10.3"
}

group = "info.skyblond"
version = "2.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("info.skyblond.fic.MainKt")
}

graalvmNative {
    toolchainDetection.set(true)

    binaries {
        all {
            resources.autodetect()
        }
        named("main") {
            buildArgs("--gc=G1")
            buildArgs("-R:MaxRAMPercentage=50")
            buildArgs("--strict-image-heap")
            buildArgs("-march=x86-64-v3")
        }
    }
}