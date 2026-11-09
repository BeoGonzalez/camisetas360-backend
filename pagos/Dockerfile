# ==========================================
# STAGE 1: BUILD
# ==========================================
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ==========================================
# STAGE 2: RUNTIME
# ==========================================
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --from=builder --chown=10001:10001 /workspace/target/*.jar app.jar

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
