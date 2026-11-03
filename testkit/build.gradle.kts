plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI test kit for true-sim: synthetic commands and pointers, draw-list goldens, a headless frame loop."

dependencies {
    api(project(":ui-core"))
}
