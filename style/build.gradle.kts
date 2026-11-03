plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI styles for true-sim: typed style keys and sheets, selectors, the resolution cache, live tokens, and the Sass-like .tss language."

dependencies {
    api(project(":ui-core"))
}
