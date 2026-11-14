plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("commons-lang:commons-lang:2.6")
    implementation("dnsjava:dnsjava:2.1.9")
    implementation("org.ini4j:ini4j:0.5.4")
    testImplementation("io.github.jaredmdobson:concentus:1.0.2")
}

// Console client for testing the protocol against a real server:
// gradlew :ts3j:runProbe -Phost=127.0.0.1
tasks.register<JavaExec>("runProbe") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("openspeak.probe.Probe")
    args = listOf(
        (project.findProperty("host") ?: "127.0.0.1").toString(),
        (project.findProperty("port") ?: "9987").toString()
    )
}

// Second participant for end-to-end tests of the app: gradlew :ts3j:runCompanion -Pseconds=120
tasks.register<JavaExec>("runCompanion") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("openspeak.probe.Companion")
    args = listOf(
        (project.findProperty("host") ?: "127.0.0.1").toString(),
        (project.findProperty("port") ?: "9987").toString(),
        (project.findProperty("seconds") ?: "120").toString()
    )
}

// Creates test channels on a local server: gradlew :ts3j:runSetup -Ptoken=<server admin privilege key>
tasks.register<JavaExec>("runSetup") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("openspeak.probe.Setup")
    args = listOf(
        (project.findProperty("host") ?: "127.0.0.1").toString(),
        (project.findProperty("port") ?: "9987").toString(),
        (project.findProperty("token") ?: "").toString()
    )
}

// Logs how other clients stream: joins every announced stream and writes all stream notifications
// (offers, signaling JSON) to a file. gradlew :ts3j:runSniffer -Plog=capture.log
tasks.register<JavaExec>("runSniffer") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("openspeak.probe.StreamSniffer")
    systemProperty("sniffer.log", (project.findProperty("log") ?: "stream-capture.log").toString())
    args = listOf(
        (project.findProperty("host") ?: "127.0.0.1").toString(),
        (project.findProperty("port") ?: "9987").toString()
    )
}
