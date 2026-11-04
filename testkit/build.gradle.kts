plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI test kit for true-sim: synthetic commands and pointers, draw-list goldens, a headless frame loop."

dependencies {
    api(project(":ui-core"))
    // LayoutSanity checks a whole Ui (layout, styles, draw lists); ui-controls' tests use the kit in turn, which
    // Gradle allows: no task depends on itself.
    api(project(":ui-controls"))
    testImplementation(project(":ui-fonts")) // LayoutSanity's tests read real glyphs
    testImplementation(testFixtures(project(":ui-controls"))) // and check the galleries
    testRuntimeOnly(project(":ui-charts")) // the themes style charts with its keys
}
