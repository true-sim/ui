plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "UI core for true-sim: elements over packed arrays, the draw list, UI commands, semantics, and the interfaces an engine implements."

dependencies {
    api("com.truesim.common:common-state:0.3.0")
    api("com.truesim.common:common-math:0.6.0")
}
