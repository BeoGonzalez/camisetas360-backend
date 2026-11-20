# UI for Apache Kafka (provectuslabs/kafka-ui) con login OAuth2 (Cognito y, opcionalmente, Entra ID) y
# acceso SOLO para cuentas ADMIN. La construye y publica deploy/aws/04-kafka-ui.sh con la etiqueta
# cfg-<hash de esta carpeta>.
#
# El RBAC de la UI (kafka-ui.yml) deja iniciar sesion a cualquier cuenta y solo le oculta los clusters.
# admin-gate/ agrega un filtro que responde 403 a toda sesion sin el grupo/app role ADMIN, asi que
# CUSTOMER y CATALOG_MANAGER no pueden entrar.

# Las versiones de Spring de admin-gate/pom.xml coinciden con las de la imagen (Boot 3.1.3, Java 17).
FROM maven:3.9.9-eclipse-temurin-17 AS admin-gate
WORKDIR /build
COPY admin-gate/pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY admin-gate/src src
RUN mvn -B -ntp -q package

FROM provectuslabs/kafka-ui:v0.7.2
COPY kafka-ui.yml entra.yml /etc/kafkaui/
COPY --from=admin-gate /build/target/kafka-ui-admin-gate.jar /opt/admin-gate/
# Mismo comando que la imagen base, pero con PropertiesLauncher para sumar /opt/admin-gate al classpath
# (JarLauncher no admite jars externos). La clase principal sale del Start-Class del manifiesto.
CMD ["/bin/sh", "-c", "exec java --add-opens java.rmi/javax.rmi.ssl=ALL-UNNAMED $JAVA_OPTS -Dloader.path=/opt/admin-gate -cp /kafka-ui-api.jar org.springframework.boot.loader.PropertiesLauncher"]
