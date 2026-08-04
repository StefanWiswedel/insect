// Deliberately empty. Plugins are declared per-module rather than pre-resolved
// here with `apply false`, because that would make every Gradle invocation --
// including `:core:test` -- resolve the Android Gradle Plugin, and :core is
// meant to build and test on a machine with no Android SDK.
