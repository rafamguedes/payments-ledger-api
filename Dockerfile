# --- build stage -----------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source so `docker build` reuses them
# across rebuilds that only touch application code.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# --- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /build/target/app.jar app.jar

EXPOSE 3000

# Virtual threads (spring.threads.virtual.enabled) do the heavy lifting for
# concurrency, so the heap doesn't need to be large — just capped sanely
# for the container's memory limit.
ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=70.0", \
  "-jar", "app.jar"]
