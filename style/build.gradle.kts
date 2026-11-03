plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI styles for true-sim: typed style keys and sheets, selectors, the resolution cache, live tokens, and the Sass-like .tss language."

dependencies {
    api(project(":ui-core"))
    // The shipped themes style every control: their tests compile them with the controls' style keys
    // (StyleKeyProvider through ServiceLoader).
    testRuntimeOnly(project(":ui-controls"))
}

// The reload benchmark (TRUESIM_BENCHMARK=true) prints its timings; it measures the code, not coverage probes, with
// a heap large enough that young collections stay rare.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "2g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
        testLogging.showStandardStreams = true
    }
}
