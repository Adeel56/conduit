# syntax=docker/dockerfile:1
#
# Multi-stage build per docs/security/container-baseline.md:
#   - build tools (JDK + Maven) live only in the throwaway `build` stage
#   - the final image is distroless (no shell, no package manager), runs non-root,
#     and is pinned by digest
#   - the Spring Boot jar is exploded into layers so dependency layers cache across rebuilds

# ---- Stage 1: build the layered jar ---------------------------------------------------------
# Official Maven image with Temurin 21 (ADR-0006 distribution), pinned by digest. We use the
# image's Maven rather than the committed ./mvnw here so the build doesn't depend on a network
# Maven download inside a minimal base. Same Maven 3.9.9 as the wrapper.
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /workspace

# Resolve dependencies first, keyed only on the POM, so this layer is reused whenever source
# changes but dependencies don't.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# Build the jar. Tests run in CI (and need a DB); skip them here to keep the image build fast
# and hermetic.
COPY src/ src/
RUN mvn -B -ntp clean package -DskipTests

# Explode the layered jar into its discrete layers for cache-friendly COPYs below.
# `--launcher` keeps the exploded BOOT-INF + loader layout that JarLauncher runs from.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

# ---- Stage 2: minimal runtime ---------------------------------------------------------------
# Distroless Java 21, `nonroot` variant (runs as uid 65532). No shell, no package manager, so a
# code-exec foothold finds nothing to pivot with. Pinned by digest.
FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d AS runtime
WORKDIR /app

# Copy layers least-frequently-changed first, so Docker reuses the heavy dependency layers when
# only application code has changed.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

# Explicit non-root (defense in depth — the base already defaults to nonroot).
USER nonroot

EXPOSE 8080

# Launch via the Spring Boot loader (exploded-layers layout). Class moved to
# org.springframework.boot.loader.launch in Boot 3.2+.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
