plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.shadow)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories { mavenCentral() }

dependencies {
  implementation(project(":libraries:docker-connector"))
  implementation(project(":libraries:workload-runtime"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  // Same reasoning as cli/build.gradle.kts: the connector declares only slf4j-api, and a real
  // binding is what keeps Netty/Armeria quiet.
  runtimeOnly(libs.logback.classic)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.core)
}

application { mainClass = "software.medusa.workload.agent.MainKt" }

// Deliberately not overriding archiveClassifier to "" the way cli/build.gradle.kts does: cli's
// project directory name ("cli") differs from its shadowJar base name ("workload-cli"), so the
// plain `jar` task and `shadowJar` never collide on the same output file. Here the project
// directory *is* "workload-agent", so blanking the classifier would make `shadowJar` overwrite
// plain `jar`'s default output path (`build/libs/workload-agent.jar`) without Gradle seeing a task
// dependency between them — exactly the validation failure this comment is here to prevent
// reintroducing. Keeping the default "-all" classifier keeps the two outputs distinct.
tasks.shadowJar {
  archiveBaseName = "workload-agent"
  archiveVersion = ""
}

tasks.named("check") { dependsOn(tasks.named("ktfmtCheck")) }

detekt { config.setFrom(rootProject.file("config/detekt/detekt.yml")) }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }

tasks.withType<Test>().configureEach { useJUnitPlatform() }
