plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.72"
    application
}

repositories {
    jcenter()
}

dependencies {
    implementation("com.beust:klaxon:5.4")

    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter", "junit-jupiter-engine", "5.6.2")
}

tasks {
    test {
        useJUnitPlatform()
    }
}

application {
    mainClassName = "gcc2speedscope.AppKt"
}
