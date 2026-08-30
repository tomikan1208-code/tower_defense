plugins {
    application
    java
}

group = "dev.antigravity"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.minestom:minestom:2026.03.25-1.21.11")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("dev.antigravity.mazeward.MazewardMain")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Headless verification of the pure logic (grid, A*, stage generation, placement rules).
// Run with `gradle selfCheck` - no Minecraft client needed.
tasks.register<JavaExec>("selfCheck") {
    group = "verification"
    description = "Runs headless checks on grid, pathfinding and stage generation"
    mainClass.set("dev.antigravity.mazeward.dev.SelfCheck")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

// Headless integration run of the real combat loop (maze building, waves, towers, enemies).
tasks.register<JavaExec>("combatSim") {
    group = "verification"
    description = "Simulates full stages headlessly without a Minecraft client"
    mainClass.set("dev.antigravity.mazeward.dev.CombatSim")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}
