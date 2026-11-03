plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Colour emoji for true-sim's UI: Noto Color Emoji (SIL OFL 1.1), optional for its size."

dependencies {
    api(project(":ui-fonts"))
    testImplementation(project(":ui-text-native"))
}
