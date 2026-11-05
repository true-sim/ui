plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    `java-test-fixtures`
}

description = "The platform-independent half of true-sim's OS accessibility bridges: snapshots of the semantics tree, actions from assistive technology run on the UI thread, change events."

dependencies {
    api(project(":ui-core"))
    // A UI of named controls the bridges' tests read and drive (here and in ui-a11y-windows).
    testFixturesApi(project(":ui-controls"))
    testFixturesApi(project(":ui-testkit"))
    testFixturesApi(project(":ui-fonts-cjk")) // bundled fonts: the same layout on every machine
}

// Allocation tests run without escape analysis, as ui-controls' (see there).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
