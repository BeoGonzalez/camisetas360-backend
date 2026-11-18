# Kafka UI con login OAuth2 (Cognito y, opcionalmente, Entra ID) y RBAC: solo cuentas ADMIN.
# La construye y publica deploy/aws/04-kafka-ui.sh con la etiqueta cfg-<hash de esta carpeta>.
FROM ghcr.io/kafbat/kafka-ui:v1.3.0
COPY kafka-ui.yml entra.yml /etc/kafkaui/
