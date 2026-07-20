# The demo/diagnostic image the manual end-to-end test runs. It's an ordinary Kotlin/JVM app that
# reads a GCS object with google-cloud-storage — no auth code of its own; the credential comes from
# the metadata server the CLI runs (M4 Beacon), via ADC. See src/ and the README.
#
# The fat jar is built by Gradle *before* this image (the publish workflow runs
# `./gradlew :images:hello-workload:shadowJar`) and is architecture-neutral, so a single jar serves
# every platform; only the JRE base is multi-arch. Multi-arch matters here (unlike the SPA image):
# this runs on developer Macs and the arm64 Ubuntu VM, not on Cloud Run.
FROM eclipse-temurin:21-jre

COPY build/libs/hello-workload.jar /app/hello-workload.jar

# No CMD/args: `workload run` never overrides the image's command — the profile's env drives it.
ENTRYPOINT ["java", "-jar", "/app/hello-workload.jar"]
