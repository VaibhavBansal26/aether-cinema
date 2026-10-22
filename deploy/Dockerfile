FROM node:22-slim AS frontend
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY frontend ./frontend
RUN npm run build:auth
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY server/pom.xml .
COPY server/src ./src
COPY dist ./src/main/resources/static
COPY --from=frontend /build/dist/auth-client.js ./src/main/resources/static/auth-client.js
RUN mvn -B -ntp verify
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system aether && useradd --system --gid aether aether
COPY --from=build --chown=aether:aether /build/target/aether-api-0.2.0.jar /app/api.jar
USER aether
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65.0","-jar","/app/api.jar"]
