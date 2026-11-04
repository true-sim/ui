plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "The developer console of true-sim's UI: commands with typed arguments, completion, history, settings as variables, a log ring fed by a sink, and the Console widget."

dependencies {
    api(project(":ui-controls"))
    // The console gallery: a scene the tests here and the engine's GPU screenshot tests build.
    testFixturesApi(project(":ui-testkit"))
    testFixturesApi(testFixtures(project(":ui-controls")))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests and benchmarks
    testImplementation(project(":ui-fonts-cjk")) // bundled fonts: the same layout on every machine
    testImplementation(project(":ui-fonts-emoji"))
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}

// Allocation tests run without escape analysis, as ui-controls' (see there).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
