# ==========================================
# ETAPA 1: Compilación (Build Stage)
# ==========================================
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Copiamos los descriptores de dependencias
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./

# Descargamos dependencias en caché para optimizar tiempos
RUN ./mvnw dependency:go-offline -B

# Copiamos el código fuente y empaquetamos el JAR ejecutable
COPY src ./src
RUN ./mvnw clean package -DskipTests

# ==========================================
# ETAPA 2: Runtime de Producción (JRE Ligero)
# ==========================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Creamos un usuario de sistema sin privilegios por seguridad
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copiamos el JAR compilado desde la etapa anterior
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", "-jar", "app.jar"]