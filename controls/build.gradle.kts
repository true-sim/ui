plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI controls for true-sim: panels, labels and images over the element store, their decorations, and the frame that styles, lays out and records them."

dependencies {
    api(project(":ui-core"))
    api(project(":ui-style"))
    api(project(":ui-layout"))
    api(project(":ui-text"))
    testImplementation(project(":ui-testkit"))
}
