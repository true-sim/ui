plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "UI charts for true-sim: axes, ticks, legends and series (lines, areas, bars, scatter, histograms, heatmaps, pies, candlesticks and more) over versioned data, with min-max pyramids and LTTB for millions of points."

dependencies {
    api(project(":ui-controls"))
    // ChartsGallery, the ImPlot-parity demo page: built by the tests here, ui-controls' gallery sanity test and the
    // engine's GPU screenshots.
    testFixturesApi(project(":ui-testkit"))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests and benchmarks
    testImplementation(project(":ui-fonts-cjk")) // bundled fonts: the same layout on every machine
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
        testLogging.showStandardStreams = true
        maxHeapSize = "4g"
    }
}

// Allocation tests without escape analysis, as ui-controls' (see there).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
