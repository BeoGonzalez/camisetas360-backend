# Camisetas360 backend

Microservicios Java 21 y Spring Boot 4.1.1: auth, catalog, carrito, orders y notifications.

## Pruebas

Cada servicio es un proyecto Maven independiente. Desde su directorio:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd verify
```

JaCoCo genera `target/site/jacoco/index.html` y `jacoco.xml`. `verify` incluye Surefire y Failsafe. Estas suites de los servicios no necesitan Docker ni credenciales externas.

Desde la raíz, con Docker activo, contratos e integración RabbitMQ:

```powershell
.\orders\mvnw.cmd -B -ntp -f tests/messaging/pom.xml -Prabbit clean verify
```

E2E, reconstruyendo y verificando primero los JAR participantes:

```powershell
.\tests\e2e\run-e2e.ps1
```

## CI/CD

GitHub Actions comprueba los cinco servicios, contratos, RabbitMQ y E2E. Publica informes JUnit/JaCoCo y logs como artefactos. El despliegue existente de main necesita todas las suites aprobadas.

La validación local del 1 de octubre de 2026 pasó 216 pruebas. La primera ejecución de los workflows en GitHub queda pendiente de publicar los cambios. El despliegue AWS existente publica auth, catalog y carrito; ampliar a orders y notifications sigue pendiente de definir.

- [Workflows, comandos, artefactos y límites](docs/testing/FASE-10-CI-CD.md).
- [Matriz de pruebas](docs/testing/FASE-3-MATRIZ-PRUEBAS.md).
- [Cobertura JaCoCo](docs/testing/FASE-9-JACOCO.md).
- [E2E del checkout](docs/testing/FASE-8-E2E.md).
