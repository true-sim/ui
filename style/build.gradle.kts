plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI styles for true-sim: typed style keys and sheets, selectors, the resolution cache, live tokens, and the Sass-like .tss language."

dependencies {
    api(project(":ui-core"))
    // The shipped themes style the controls in their partials with the controls' own keys, found as
    // StyleKeyHolder services: the theme tests here need those holders on the class path.
    testRuntimeOnly(project(":ui-controls"))
    testRuntimeOnly(project(":ui-charts")) // and the charts' (ChartStyles)
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
