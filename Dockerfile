# syntax=docker/dockerfile:1
#
# This IS the production deploy path. Render builds this image from `main`
# (see render.yaml). Keeping deployment as a Dockerfile rather than a
# provider-specific config is what has made three host changes cheap.

# --- build ------------------------------------------------------------------
# The Gradle wrapper provisions its own JDK (foojay resolver), so the builder
# image only needs a JDK new enough to RUN Gradle, not to compile our target.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src

# Copy the wrapper and build scripts first. These change far less often than
# source, so the dependency download below stays cached across code edits.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# --- runtime ----------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# Never run as root. The webhook makes this process publicly reachable.
RUN addgroup -S drovi && adduser -S drovi -G drovi
USER drovi

COPY --from=build --chown=drovi:drovi /src/build/libs/*.jar app.jar

EXPOSE 8080

# Tuned for Render's free instance: 512 MB and 0.1 CPU.
#
# MaxRAMPercentage is right HERE (unlike on a bare VM) because the JVM reads the
# container's cgroup limit, which is the real 512 MB — so changing plan size needs
# no rebuild. 65% leaves headroom for metaspace, thread stacks and the JVM's own
# off-heap overhead, all of which sit OUTSIDE the heap and are what actually
# trigger the OOM kill when people set this to 90 and wonder why.
#
# SerialGC: below ~2 GB, G1's background threads cost more on 0.1 CPU than its
# pause times save. TieredStopAtLevel=1 skips the C2 compiler — slower steady
# state, markedly faster startup, which is the right trade when CPU is the
# scarce resource and throughput is a handful of requests a minute.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
