# The credential/metadata emulator, packaged as a container run alongside a workload container as
# a network sidecar (see libraries/workload-runtime's MetadataSidecar.kt for why). `workload run`
# starts one of these per run, on a fresh per-run bridge network shared with exactly one workload
# container, so cross-tenant traffic has no route to travel on at all.
#
# The fat jar is built by Gradle *before* this image (the publish workflow runs
# `./gradlew :images:metadata-emulator:shadowJar`) and is architecture-neutral, so a single jar
# serves every platform; only the JRE base is multi-arch. Multi-arch matters here for the same
# reason it does for hello-workload: a worker node can be an arm64 host as easily as amd64.
FROM eclipse-temurin:21-jre

COPY build/libs/metadata-emulator.jar /app/metadata-emulator.jar

ENTRYPOINT ["java", "-jar", "/app/metadata-emulator.jar"]
