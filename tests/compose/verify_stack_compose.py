"""CI smoke regression using the exact clean-verify JARs, a signed issuer and real dependencies."""
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid
from compose_support import APP_SERVICES, ROOT, SERVICES, TARGET, Stack, eventually

# Productores y consumidores de Kafka (docs/contracts/analytics-events.md y pago-events.md).
KAFKA_CLIENTS = ("catalog", "carrito", "recommendation", "pagos", "analytics")
FRAUD_CARD = "4532999988887777"


def request(url, token=None, body=None, expected=200, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as failure:
        response = failure
    except (urllib.error.URLError, TimeoutError, ConnectionError) as network_error:
        # Convierte errores de conexión en AssertionError para que 'eventually' los atrape y reintente
        raise AssertionError("El servicio aún no está listo: " + str(network_error))
        
    with response:
        assert response.status == expected, "Unexpected HTTP status: " + str(response.status)
        content = response.read()
        return json.loads(content) if content else None


def verify_kafka_flows(stack, urls, tokens):
    """Flujos Kafka entre servicios: catalog -> analytics/recommendation y pagos -> analytics."""
    owner, admin = tokens["ownerToken"], tokens["adminToken"]

    # 1. Ver un producto publica catalogo.producto-visto (clave = usuario <tid>:<oid>).
    product = request(urls["catalog"] + "/api/v1/catalog/products/CAM-001", owner)
    assert product["sku"] == "CAM-001"

    # 2. recommendation lo registra como VISTA (peso 1) y lo copia a DynamoDB (UserInteractions).
    eventually(lambda: stack.sql("recommendation",
        "SELECT count(*) FROM interacciones WHERE sku = 'CAM-001' AND accion = 'VISTA' AND peso = 1") == "1", timeout=90)
    eventually(lambda: stack.sql("recommendation",
        "SELECT count(*) FROM interacciones WHERE dynamo_sincronizado_en IS NULL") == "0", timeout=90)

    # 3. analytics guarda el evento en DynamoDB y recalcula el perfil del mismo usuario.
    def analytics_profile():
        body = request(urls["analytics"] + "/api/v1/analytics/recomendaciones", owner)
        return body if body["generadoEn"] and "Fútbol" in body["perfil"] else False
    analytics_profile_body = eventually(analytics_profile, timeout=90)
    request(urls["analytics"] + "/api/v1/analytics/recomendaciones", expected=401)
    # El dashboard es solo para ADMIN/CATALOG_MANAGER.
    request(urls["analytics"] + "/api/v1/analytics/metricas", owner, expected=403)

    # 4. Antifraude: con 2 intentos permitidos, el 3.o con la misma tarjeta se bloquea 30 min (429 + Retry-After)
    #    y pago.fraude-detectado llega por Kafka al dashboard de analytics.
    #    pagos exige token (CUSTOMER + Checkout.Create): sin token, 401 y no cuenta como intento.
    request(urls["pagos"] + "/api/v1/pago/iniciar", body={"ordenId": str(uuid.uuid4())}, expected=401)
    for attempt in range(3):
        body = {"ordenId": str(uuid.uuid4()), "monto": 10000, "numeroTarjeta": FRAUD_CARD, "cvv": "123",
                "mesVencimiento": 12, "anioVencimiento": 2030, "nombreTitular": "QA Compose"}
        request(urls["pagos"] + "/api/v1/pago/iniciar", owner, body=body, expected=201 if attempt < 2 else 429)
    eventually(lambda: stack.sql("pagos", "SELECT count(*) FROM pago.outbox_evento "
        "WHERE event_type = 'PAGO_FRAUDE_DETECTADO' AND estado = 'PUBLICADO'") == "1", timeout=60)
    assert stack.sql("pagos", "SELECT count(*) FROM pago.outbox_evento WHERE payload LIKE '%" + FRAUD_CARD + "%'") == "0", \
        "El numero de tarjeta nunca debe salir en un evento"

    def fraud_alert():
        metrics = request(urls["analytics"] + "/api/v1/analytics/metricas", admin)
        return metrics if any(alert["tipo"] == "FRAUDE" for alert in metrics["alertas"]) else False
    metrics = eventually(fraud_alert, timeout=60)
    assert metrics["eventosPorTipo"].get("PRODUCTO_VISTO", 0) >= 1
    return analytics_profile_body


def main():
    stack = Stack("stack")
    issuer = None
    metadata_file = TARGET / (stack.project + "-issuer.json")
    try:
        wrapper = ROOT / "auth" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        subprocess.run([str(wrapper), "-f", str(ROOT / "auth/pom.xml"), "dependency:build-classpath",
            "-Dmdep.outputFile=target/compose-classpath.txt"], cwd=ROOT, stdout=stack.log, stderr=subprocess.STDOUT, check=True)
        classpath = (ROOT / "auth/target/compose-classpath.txt").read_text().strip()
        issuer = subprocess.Popen(["java", "--class-path", classpath, str(ROOT / "tests/compose/ComposeJwtIssuer.java"),
            str(metadata_file)], stdin=subprocess.PIPE, stdout=stack.log, stderr=subprocess.STDOUT, cwd=ROOT)
        eventually(lambda: metadata_file.exists() and metadata_file.stat().st_size > 0, timeout=45)
        tokens = json.loads(metadata_file.read_text())
        rabbit_password = stack.credentials["orders"]
        stack.document["services"]["rabbit"] = {"image": "rabbitmq:4-management", "networks": ["bus"],
            "environment": {"RABBITMQ_DEFAULT_USER": "qa", "RABBITMQ_DEFAULT_PASS": rabbit_password},
            "healthcheck": {"test": ["CMD", "rabbitmq-diagnostics", "-q", "ping"], "interval": "3s", "timeout": "8s", "retries": 40}}
        stack.document["services"]["smtp"] = {"image": "axllent/mailpit:v1.31.3", "networks": ["bus"], "ports": ["127.0.0.1::8025"]}
        stack.add_kafka()
        stack.add_dynamodb()
        for service in APP_SERVICES:
            assert len(list((ROOT / service / "target").glob("*-SNAPSHOT.jar"))) == 1, "Run clean verify before Compose"
            environment = {"SERVER_PORT": "8080", "JAVA_TOOL_OPTIONS": "-Xmx256m", "SPRING_PROFILES_ACTIVE": "production",
                "RABBITMQ_HOST": "rabbit", "RABBITMQ_USERNAME": "qa", "RABBITMQ_PASSWORD": rabbit_password, "RABBITMQ_VHOST": "/",
                "ENTRA_ISSUER_URI": tokens["issuer"], "ENTRA_AUDIENCE": tokens["audience"],
                "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI": tokens["jwks"],
                "MAIL_USERNAME": "qa", "MAIL_PASSWORD": "qa", "MAIL_FROM": "orders@camisetas360.test",
                "SPRING_MAIL_HOST": "smtp", "SPRING_MAIL_PORT": "1025",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH": "false", "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE": "false",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED": "false", "APP_CORS_ENABLED": "false"}
            depends_on = {"rabbit": {"condition": "service_healthy"}}
            networks = ["bus"]
            if service in SERVICES:
                environment.update({"SPRING_DATASOURCE_URL": "jdbc:postgresql://" + service + "-db:5432/" + service,
                    "SPRING_DATASOURCE_USERNAME": service, "SPRING_DATASOURCE_PASSWORD": stack.credentials[service]})
                depends_on[service + "-db"] = {"condition": "service_healthy"}
                networks.append(service + "-db")
            if service in KAFKA_CLIENTS:
                # Misma topologia que AWS: Kafka real, topicos creados antes de arrancar y auto-create apagado.
                environment["KAFKA_BOOTSTRAP_SERVERS"] = "kafka:9092"
                depends_on["kafka-init"] = {"condition": "service_completed_successfully"}
            if service in ("analytics", "recommendation"):
                environment.update({"DYNAMODB_ENDPOINT": "http://dynamodb:8000", "AWS_REGION": "us-east-1"})
                depends_on["dynamodb"] = {"condition": "service_started"}
            if service == "carrito":
                # Precios y existencia vienen de catalog (por el NLB en AWS, por la red interna aqui).
                environment["CATALOG_BASE_URL"] = "http://catalog:8080"
                depends_on["catalog"] = {"condition": "service_started"}
            if service == "pagos":
                environment.update({"ANTIFRAUDE_SECRETO_HASH": stack.credentials["pagos"], "WEBPAY_API_KEY": "api-key-de-prueba",
                    "ANTIFRAUDE_MAX_INTENTOS": "2", "ANTIFRAUDE_VENTANA_SEGUNDOS": "300"})
            stack.document["services"][service] = {"build": {"context": str(ROOT / service)}, "environment": environment,
                "networks": networks, "ports": ["127.0.0.1::8080"],
                "extra_hosts": ["host.docker.internal:host-gateway"], "depends_on": depends_on}
        stack.write()
        stack.compose("up", "-d", "--build", timeout=600)
        urls = {service: stack.url(service) for service in APP_SERVICES}
        for service in APP_SERVICES:
            health = eventually(lambda: request(urls[service] + "/actuator/health"), timeout=150)
            assert health["status"] == "UP" and set(health) <= {"status", "groups"}, service + ": health must hide details"
            assert request(urls[service] + "/actuator/health/liveness") == {"status": "UP"}, service + " liveness"
            # El NLB de AWS enruta segun readiness: debe ser publico, sin detalles y UP tras el arranque.
            assert request(urls[service] + "/actuator/health/readiness") == {"status": "UP"}, service + " readiness"
        owner = tokens["ownerToken"]
        assert request(urls["auth"] + "/api/v1/auth/profile", owner)["email"] == tokens["email"]
        request(urls["auth"] + "/api/v1/auth/profile", tokens["missingSubjectToken"], expected=401)
        request(urls["catalog"] + "/api/v1/catalog/products", owner)
        request(urls["carrito"] + "/api/v1/carrito/checkout", body={"items": [{"sku": "CAM-001", "quantity": 2, "unitPrice": 1}]}, expected=401)
        accepted = request(urls["carrito"] + "/api/v1/carrito/checkout", owner,
            # unitPrice del cliente se ignora: carrito toma precio y existencia de catalog (CAM-001 = 39990 CLP desde V5).
            {"items": [{"sku": "CAM-001", "quantity": 2, "unitPrice": 1}]}, expected=202)
        orders = eventually(lambda: request(urls["orders"] + "/api/v1/orders", owner) or False)
        order = orders[0]
        assert round(order["totalAmount"], 2) == 79980, "Total must use catalog prices: " + str(order["totalAmount"])
        assert order["userEmail"] == tokens["email"]
        request(urls["orders"] + "/api/v1/orders/" + str(order["orderId"]), tokens["foreignToken"], expected=404)
        assert request(urls["orders"] + "/api/v1/orders", tokens["foreignToken"]) == []
        request(urls["notifications"] + "/api/v1/notifications/email", owner,
            {"to": "qa@example.test", "subject": "unauthorized", "body": "test"}, expected=403)
        request(urls["recommendation"] + "/api/v1/recomendaciones", expected=401)
        eventually(lambda: request(stack.url("smtp", 8025) + "/api/v1/messages")["messages"] or False)
        assert stack.sql("orders", "SELECT count(*) FROM orders WHERE tenant_id IS NOT NULL AND object_id IS NOT NULL") == "1"
        assert stack.sql("carrito", "SELECT count(*) FROM checkout_requests WHERE tenant_id IS NOT NULL AND object_id IS NOT NULL") == "1"
        eventually(lambda: stack.sql("recommendation", "SELECT count(*) FROM recommendation_order_inbox") == "1")
        eventually(lambda: stack.sql("recommendation", "SELECT sku FROM recomendaciones WHERE posicion = 1") == "CAM-001")
        verify_kafka_flows(stack, urls, tokens)
        stack.compose("stop", "rabbit", "smtp", "kafka")
        # Las caidas de brokers no deben reiniciar tareas en ECS (health de dependencias desactivado a proposito).
        for service in APP_SERVICES:
            assert request(urls[service] + "/actuator/health/liveness")["status"] == "UP", service + " liveness sin brokers"
            assert request(urls[service] + "/actuator/health")["status"] == "UP", service + " health sin brokers"
        # Kafka es best-effort para catalog: el detalle de producto responde aunque el broker este caido.
        request(urls["catalog"] + "/api/v1/catalog/products/CAM-002", owner)
        stack.compose("start", "rabbit", "smtp", "kafka")
        eventually(lambda: stack.compose("exec", "-T", "rabbit", "rabbitmq-diagnostics", "-q", "ping", check=False).returncode == 0)
        (TARGET / "stack-result.json").write_text(json.dumps({"result": "PASS", "services": list(APP_SERVICES),
            "health": "200 redacted; liveness/readiness public", "checkout": accepted["status"],
            "ownership": "foreign identity denied", "rabbitmq_smtp": "delivered",
            "recommendation": "purchase (RabbitMQ) + view (Kafka) -> interacciones; DynamoDB copy synced",
            "analytics": "catalog view -> Kafka -> DynamoDB profile; pagos fraud -> Kafka -> dashboard alert"}), encoding="utf-8")
        print("PASS: eight service health paths, signed JWT, checkout, ownership, RabbitMQ, SMTP, Kafka and DynamoDB")
    finally:
        if issuer:
            issuer.stdin.close()
            try:
                issuer.wait(timeout=15)
            except subprocess.TimeoutExpired:
                issuer.kill()
                issuer.wait()
        metadata_file.unlink(missing_ok=True)
        stack.close()


if __name__ == "__main__":
    main()