plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
    application
}

version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.h2database:h2:2.2.220")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter", "junit-jupiter-engine", "5.9.1")
}

tasks {
    test {
        useJUnitPlatform()
    }
}

application {
    mainClass.set("gcc2speedscope.AppKt")
}
