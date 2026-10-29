import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm") version "2.4.21"
}

group = "pele"
version = "1.12.0"

repositories {
  mavenCentral()
}

dependencies {
  testImplementation(kotlin("test"))
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xjdk-release=17")
  }
}

java {
  withSourcesJar()
}

// The jars carry upstream's MIT license and say which upstream commit they port.
tasks.withType<Jar>().configureEach {
  from(rootDir.resolve("../LICENSE")) { into("META-INF") }
  manifest {
    attributes(
      "Implementation-Title" to "pele-kotlin",
      "Implementation-Version" to project.version,
    )
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(17)
}

fun Test.configurePeleTests(stackSize: String = "4m") {
  useJUnitPlatform()
  maxHeapSize = "2g"
  jvmArgs("-Duser.timezone=UTC", "-Xss$stackSize")
  systemProperty("pele.testData", layout.projectDirectory.dir("testData").asFile.absolutePath)
  System.getProperty("pele.type")?.let { systemProperty("pele.type", it) }
  System.getProperty("pele.svgOut")?.let { systemProperty("pele.svgOut", it) }
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStandardStreams = false
  }
}

tasks.test {
  configurePeleTests()
}

tasks.register<Test>("testSmallStack") {
  description = "Checks public API and large flat inputs with a 1 MB thread stack."
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  configurePeleTests("1m")
  filter { includeTestsMatching("pele.PeleTest") }
}

// `\b` and a few other regex behaviours differ on JDK <= 18, so the suite also runs on 17.
tasks.register<Test>("testJdk17") {
  description = "Runs the test suite on a JDK 17 toolchain."
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
  configurePeleTests()
}

// Differential fuzzing driver for scripts/run.sh.
tasks.register<JavaExec>("fuzzRender") {
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("pele.FuzzMainKt")
  args(providers.gradleProperty("inputs").getOrElse("inputs.txt"))
  jvmArgs("-Xss4m")
  providers.gradleProperty("jdk").orNull?.let { v ->
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(v.toInt())) })
  }
}

tasks.register<JavaExec>("benchmarkJourney") {
  description = "Reports journey rendering time and allocation as task counts grow."
  group = "verification"
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("pele.JourneyBenchmarkKt")
  maxHeapSize = "512m"
}
