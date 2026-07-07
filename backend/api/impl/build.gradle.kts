plugins {
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.versionCatalogUpdate)
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.detekt) apply false
}

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
