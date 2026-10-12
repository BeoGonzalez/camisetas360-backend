# Package the JAR verified by the Maven Wrapper and CI.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --chown=10001:10001 target/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]