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
    mainClass.set("dev.antigravity.td.Main")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
