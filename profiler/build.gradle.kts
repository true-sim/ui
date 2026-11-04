plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "The profiler views of true-sim's UI: the frame graph overlay (frame times streaming on a chart) and the detailed profiler (a timeline of every thread and GPU queue, a flame graph, a zone table, counters) over a recording, with exports to its own format and Chrome's trace JSON."

dependencies {
    api(project(":ui-charts"))
    // The profiler gallery: a scene the tests here and the engine's GPU screenshot tests build.
    testFixturesApi(project(":ui-testkit"))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests and previews
    testImplementation(project(":ui-fonts-cjk")) // bundled fonts: the same layout on every machine
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
        testLogging.showStandardStreams = true
    }
}

// Allocation tests without escape analysis, as ui-controls' (see there).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
