plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "UI properties for true-sim: property metadata from annotations, generated classes or external sources, the builder pipeline turning it into styled, live-bound property panels, and collection editors."

dependencies {
    api(project(":ui-controls"))
    // The props gallery (PropsGallery): a scene the tests here, ui-controls' sanity checks and the engine's GPU
    // screenshots build.
    testFixturesApi(project(":ui-testkit"))
    testFixturesAnnotationProcessor(project(":ui-props-processor"))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests
    testImplementation(project(":ui-fonts-cjk")) // bundled fonts: the same layout on every machine
    // The processor runs on the tests' records (FooProperties next to Foo); the benchmark compares it to reflection.
    testAnnotationProcessor(project(":ui-props-processor"))
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
        testLogging.showStandardStreams = true
    }
}

// Allocation tests must not depend on escape analysis (see ui-controls).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
