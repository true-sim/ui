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
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$natives")
        runtimeOnly("org.lwjgl:lwjgl-freetype::$natives")
        runtimeOnly("org.lwjgl:lwjgl-harfbuzz::$natives")
        runtimeOnly("org.lwjgl:lwjgl-msdfgen::$natives")
    }
}
