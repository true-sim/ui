plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI fonts for true-sim: Inter, Noto Sans Arabic and Noto Sans Devanagari (SIL OFL 1.1), the default fallback chain."

dependencies {
    api(project(":ui-text"))
    testImplementation(project(":ui-text-native"))
}
