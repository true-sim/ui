plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "CJK UI fonts for true-sim: the Noto Sans SC and JP subsets (SIL OFL 1.1), optional for their size."

dependencies {
    api(project(":ui-fonts"))
    testImplementation(project(":ui-text-native"))
}
