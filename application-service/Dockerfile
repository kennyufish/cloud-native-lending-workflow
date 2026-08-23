FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl application-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/application-service/target/application-service-*.jar /app/app.jar
RUN useradd --system --uid 10001 --no-create-home lending
USER 10001

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
