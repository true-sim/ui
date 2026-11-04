plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "UI gizmos for true-sim: translate, rotate, scale and bounds handles with drag solving in doubles, snapping, pivots, undo hooks, the view cube and XR manipulation."

dependencies {
    api(project(":ui-scene"))
    testFixturesApi(project(":ui-testkit"))
    testFixturesApi(testFixtures(project(":ui-scene")))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests and previews
    testImplementation(project(":ui-fonts-cjk"))
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "2g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
        testLogging.showStandardStreams = true
    }
}

// Allocation tests without escape analysis, as ui-controls' (what the JIT happens to scalar-replace differs by machine).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
