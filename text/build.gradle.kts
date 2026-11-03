plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI text for true-sim: fonts, shaping and rasterizer interfaces, itemisation, BiDi, line breaking, text layout and the glyph atlas; a JDK-only fallback."

dependencies {
    api(project(":ui-core"))
    api("com.truesim.common:common-jobs:0.3.2")
    testImplementation(project(":ui-fonts-cjk")) // bundled fonts: the JDK fallback tested the same everywhere
}

// Screenshots (TRUESIM_SCREENSHOTS=true) and benchmarks (TRUESIM_BENCHMARK=true) read these; normal runs skip them.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}
