plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    application
}

version = "0.2.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases")
        content {
            includeGroup("org.gradle")
        }
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.h2database:h2:2.2.220")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.github.erosb:everit-json-schema:1.14.2")

    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // The tooling API need an SLF4J implementation available at runtime, replace this with any other implementation
    testImplementation("org.gradle:gradle-tooling-api:7.3")
    testRuntimeOnly("org.slf4j:slf4j-simple:1.7.10")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

tasks {
    test {
        useJUnitPlatform()
    }
}

application {
    mainClass.set("gcc2speedscope.AppKt")
}
