# Camisetas360 backend

Microservicios Java 21 y Spring Boot 4.1.1: auth, catalog, carrito, orders y notifications.

## Backend local con un único Docker Compose

Con Docker activo, copia `.env.example` a `.env` y completa las contraseñas de
PostgreSQL y RabbitMQ. Genera los JAR con `clean verify` de cada servicio antes
de construir las imágenes; los Dockerfiles empaquetan esos JAR verificados.

```powershell
docker compose up -d --build
docker compose ps
```

`docker-compose.yml` es para desarrollo local. Incluye los cinco microservicios,
cinco PostgreSQL separados, RabbitMQ y Mailpit para recibir correos de prueba.
Todos los puertos publicados escuchan únicamente en `127.0.0.1`.

| Conexión DBeaver | Host | Puerto | Base | Usuario / contraseña |
| --- | --- | --- | --- | --- |
| Orders | localhost | 5432 | `POSTGRES_DB` del `.env` | `POSTGRES_USER` / `POSTGRES_PASSWORD` |
| Catalog | localhost | 5433 | `CATALOG_POSTGRES_DB` del `.env` | `CATALOG_POSTGRES_USER` / `CATALOG_POSTGRES_PASSWORD` |
| Auth | localhost | 5434 | `AUTH_POSTGRES_DB` del `.env` | `AUTH_POSTGRES_USER` / `AUTH_POSTGRES_PASSWORD` |
| Carrito | localhost | 5435 | `CARRITO_POSTGRES_DB` del `.env` | `CARRITO_POSTGRES_USER` / `CARRITO_POSTGRES_PASSWORD` |
| Notifications | localhost | 5436 | `NOTIFICATIONS_POSTGRES_DB` del `.env` | `NOTIFICATIONS_POSTGRES_USER` / `NOTIFICATIONS_POSTGRES_PASSWORD` |

Dentro de Docker, orders usa `postgres:5432` y catalog usa `catalog-postgres:5432`.
Auth usa `auth-postgres:5432`, carrito usa `carrito-postgres:5432` y notifications
usa `notifications-postgres:5432`. Cada instancia tiene usuario, contraseña,
volumen, red y migraciones propios. Ningún servicio consulta la base de otro.
Carrito, orders y notifications usan `rabbitmq:5672`; auth y catalog no son clientes AMQP.
La administración RabbitMQ está en http://localhost:15672 y los correos locales
en http://localhost:8025. Notifications envía por SMTP a `mailpit:1025`.

`docker compose down` conserva los datos. `docker compose down -v` elimina los volúmenes.
La configuración de staging/producción se administra por separado mediante los
manifests y Secrets externos, no con este Compose local.

Auth sincroniza perfiles identificados por issuer/subject desde JWT válidos;
Entra ID sigue validando la identidad. Carrito guarda cada solicitud de checkout
y sus items. Notifications registra los correos enviados correctamente.
Estas tablas complementan products, orders y order_items existentes.

- [Bases propias de los cinco servicios: cambios completos y verificación](docs/postgresql/BASES-POR-MICROSERVICIO.md).

## Pruebas

Cada servicio es un proyecto Maven independiente. Desde su directorio:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd verify
```

JaCoCo genera `target/site/jacoco/index.html` y `jacoco.xml`. `verify` incluye Surefire y Failsafe. Las suites de persistencia de los cinco servicios requieren Docker y crean PostgreSQL con Testcontainers, sin credenciales externas.

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

La migración PostgreSQL mantiene Flyway y ddl-auto=validate en todos los entornos. El workflow existente despliega a EC2 y ahora empaqueta JARs verificados de los cinco servicios. Los manifests EKS son una preparación separada; su despliegue requiere cluster, Secrets e imágenes reales.

- [Operación PostgreSQL, comandos y riesgos](docs/postgresql/OPERACION.md).
- [Preparación EKS](deploy/eks/README.md).

- [Auditoría, cambios completos y evidencias PostgreSQL](docs/postgresql/INFORME-MIGRACION.md).
