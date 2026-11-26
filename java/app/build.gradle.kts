// The CLI: argument parsing, output, and the app logic.
plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":platform"))
    implementation(libs.picocli)
    implementation(libs.jackson.databind)
    // dbus-java logs through SLF4J; with no provider SLF4J warns on stderr.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass = "whereami.cli.Main"
    applicationName = "whereami"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED", // allow FFM without a warning
        "-Dstdout.encoding=UTF-8",           // otherwise the console code page (e.g. 437)
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.withType<JavaCompile> {
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
