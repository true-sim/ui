plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "English spelling dictionaries for true-sim's UI: SCOWL's Hunspell en_US and en_GB (SCOWL licence), optional for their size."

dependencies {
    api(project(":ui-text"))
}
