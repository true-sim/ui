plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Native UI text for true-sim: FreeType fonts, HarfBuzz shaping and msdfgen MTSDF glyphs through LWJGL."

val lwjglVersion: String by rootProject.extra
val lwjglNatives: List<String> by rootProject.extra

dependencies {
    api(project(":ui-text"))
    api(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    api("org.lwjgl:lwjgl")
    api("org.lwjgl:lwjgl-freetype")
    api("org.lwjgl:lwjgl-harfbuzz")
    api("org.lwjgl:lwjgl-msdfgen")
    // stb_rect_pack only competes with our skyline packer in the benchmark; ours won, so stb stays out of main
    testImplementation("org.lwjgl:lwjgl-stb")
    testImplementation(project(":ui-fonts-cjk")) // bundled fonts: the same goldens on every machine
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$natives")
        runtimeOnly("org.lwjgl:lwjgl-freetype::$natives")
        runtimeOnly("org.lwjgl:lwjgl-harfbuzz::$natives")
        runtimeOnly("org.lwjgl:lwjgl-msdfgen::$natives")
        testRuntimeOnly("org.lwjgl:lwjgl-stb::$natives")
    }
}

// The build-time half of the atlas (UI11): `tools` holds the baker's command line; it runs the same atlas code the
// run time does and writes the glyph cache the run time loads.
val tools: SourceSet = sourceSets.create("tools") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations["toolsImplementation"].extendsFrom(configurations["implementation"], configurations["api"])
dependencies {
    "toolsImplementation"(project(":ui-fonts-cjk")) // the baker bakes the bundled fonts, then installed ones
}
configurations["toolsRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])
sourceSets.test {
    compileClasspath += tools.output
    runtimeClasspath += tools.output
}

// ./gradlew :ui-text-native:bakeAtlas -PatlasText=<file with the game's text> -PatlasFamilies="sans-serif|serif"
//   -PatlasOut=<cache file> -PatlasEmSize=40 -PatlasPixelRange=6
val atlasOut = providers.gradleProperty("atlasOut").map { file(it) }
    .orElse(layout.buildDirectory.file("atlas/glyphs.tsgc").map { it.asFile })
val atlasText = providers.gradleProperty("atlasText").orElse("")
val atlasFamilies = providers.gradleProperty("atlasFamilies").orElse("sans-serif")
val atlasEmSize = providers.gradleProperty("atlasEmSize").orElse("40")
val atlasPixelRange = providers.gradleProperty("atlasPixelRange").orElse("6")
tasks.register<JavaExec>("bakeAtlas") {
    group = "build"
    description = "Pre-bakes MTSDF glyphs (Latin plus -PatlasText) into the glyph cache file the run time loads."
    classpath = tools.runtimeClasspath
    mainClass = "com.truesim.ui.text.natives.tools.BakeAtlas"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // resolved when the task is configured: Gradle properties are configuration inputs of the cache anyway
    args(
        listOf(
            "--out", atlasOut.get().path,
            "--families", atlasFamilies.get(),
            "--em-size", atlasEmSize.get(),
            "--pixel-range", atlasPixelRange.get(),
        ) + (if (atlasText.get().isEmpty()) emptyList() else listOf("--text-file", atlasText.get())),
    )
    outputs.file(atlasOut)
}

tasks.named("check") {
    dependsOn(tasks.named("compileToolsJava"))
}

// Screenshots (TRUESIM_SCREENSHOTS=true) and benchmarks (TRUESIM_BENCHMARK=true) are tests that skip otherwise.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}

// Allocation tests without escape analysis: what the JIT scalar-replaces differs between machines (see ui-controls).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}
