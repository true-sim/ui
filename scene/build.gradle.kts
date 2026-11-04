plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "UI scene bridge for true-sim: the Interactive component, scene picking through scene's ScenePicker, world panels and camera views."

dependencies {
    api(project(":ui-controls"))
    // Components, Pickable and ScenePicker (the picking model this bridge routes) arrived in scene 0.64.0.
    api("com.truesim.scene:scene-core:0.64.0")
    testFixturesApi(project(":ui-testkit"))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests and previews
    testImplementation(project(":ui-fonts-cjk"))
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes; the picking benchmark holds 100,000
// nodes.
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
