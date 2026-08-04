pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "biomon-insect"

// :core is pure Kotlin/JVM and holds the trigger pipeline, policies and record
// formats. It builds and tests without the Android SDK -- `./gradlew :core:test`
// is the fast loop for anything algorithmic.
include(":core")

// :app needs the Android SDK. Including it unconditionally makes every Gradle
// invocation fail on a machine that has no SDK, which would also take :core
// down with it, so it is included only when an SDK is actually present.
val hasAndroidSdk = sequenceOf(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    file("local.properties").takeIf { it.exists() }?.let { propsFile ->
        val props = java.util.Properties()
        propsFile.inputStream().use { stream -> props.load(stream) }
        props.getProperty("sdk.dir")
    },
).any { !it.isNullOrBlank() && file(it).isDirectory }

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle(
        "No Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties): " +
            "skipping :app. :core still builds and tests."
    )
}
