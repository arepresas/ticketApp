# syntax=docker/dockerfile:1.7

# -----------------------------------------------------------------------------
# Stage 1: build the BFF (multi-module reactor — only `bff/` is assembled here)
# -----------------------------------------------------------------------------
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /workspace

# Cache dependencies first
COPY pom.xml ./
COPY domain/pom.xml domain/
COPY persistence/pom.xml persistence/
COPY openai-ai/pom.xml openai-ai/
COPY bff/pom.xml bff/
RUN mvn -B -ntp -pl bff -am -DskipTests dependency:go-offline

# Copy sources and build the executable jar
COPY domain domain
COPY persistence persistence
COPY openai-ai openai-ai
COPY bff bff
RUN mvn -B -ntp -pl bff -am -DskipTests package \
 && cp bff/target/bff-*.jar /workspace/bff.jar

# -----------------------------------------------------------------------------
# Stage 2: runtime — distroless base, no shell, no package manager
# -----------------------------------------------------------------------------
# Using `java-base` (not `java25` because distroless does not yet ship a
# java25 image) and providing the full JRE from the build stage. The trade-off
# is a slightly larger image (~250 MB vs ~120 MB with jlink) but no risk of
# missing JDK modules that Spring / cglib / Hibernate may pull in transitively.
FROM gcr.io/distroless/java-base:nonroot AS runtime

# Copy the entire JRE from the build stage
COPY --from=build /opt/java/openjdk /opt/jre

# Copy the executable fat-jar
COPY --from=build /workspace/bff.jar /app/bff.jar

# Run as the unprivileged "nonroot" user (uid 65532) baked into distroless
USER 65532:65532

# BFF listens on 8080 by default (see bff/src/main/resources/application.yml)
EXPOSE 8080

ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    # Default heap cap (fly.toml [env] JAVA_TOOL_OPTIONS overrides this at
    # runtime). Must be JAVA_TOOL_OPTIONS: exec-form ENTRYPOINT + no shell in
    # distroless means $JAVA_OPTS would never expand.
    JAVA_TOOL_OPTIONS=-Xmx768m

ENTRYPOINT ["/opt/jre/bin/java", "-jar", "/app/bff.jar"]
