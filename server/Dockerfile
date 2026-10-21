FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp verify
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system aether && useradd --system --gid aether aether
COPY --from=build --chown=aether:aether /build/target/aether-api-0.2.0.jar /app/api.jar
USER aether
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/api.jar"]
