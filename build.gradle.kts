plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.2"
}

group = "dev.janciara"
version = "0.1.0"

java {
    toolchain {
        // Pins JDK 21 regardless of the system default java.
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

// Benchmarks (M5): ./gradlew jmh — results land in build/results/jmh/results.txt
// A single class: ./gradlew jmh -PjmhIncludes=WriteBenchmark
jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    // Shorter iterations than the default 10 s — the whole run should fit in a few minutes.
    warmup.set("1s")
    timeOnIteration.set("2s")
    // Mode and time unit are set per benchmark (@BenchmarkMode/@OutputTimeUnit) — overriding them
    // globally would turn the compaction measurement into something other than it claims to be.
    if (project.hasProperty("jmhIncludes")) {
        includes.set(listOf(project.property("jmhIncludes").toString()))
    }
}
