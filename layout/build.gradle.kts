plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI layout for true-sim: measure and arrange; stack, flex, grid, border, anchors and overlay; pixel snapping."

dependencies {
    api(project(":ui-core"))
}
