plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin. No Android dependencies, on purpose: everything in here is
// deterministic and unit-testable on the JVM, which is the only way the trigger
// pipeline gets verified without a phone in hand.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
