"""Verify separate credentials/networks and durable DB volumes with real Compose."""
import json
from compose_support import SERVICES, TARGET, Stack


def main():
    stack = Stack("postgres")
    try:
        stack.write()
        stack.compose("up", "-d", "--wait", "--wait-timeout", "120")
        for service in SERVICES:
            stack.sql(service, "CREATE TABLE persistence_marker(id integer PRIMARY KEY); INSERT INTO persistence_marker VALUES (1)")
            failed = stack.compose("exec", "-T", "-e", "PGPASSWORD=wrong-test-password", service + "-db",
                "psql", "-h", "127.0.0.1", "-U", service, "-d", service, "-Atc", "SELECT 1", check=False)
            assert failed.returncode != 0, "PostgreSQL must reject incorrect credentials"
        cross = stack.compose("exec", "-T", "-e", "PGCONNECT_TIMEOUT=3", "auth-db", "psql", "-h", "catalog-db",
            "-U", "auth", "-d", "catalog", "-Atc", "SELECT 1", check=False)
        assert cross.returncode != 0, "DB networks must be isolated"
        stack.compose("up", "-d", "--force-recreate", "--wait", "--wait-timeout", "120")
        for service in SERVICES:
            assert stack.sql(service, "SELECT id FROM persistence_marker") == "1", "Named volume must survive container replacement"
        (TARGET / "postgres-result.json").write_text(json.dumps({"result": "PASS", "databases": list(SERVICES),
            "wrong_password": "rejected", "cross_network": "blocked", "volume_recreation": "preserved"}), encoding="utf-8")
        print("PASS: six isolated PostgreSQL databases, credentials and persistent volumes")
    finally:
        stack.close()


if __name__ == "__main__":
    main()
