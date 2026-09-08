# ==========================================
# ETAPA 1: Construcción (Builder) con Maven
# ==========================================
FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app

# Copiamos el archivo de dependencias primero para aprovechar la caché de Docker
COPY pom.xml .
# Descargamos las dependencias (esto acelera futuros builds)
RUN mvn dependency:go-offline

# Copiamos el código fuente
COPY src ./src

# Compilamos el proyecto omitiendo los tests para mayor velocidad
RUN mvn clean package -DskipTests

# ==========================================
# ETAPA 2: Servidor de Producción (Runtime)
# ==========================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copiamos únicamente el archivo .jar compilado desde la Etapa 1
COPY --from=builder /app/target/*.jar app.jar

# Exponemos el puerto estándar de Spring Boot
EXPOSE 8080

# Arrancamos la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]