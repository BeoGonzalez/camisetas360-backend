#!/usr/bin/env python3
"""Verifica que a UI for Apache Kafka (imagen real de deploy/ecs/kafka-ui) solo entren cuentas ADMIN.

Levanta la imagen junto a un proveedor OIDC simulado y recorre el login OAuth2 completo
(authorization code) como Cognito y como Entra ID con distintos grupos / app roles:

- grupo Cognito ADMIN o app role Entra ADMIN -> entra y ve el cluster (solo lectura)
- CUSTOMER, CATALOG_MANAGER, sin grupos o ADMIN en el claim equivocado -> 403 en la UI y en la API

Uso (repo root, Docker corriendo):
    python3 tests/kafka-ui/verify_kafka_ui_access.py              # construye la imagen y prueba
    python3 tests/kafka-ui/verify_kafka_ui_access.py --skip-build # reutiliza c360-kafka-ui-test

Las peticiones salen de un contenedor en la red de la prueba (los hosts del flujo OAuth2 son
'kafka-ui' y 'oidc'); este mismo archivo actua de cliente con --client.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
IMAGE = "c360-kafka-ui-test"
PROJECT = "c360-kafka-ui-test"
COMPOSE = ["docker", "compose", "-f", str(HERE / "docker-compose.yml"), "-p", PROJECT]

BASE = "http://kafka-ui:8080/kafka-ui"
CLUSTER = "camisetas360-kafka"


# ----------------------------------------------------------------------------------------------
# Cliente (corre dentro del contenedor python, solo biblioteca estandar)
# ----------------------------------------------------------------------------------------------
def client() -> int:
    import http.cookiejar
    import urllib.error
    import urllib.parse
    import urllib.request

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    def new_session():
        return urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())

    def fetch(opener, url, data=None, accept="application/json"):
        request = urllib.request.Request(url, data=data, headers={"Accept": accept})
        try:
            with opener.open(request, timeout=30) as response:
                return response.status, response.headers, response.read().decode()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read().decode(errors="replace")

    def wait_ready():
        opener = new_session()
        deadline = time.time() + 240
        while time.time() < deadline:
            try:
                oidc, _, _ = fetch(opener, "http://oidc:8080/cognito/.well-known/openid-configuration")
                ui, _, _ = fetch(opener, BASE + "/actuator/health")
                if oidc == 200 and ui == 200:
                    return
            except OSError:
                pass
            time.sleep(3)
        raise SystemExit("Kafka UI u OIDC no respondieron a tiempo")

    def login(provider, claims):
        """Login OAuth2 completo; devuelve la sesion (cookies) ya autenticada."""
        opener = new_session()
        status, headers, _ = fetch(opener, f"{BASE}/oauth2/authorization/{provider}", accept="text/html")
        assert status == 302, f"{provider}: inicio de login devolvio {status}"
        authorize = headers["Location"]
        assert authorize.startswith(f"http://oidc:8080/"), authorize
        form = urllib.parse.urlencode({"username": "usuario-prueba", "claims": json.dumps(claims)}).encode()
        status, headers, _ = fetch(opener, authorize, data=form, accept="text/html")
        assert status == 302, f"{provider}: el proveedor devolvio {status}"
        callback = headers["Location"]
        assert callback.startswith(BASE + "/login/oauth2/code/" + provider), callback
        status, headers, body = fetch(opener, callback, accept="text/html")
        assert status == 302 and "error" not in headers.get("Location", ""), \
            f"{provider}: callback devolvio {status} {headers.get('Location')} {body[:300]}"
        return opener

    def cognito(groups):
        claims = {"cognito:username": "usuario-prueba", "email": "usuario@example.test"}
        if groups is not None:
            claims["cognito:groups"] = groups
        return "cognito", claims

    def entra(roles, claim="roles"):
        claims = {"email": "usuario@example.test", "name": "Usuario Prueba"}
        if roles is not None:
            claims[claim] = roles
        return "azure", claims

    failures = []

    def check(name, condition, detail=""):
        print(("  ok   " if condition else "  FAIL ") + name + ("" if condition else f" -> {detail}"))
        if not condition:
            failures.append(name)

    wait_ready()
    anonymous = new_session()

    status, _, _ = fetch(anonymous, BASE + "/actuator/health")
    check("health del NLB es publico", status == 200, status)
    status, headers, _ = fetch(anonymous, BASE + "/api/clusters")
    check("sin sesion la API no responde datos", status in (302, 401), status)

    admitted = {
        "Cognito ADMIN": cognito(["ADMIN"]),
        "Cognito ADMIN + CUSTOMER": cognito(["CUSTOMER", "ADMIN"]),
        "Entra app role ADMIN": entra(["ADMIN"]),
    }
    denied = {
        "Cognito CUSTOMER": cognito(["CUSTOMER"]),
        "Cognito CATALOG_MANAGER": cognito(["CATALOG_MANAGER"]),
        "Cognito sin grupos": cognito(None),
        "Entra app role CUSTOMER": entra(["CUSTOMER"]),
        "Entra app role CATALOG_MANAGER": entra(["CATALOG_MANAGER"]),
        "Entra sin app roles": entra(None),
        "Entra con ADMIN en cognito:groups": entra(["ADMIN"], claim="cognito:groups"),
    }

    for name, (provider, claims) in admitted.items():
        session = login(provider, claims)
        status, _, body = fetch(session, BASE + "/api/clusters")
        check(f"{name}: ve el cluster", status == 200 and CLUSTER in body, f"{status} {body[:200]}")
        status, _, body = fetch(session, BASE + "/", accept="text/html")
        check(f"{name}: carga la UI", status == 200 and "Acceso denegado" not in body, status)
        status, _, _ = fetch(session, f"{BASE}/api/clusters/{CLUSTER}/topics",
                             data=b'{"name":"prueba","partitions":1}')
        check(f"{name}: el cluster es de solo lectura", status in (403, 405), status)

    for name, (provider, claims) in denied.items():
        session = login(provider, claims)
        status, _, body = fetch(session, BASE + "/api/clusters")
        check(f"{name}: la API responde 403", status == 403 and CLUSTER not in body, f"{status} {body[:200]}")
        status, _, body = fetch(session, BASE + "/", accept="text/html")
        check(f"{name}: la UI responde 403", status == 403 and "Acceso denegado" in body, status)
        status, _, _ = fetch(session, BASE + "/logout", data=b"", accept="text/html")
        check(f"{name}: puede cerrar sesion", status in (200, 302), status)

    print(f"\n{len(failures)} fallos" if failures else "\nTodas las verificaciones pasaron")
    return 1 if failures else 0


# ----------------------------------------------------------------------------------------------
# Orquestacion (host)
# ----------------------------------------------------------------------------------------------
def run(*command, check=True, **kwargs):
    return subprocess.run(list(command), check=check, **kwargs)


def main() -> int:
    if "--client" in sys.argv:
        return client()
    if "--skip-build" not in sys.argv:
        print("Construyendo la imagen de deploy/ecs/kafka-ui ...")
        run("docker", "build", "-q", "-t", IMAGE, str(ROOT / "deploy" / "ecs" / "kafka-ui"), stdout=subprocess.DEVNULL)
    run(*COMPOSE, "down", "-v", "--remove-orphans", check=False, capture_output=True)
    try:
        run(*COMPOSE, "up", "-d", "--quiet-pull")
        result = run("docker", "run", "--rm", "--network", f"{PROJECT}_default",
                     "-v", f"{HERE}:/prueba:ro", "python:3.12-alpine",
                     "python", "-u", "/prueba/verify_kafka_ui_access.py", "--client", check=False)
        if result.returncode != 0:
            run(*COMPOSE, "logs", "--tail", "80", "kafka-ui", check=False)
        return result.returncode
    finally:
        run(*COMPOSE, "down", "-v", "--remove-orphans", check=False, capture_output=True)


if __name__ == "__main__":
    sys.exit(main())
