# deploy-backend.yml usa el valor por defecto (la misma imagen que docker-compose).
# Para produccion real conviene fijarla por digest: --build-arg RABBITMQ_BASE_IMAGE=rabbitmq@sha256:...
ARG RABBITMQ_BASE_IMAGE=rabbitmq:4-management
FROM ${RABBITMQ_BASE_IMAGE}
COPY rabbitmq.conf /etc/rabbitmq/rabbitmq.conf
