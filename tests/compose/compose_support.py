"""Isolated CI Compose projects; never operates on the developer's local stack."""
import json
import secrets
import subprocess
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Servicios con PostgreSQL propio (cada uno recibe su base aislada en el Stack).
SERVICES = ("auth", "catalog", "carrito", "orders", "notifications", "recommendation", "pagos")
# Servicios sin base relacional (analytics persiste en DynamoDB).
STATELESS_SERVICES = ("analytics",)
# Todos los microservicios desplegables: el mismo conjunto que la matriz de CI y el deploy a ECS.
APP_SERVICES = SERVICES + STATELESS_SERVICES
# Mismos topicos que crea kafka-init en docker-compose.yml (auto-create esta desactivado, como en AWS).
KAFKA_TOPICS = ("pago.intento-registrado", "pago.autorizado", "pago.rechazado", "pago.fraude-detectado",
                "catalogo.producto-visto", "carrito.item-agregado", "carrito.item-eliminado",
                "carrito.envio-actualizado", "recommendation.interacciones.dlt")
TARGET = ROOT / "tests/compose/target"


class Stack:
    def __init__(self, label):
        TARGET.mkdir(parents=True, exist_ok=True)
        self.project = "security-" + label + "-" + uuid.uuid4().hex[:10]
        self.file = TARGET / (self.project + ".json")
        self.log = (TARGET / (label + ".log")).open("w", encoding="utf-8")
        self.document = {"services": {}, "volumes": {}, "networks": {"bus": {}}}
        self.credentials = {}
        for service in SERVICES:
            password = secrets.token_hex(20)
            self.credentials[service] = password
            network, volume = service + "-db", service + "-data"
            self.document["networks"][network] = {"internal": True}
            self.document["volumes"][volume] = {}
            self.document["services"][network] = {
                "image": "postgres:17", "networks": [network],
                "environment": {"POSTGRES_USER": service, "POSTGRES_DB": service, "POSTGRES_PASSWORD": password,
                                "POSTGRES_INITDB_ARGS": "--auth-host=scram-sha-256"},
                "volumes": [volume + ":/var/lib/postgresql/data"],
                "healthcheck": {"test": ["CMD-SHELL", "pg_isready -U " + service + " -d " + service],
                                "interval": "2s", "timeout": "3s", "retries": 40}}

    def add_kafka(self):
        """Broker KRaft de un nodo + creacion explicita de topicos (igual que kafka-init)."""
        self.document["services"]["kafka"] = {
            "image": "apache/kafka:3.8.0", "hostname": "kafka", "networks": ["bus"],
            "environment": {
                "KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
                "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@kafka:9093",
                "KAFKA_LISTENERS": "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093",
                "KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://kafka:9092",
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
                "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER", "KAFKA_INTER_BROKER_LISTENER_NAME": "PLAINTEXT",
                "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "false", "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
                "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS": "0", "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR": "1",
                "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1"},
            "healthcheck": {"test": ["CMD-SHELL", "/opt/kafka/bin/kafka-cluster.sh cluster-id --bootstrap-server localhost:9092"],
                            "interval": "3s", "timeout": "8s", "retries": 40}}
        create = " && ".join("/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists"
                             " --topic " + topic + " --partitions 3 --replication-factor 1" for topic in KAFKA_TOPICS)
        self.document["services"]["kafka-init"] = {
            "image": "apache/kafka:3.8.0", "networks": ["bus"], "entrypoint": ["/bin/bash", "-c", create],
            "depends_on": {"kafka": {"condition": "service_healthy"}}}

    def add_dynamodb(self):
        self.document["services"]["dynamodb"] = {
            "image": "amazon/dynamodb-local:2.5.2", "networks": ["bus"],
            "command": ["-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb"]}

    def write(self):
        self.file.write_text(json.dumps(self.document, indent=2), encoding="utf-8")

    def compose(self, *args, check=True, timeout=180):
        result = subprocess.run(["docker", "compose", "-p", self.project, "-f", str(self.file), *args],
            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        self.log.write(result.stdout)
        self.log.flush()
        if check and result.returncode:
            raise RuntimeError("Compose command failed; inspect tests/compose/target logs")
        return result

    def sql(self, service, query):
        # El generador dinámico de Stack nombra cada contenedor de base de datos como [service]-db
        db_container = service + "-db"
        
        # El usuario de la base de datos es igual al nombre del servicio
        db_user = service
        
        result = self.compose("exec", "-T", db_container, "psql", "-U", db_user, "-d", service,
                            "-t", "-c", query, check=False)
        if result.returncode != 0:
            raise RuntimeError(f"SQL execution failed for {service} on container {db_container}")
        return result.stdout.strip()

    def url(self, service, port=8080):
        return "http://" + self.compose("port", service, str(port)).stdout.strip()

    def close(self):
        try:
            self.compose("logs", "--no-color", check=False)
            # Unique project created by this instance: no shared/local volumes are removed.
            self.compose("down", "--volumes", "--remove-orphans", check=False)
        finally:
            self.file.unlink(missing_ok=True)  # Contains only generated disposable test credentials.
            self.log.close()


def eventually(check, timeout=120):
    deadline = time.monotonic() + timeout
    failure = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value is not False:
                return value
        except Exception as error:
            failure = error
        time.sleep(1)
    raise AssertionError("Compose condition did not become true") from failure
