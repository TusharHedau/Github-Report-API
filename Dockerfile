# ═══════════════════════════════════════════════════════════════════
#  GitHub Report API – Dockerfile
#  Multi-stage production build targeting Java 21.
# ═══════════════════════════════════════════════════════════════════

# ── Stage 1: Build ────────────────────────────────────────────────
# Use official maven image with Eclipse Temurin Java 21 to compile the app.
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy dependency descriptor first to leverage Docker layer caching
COPY pom.xml .

# Download dependencies (go-offline) to speed up subsequent rebuilds
RUN mvn dependency:go-offline -B

# Copy source directory
COPY src ./src

# Compile and package the application as an executable fat-JAR.
# Skipping tests here since they run in CI pipelines; the image build focuses purely on compilation.
RUN mvn package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────────────
# Use minimal, secure JRE 21 Alpine image for execution.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create a non-root user and group for security hardening.
# Running applications as root inside containers violates the Principle of Least Privilege.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy compiled executable JAR from the builder stage
COPY --from=builder /build/target/github-report-api-1.0.0-SNAPSHOT.jar app.jar

# Adjust ownership to the non-root user
RUN chown appuser:appgroup app.jar

# Switch execution context to the non-root user
USER appuser

# Document that Netty binds to port 8080 by default
EXPOSE 8080

# Environment variables defaults. Can be overridden during container startup.
ENV GITHUB_TOKEN=""
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

# Run the Spring Boot fat-JAR.
# Using array syntax for ENTRYPOINT ensures signals (SIGTERM/SIGINT) propagate correctly,
# allowing Graceful Shutdown of Spring Boot/Netty.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
