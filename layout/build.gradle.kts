plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI layout for true-sim: measure and arrange; stack, flex, grid, border, anchors and overlay; pixel snapping."

val lwjglVersion: String by rootProject.extra
val lwjglNatives: List<String> by rootProject.extra

dependencies {
    api(project(":ui-core"))
    // Yoga is the conformance oracle and the benchmark's competitor (design 4.6, UI14), never a production dependency.
    testImplementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    testImplementation("org.lwjgl:lwjgl")
    testImplementation("org.lwjgl:lwjgl-yoga")
    for (natives in lwjglNatives) {
        testRuntimeOnly("org.lwjgl:lwjgl::$natives")
        testRuntimeOnly("org.lwjgl:lwjgl-yoga::$natives")
    }
}

// The benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
