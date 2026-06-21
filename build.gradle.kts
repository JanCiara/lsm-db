plugins {
    `java-library`
}

group = "dev.janciara"
version = "0.1.0"

java {
    toolchain {
        // Wymusza JDK 21 niezaleznie od domyslnego javy w systemie.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// JMH (benchmarki) dojdzie w M5 — wtedy dodamy plugin "me.champeau.jmh".
