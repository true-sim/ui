plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "UI controls for true-sim: panels, labels and images over the element store, their decorations, and the frame that styles, lays out and records them."

dependencies {
    api(project(":ui-core"))
    api(project(":ui-style"))
    api(project(":ui-layout"))
    api(project(":ui-text"))
    // The galleries (ControlsGallery, DataGallery, WindowsScene): scenes of every control that the tests here and the
    // engine's GPU screenshot tests build.
    testFixturesApi(project(":ui-testkit"))
    testImplementation(project(":ui-testkit"))
    testImplementation(project(":ui-text-native")) // the real text path in allocation tests and benchmarks
    testImplementation(project(":ui-fonts-cjk")) // bundled fonts: the same layout on every machine
    testImplementation(project(":ui-fonts-emoji")) // colour emoji in text fields
    // The shipped themes style charts with ChartStyles' keys; GallerySanityTest checks the charts gallery too.
    testImplementation(testFixtures(project(":ui-charts")))
}

// Benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}

// Allocation tests must not depend on what the JIT happens to scalar-replace: FontRegistry.chain's lookup key (24 bytes
// a reshape) allocated on Linux CI and was removed by escape analysis on Windows (true-sim/ui CI run 41706850516), and
// LWJGL's HarfBuzz calls allocate a segment each unless inlined. Without escape analysis such allocations show on
// every machine.
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
