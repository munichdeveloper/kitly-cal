# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/target/kitly-cal-backend.jar app.jar
USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=40s \
  CMD curl -fsS http://localhost:8080/health || exit 1

# sh -c so JAVA_OPTS from the environment is honoured; exec so the JVM receives SIGTERM.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
