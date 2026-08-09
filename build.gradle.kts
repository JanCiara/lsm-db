plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.2"
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

// Benchmarki (M5): ./gradlew jmh — wyniki laduja w build/results/jmh/results.txt
// Pojedyncza klasa: ./gradlew jmh -PjmhIncludes=WriteBenchmark
jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    // Krotsze iteracje niz domyslne 10 s — caly przebieg ma miescic sie w kilku minutach.
    warmup.set("1s")
    timeOnIteration.set("2s")
    // Tryb i jednostke ustala kazdy benchmark u siebie (@BenchmarkMode/@OutputTimeUnit) —
    // globalne nadpisanie zamienilo by pomiar compaction w cos innego niz deklaruje.
    if (project.hasProperty("jmhIncludes")) {
        includes.set(listOf(project.property("jmhIncludes").toString()))
    }
}
