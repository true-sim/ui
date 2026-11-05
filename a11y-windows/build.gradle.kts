plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Windows UI Automation for true-sim's UI: a COM provider written with the JDK's FFM, answering WM_GETOBJECT for a window, so Narrator and other screen readers read and drive the controls."

dependencies {
    api(project(":ui-a11y"))
    testImplementation(testFixtures(project(":ui-a11y"))) // the rig: a UI of named controls
}

// The provider is COM through FFM, and only Windows has UI Automation: its test (a window, a UI Automation client)
// runs there and is skipped elsewhere. CI runs Linux, where the COM half cannot run: coverage is enforced on Windows
// only, as engine-xr's is with a headset (the platform-independent half, ui-a11y, is held to it everywhere).
if (!System.getProperty("os.name", "").startsWith("Windows")) {
    coverage {
        minimum.set(0.0)
    }
}
