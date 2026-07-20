plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies { implementation(project(":backend:api:impl:shared")) }

application { mainClass = "software.medusa.workload.server.MainKt" }
