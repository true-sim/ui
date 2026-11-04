plugins {
    id("truesim.java-library-conventions")
}

description = "UI property processor for true-sim: the annotation processor writing <Type>Properties classes (property metadata, lenses, accessors) for ui-props. JDK only."

dependencies {
    // The tests compile annotated sources with the processor in process and load what it wrote.
    testImplementation(project(":ui-props"))
}
