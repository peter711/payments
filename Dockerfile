# syntax=docker/dockerfile:1
#
# One reusable multi-stage Dockerfile for every Spring Boot module in the
# reactor (payment-api, payment-processor, notification-service). The target
# module is selected with the MODULE build arg, so the three service images
# share a single definition.
#
# Build context is the repository root, because each service depends on the
# shared payments-contracts module and the whole reactor must be visible to
# Maven.
#
# Example:
#   docker build --build-arg MODULE=payment-api -t payments/payment-api .

FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /src
COPY . .
# Build only the requested module plus its intra-reactor dependencies
# (payments-contracts). Tests are skipped: they are executed in CI, not while
# producing a runnable image. The mounted Maven cache keeps dependencies between
# builds instead of re-downloading them for every module/image.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -pl "${MODULE}" -am package

# The Temurin "jre" images are jlink-trimmed and omit the jdk.random module,
# which RandomGenerator.getDefault() needs at runtime. The full JDK keeps the
# container behaviour identical to a normal local JDK 17.
FROM eclipse-temurin:17-jdk
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
