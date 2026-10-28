// Stand-alone JVM build used to compile, test and benchmark the pure game code
// (engine + renderer) without the Android / Compose toolchain.
rootProject.name = "lastasylum-verify"
pluginManagement { repositories { maven("https://repo1.maven.org/maven2"); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven("https://repo1.maven.org/maven2") } }
