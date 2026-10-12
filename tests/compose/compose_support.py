"""Isolated CI Compose projects; never operates on the developer's local stack."""
import json
import secrets
import subprocess
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ("auth", "catalog", "carrito", "orders", "notifications", "recommendation")
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
        result = self.compose("exec", "-T", service + "-db", "psql", "-U", service, "-d", service,
                              "-v", "ON_ERROR_STOP=1", "-Atc", query)
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
