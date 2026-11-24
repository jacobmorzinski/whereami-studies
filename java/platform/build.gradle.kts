// The library: model, Platform interface, and the per-OS backends.
// The Clojure port will depend on this project too.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile> {
    // Built with JDK 27, but only Java 25 (LTS) APIs and bytecode.
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    // FFM downcalls are "restricted"; without this the JVM prints a warning.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
