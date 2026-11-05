# Builds the backend without needing Java on the host. Tests run in CI, not here.
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
COPY --from=build /src/target/payment-gateway-*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
