plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI text for true-sim: fonts, shaping and rasterizer interfaces, itemisation, BiDi, line breaking, text layout and the glyph atlas; a JDK-only fallback."

dependencies {
    api(project(":ui-core"))
}
