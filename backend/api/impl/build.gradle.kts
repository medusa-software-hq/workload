// Plugin versions are declared once in the root build.gradle.kts (`apply false`); this project only
// configures the three api modules below.
allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "com.ncorti.ktfmt.gradle")
        apply(plugin = "io.gitlab.arturbosch.detekt")

        tasks.named("check") {
            dependsOn(tasks.named("ktfmtCheck"))
        }

        // One detekt config for every module in the repo (detekt would otherwise look for a
        // per-project config/detekt/detekt.yml).
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        }

        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.add("-parameters")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
