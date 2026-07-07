plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies { implementation(project(":shared")) }

application { mainClass = "software.medusa.workload.server.MainKt" }
