plugins { kotlin("jvm") }
repositories { mavenCentral() }

dependencies {
    // Compiled against the interfaces only. The desktop build (JNA) and the
    // Android build (JNI) ship in different artifacts, so each consumer supplies
    // its own — otherwise the desktop natives would be packaged into the APK.
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    compileOnly("net.java.dev.jna:jna:5.14.0")

    testImplementation(kotlin("test"))
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    testImplementation("net.java.dev.jna:jna:5.14.0")
}

kotlin { jvmToolchain(17) }
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
