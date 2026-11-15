# Kafka de un nodo (KRaft: broker + controller) para ECS Fargate.
# La imagen oficial genera server.properties a partir de las variables KAFKA_*; si se define alguna,
# no usa su configuracion por defecto, por eso aqui va la configuracion completa.
#
# KAFKA_ADVERTISED_LISTENERS NO se fija aqui: la inyecta deploy-backend.yml con el DNS del NLB
# (PLAINTEXT://<NLB_DNS>:9092), que es la direccion por la que llegan los clientes.
#
# El disco de Fargate es efimero: al reiniciar la tarea se pierden topics y offsets. Por eso la
# creacion automatica de topics queda activada (3 particiones), y los consumidores se reposicionan solos.
FROM apache/kafka:3.8.0

ENV KAFKA_NODE_ID=1 \
    KAFKA_PROCESS_ROLES=broker,controller \
    KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
    KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
    KAFKA_NUM_PARTITIONS=3 \
    KAFKA_LOG_DIRS=/tmp/kraft-combined-logs \
    KAFKA_HEAP_OPTS="-Xmx1g -Xms1g"

EXPOSE 9092
