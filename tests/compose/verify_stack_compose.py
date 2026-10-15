"""CI smoke regression using the exact clean-verify JARs, a signed issuer and real dependencies."""
import json
import os
import subprocess
import urllib.error
import urllib.request
from compose_support import ROOT, SERVICES, TARGET, Stack, eventually


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
        for service in SERVICES:
            assert len(list((ROOT / service / "target").glob("*-SNAPSHOT.jar"))) == 1, "Run clean verify before Compose"
            environment = {"SERVER_PORT": "8080", "JAVA_TOOL_OPTIONS": "-Xmx256m", "SPRING_PROFILES_ACTIVE": "production",
                "SPRING_DATASOURCE_URL": "jdbc:postgresql://" + service + "-db:5432/" + service,
                "SPRING_DATASOURCE_USERNAME": service, "SPRING_DATASOURCE_PASSWORD": stack.credentials[service],
                "RABBITMQ_HOST": "rabbit", "RABBITMQ_USERNAME": "qa", "RABBITMQ_PASSWORD": rabbit_password, "RABBITMQ_VHOST": "/",
                "ENTRA_ISSUER_URI": tokens["issuer"], "ENTRA_AUDIENCE": tokens["audience"],
                "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI": tokens["jwks"],
                "MAIL_USERNAME": "qa", "MAIL_PASSWORD": "qa", "MAIL_FROM": "orders@camisetas360.test",
                "SPRING_MAIL_HOST": "smtp", "SPRING_MAIL_PORT": "1025",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH": "false", "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE": "false",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED": "false", "APP_CORS_ENABLED": "false"}
            if service == "recommendation":
                environment["SPRING_AI_MODEL_CHAT"] = "none"
                environment["SPRING_AI_MODEL_EMBEDDING"] = "none"
                environment["SPRING_AUTOCONFIGURE_EXCLUDE"] = ",".join("org.springframework.ai.model.mistralai.autoconfigure.MistralAi" + name + "AutoConfiguration"
                    for name in ("Chat", "Embedding", "Moderation", "Ocr"))
            stack.document["services"][service] = {"build": {"context": str(ROOT / service)}, "environment": environment,
                "networks": ["bus", service + "-db"], "ports": ["127.0.0.1::8080"],
                "extra_hosts": ["host.docker.internal:host-gateway"],
                "depends_on": {service + "-db": {"condition": "service_healthy"}, "rabbit": {"condition": "service_healthy"}}}
        stack.write()
        stack.compose("up", "-d", "--build", timeout=600)
        urls = {service: stack.url(service) for service in SERVICES}
        for service in SERVICES:
            health = eventually(lambda: request(urls[service] + "/actuator/health"), timeout=150)
            assert health["status"] == "UP" and set(health) <= {"status", "groups"}, "Health must hide details"
            assert request(urls[service] + "/actuator/health/liveness") == {"status": "UP"}
        owner = tokens["ownerToken"]
        assert request(urls["auth"] + "/api/v1/auth/profile", owner)["email"] == tokens["email"]
        request(urls["auth"] + "/api/v1/auth/profile", tokens["missingSubjectToken"], expected=401)
        request(urls["catalog"] + "/api/v1/catalog/products", owner)
        request(urls["carrito"] + "/api/v1/carrito/checkout", body={"items": [{"sku": "QA", "quantity": 2, "unitPrice": 10}]}, expected=401)
        accepted = request(urls["carrito"] + "/api/v1/carrito/checkout", owner,
            {"items": [{"sku": "QA", "quantity": 2, "unitPrice": 10}]}, expected=202)
        orders = eventually(lambda: request(urls["orders"] + "/api/v1/orders", owner) or False)
        order = orders[0]
        assert order["totalAmount"] == 20 and order["userEmail"] == tokens["email"]
        request(urls["orders"] + "/api/v1/orders/" + str(order["orderId"]), tokens["foreignToken"], expected=404)
        assert request(urls["orders"] + "/api/v1/orders", tokens["foreignToken"]) == []
        request(urls["notifications"] + "/api/v1/notifications/email", owner,
            {"to": "qa@example.test", "subject": "unauthorized", "body": "test"}, expected=403)
        request(urls["recommendation"] + "/api/v1/recommendations", expected=404)
        eventually(lambda: request(stack.url("smtp", 8025) + "/api/v1/messages")["messages"] or False)
        assert stack.sql("orders", "SELECT count(*) FROM orders WHERE tenant_id IS NOT NULL AND object_id IS NOT NULL") == "1"
        assert stack.sql("carrito", "SELECT count(*) FROM checkout_requests WHERE tenant_id IS NOT NULL AND object_id IS NOT NULL") == "1"
        eventually(lambda: stack.sql("recommendation", "SELECT count(*) FROM recommendation_order_inbox") == "1")
        stack.compose("stop", "rabbit", "smtp")
        for service in SERVICES:
            assert request(urls[service] + "/actuator/health/liveness")["status"] == "UP"
            assert request(urls[service] + "/actuator/health")["status"] == "UP"
        stack.compose("start", "rabbit", "smtp")
        eventually(lambda: stack.compose("exec", "-T", "rabbit", "rabbitmq-diagnostics", "-q", "ping", check=False).returncode == 0)
        (TARGET / "stack-result.json").write_text(json.dumps({"result": "PASS", "services": list(SERVICES),
            "health": "200 redacted", "checkout": accepted["status"], "ownership": "foreign identity denied",
            "rabbitmq_smtp": "delivered", "recommendation": "private worker; no business API"}), encoding="utf-8")
        print("PASS: six service health paths, signed JWT, checkout, ownership, RabbitMQ and SMTP")
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