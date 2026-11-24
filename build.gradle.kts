plugins {
    java
}

group = "fi.alavesa"
version = "1.0.1"

// Paper publishes the 26.x API as <mc>.build.<n>-<channel> (there is no -R0.1-SNAPSHOT any more).
// Pinned to an exact stable build so the build is reproducible; bump the build number to move forward.
val paperApi = "io.papermc.paper:paper-api:26.3.build.253-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Gson, Adventure/MiniMessage, JOML and Brigadier all come with the Paper API - nothing is shaded,
    // so there is no shadow plugin: the plain `jar` task output is the plugin.
    compileOnly(paperApi)
    // SCP-079 (soft dependency): only its public api package is used, behind link/Scp079Link
    compileOnly(files("libs/SCP079-1.3.0.jar"))

    testImplementation(paperApi)
    testImplementation(files("libs/SCP079-1.3.0.jar"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // Minecraft / Paper 26.3 run on Java 25. --release 25 lets any JDK >= 25 build it (no toolchain download).
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}

tasks.jar {
    archiveBaseName.set("ScpAccess")
}
