plugins {
    id("org.jetbrains.kotlin.jvm") version "1.7.10"
    application
}

version = "0.1.0"

repositories {
    jcenter()
}

dependencies {
    implementation("com.beust:klaxon:5.5")

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
