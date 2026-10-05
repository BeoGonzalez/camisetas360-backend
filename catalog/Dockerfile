# Build and verify with the Maven Wrapper before docker build.
# CI supplies the exact JAR that passed Surefire, Failsafe and E2E.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --chown=10001:10001 target/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
