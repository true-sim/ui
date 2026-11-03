plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI controls for true-sim: panels, labels and images over the element store, their decorations, and the frame that styles, lays out and records them."

dependencies {
    api(project(":ui-core"))
    api(project(":ui-style"))
    api(project(":ui-layout"))
    api(project(":ui-text"))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests and benchmarks
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
