// Shared by the modules: LWJGL (ui-text-native only) with natives for the desktop platforms, the engine's version.
val lwjglVersion by extra("3.4.3")
val lwjglNatives by extra(
    listOf("natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64", "natives-macos", "natives-macos-arm64"),
)

subprojects {
    // LWJGL uses native access; the JDK-only modules do not mind the flag.
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
