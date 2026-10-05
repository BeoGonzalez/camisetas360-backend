# PostgreSQL propio para los cinco microservicios

Ampliación solicitada el 5 de octubre de 2026. Cambios aplicados y verificados localmente. Se mantiene un único docker-compose.yml para desarrollo local.

## Auditoría antes de ampliar la persistencia

Catalog y orders ya tenían JPA/PostgreSQL/Flyway. Auth devolvía los claims de perfiles desde JWT; carrito generaba checkout.requested con items; notifications enviaba correos por SMTP. Estos tres no tenían entidades, repositorios, datasource ni migraciones. Se añadieron para persistir sus operaciones existentes, tal como pide el nuevo alcance. No se añadieron bases vacías ni columnas de contraseñas, tokens, preferencias o funcionalidad comercial inexistente.

| Servicio | Base local | Puerto DBeaver | Datos propios |
| --- | --- | ---: | --- |
| orders | camisetas360 | 5432 | orders, order_items |
| catalog | camisetas360_catalog | 5433 | products |
| auth | camisetas360_auth | 5434 | user_profiles |
| carrito | camisetas360_carrito | 5435 | checkout_requests, checkout_request_items |
| notifications | camisetas360_notifications | 5436 | email_deliveries |

Los nombres pueden configurarse en .env. Cada instancia usa sus propios POSTGRES_DB/USER/PASSWORD y su propio volumen/red. Las aplicaciones reciben SPRING_DATASOURCE_*; no consultan bases de otros servicios. El acceso de DBeaver escucha exclusivamente en loopback. .env está ignorado y sus credenciales no se publican.

## Resultado de verificación

| Módulo | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| auth | 32 | 0 | 0 | 0 |
| catalog | 30 | 0 | 0 | 0 |
| carrito | 57 | 0 | 0 | 0 |
| orders | 84 | 0 | 0 | 0 |
| notifications | 47 | 0 | 0 | 0 |
| tests/messaging | 8 | 0 | 0 | 0 |
| tests/e2e | 2 | 0 | 0 | 0 |
| TOTAL | 260 | 0 | 0 | 0 |

Auth, carrito, notifications, messaging y E2E terminaron con BUILD SUCCESS en esta ampliación. Los resultados de orders/catalog son los verify verdes de la migración previa, sin cambios nuevos en sus servicios. JaCoCo HTML/XML existe para los cinco servicios.

Compose: 42 comprobaciones PASS (29 de infraestructura, 13 del stack). Las cinco bases respondieron desde el host, rechazaron password incorrecto, confirmaron commit/rollback y conservaron datos tras restart y recreación. Cada base tiene almacenamiento y red diferentes.

El stack verificó perfiles auth persistidos sin duplicación, productos de catalog, checkout de carrito confirmado, orden CREATED de orders con total 69 e items exactos, evento real order.created, aislamiento de usuarios y correo SMTP/log exactos en notifications. Los cinco esquemas usaron Flyway y Hibernate validate. Carrito, orders y notifications tuvieron conexiones AMQP autenticadas. Auth/catalog no son clientes AMQP.

El E2E con Testcontainers arranca tres PostgreSQL separados por método (carrito/orders/notifications), RabbitMQ y Mailpit; valida cada persistencia por JDBC independiente. No hay mocks de SQL, AMQP, HTTP, SMTP ni persistencia en E2E.

Evidencia local: verify-owned-postgresql.log y XML Surefire/Failsafe de los módulos; tests/compose/target/*-report.json, perfiles/checkout/orden/evento/email/log SQL; tests/e2e/target/e2e-logs con logs de los tres PostgreSQL, persistencia y respuestas reales. Los proyectos temporales fueron retirados; no se eliminaron datos locales.

## Incidencia de entorno corregida

Testcontainers inicialmente buscaba el pipe predeterminado docker_engine, pero el contexto activo de Docker Desktop usaba dockerDesktopLinuxEngine. Se ejecutó con DOCKER_HOST apuntando al endpoint consultado por Docker CLI. El script Windows E2E resuelve ese endpoint si el usuario no lo define. No se cambió PostgreSQL por un sustituto ni se deshabilitó Docker, Flyway, constraints o seguridad.

## Comandos

```powershell
# Desde la raíz, para Windows si hace falta resolver Docker Desktop:
$env:DOCKER_HOST = (docker context inspect --format '{{.Endpoints.docker.Host}}').Trim()
foreach ($service in @('auth','catalog','carrito','orders','notifications')) {
    Push-Location $service
    try {
        .\mvnw.cmd -B -ntp clean verify
        if ($LASTEXITCODE -ne 0) { throw "Falló verify: $service" }
    } finally { Pop-Location }
}
.\orders\mvnw.cmd -B -ntp -f tests/e2e/pom.xml -Pe2e clean verify
python tests/compose/verify_postgres_compose.py
python tests/compose/verify_stack_compose.py
docker compose up -d --build
```

## Límites explícitos

Entra ID sigue validando la identidad. Auth guarda el perfil validado, no contraseñas ni tokens. Los claims opcionales conservan null; un JWT sin issuer/subject se rechaza con 401 antes de persistir. Carrito guarda solicitudes de checkout, no implementa una API nueva de carrito entre sesiones. Notifications guarda envíos exitosos; no se añadió una API ni una cola de reintentos persistentes.

Un fallo de publicación revierte la solicitud/items en carrito. Un fallo SMTP revierte el registro en notifications. DB/AMQP y DB/SMTP son efectos distribuidos: una caída después del efecto externo y antes del commit puede producir inconsistencia o duplicados. No se promete outbox ni exactly-once. Double permanece en dinero y PostgreSQL usa DOUBLE PRECISION; BigDecimal/NUMERIC necesita una migración coordinada posterior.

GitHub Actions está preparado pero los cambios no se han publicado ni ejecutado remotamente. Los manifests EKS previos preparan orders; esta ampliación no despliega las otras bases/servicios en AWS. Producción requiere conexiones y secretos propios para cada servicio. El Compose es local.

## Archivo por archivo y código completo final

### `.env.example`

1. **Ruta:** [.env.example](../../.env.example).

2. **Problema actual:** Solo catalog y orders tenían PostgreSQL propio.

3. **Cambio realizado:** Añade auth/carrito/notifications con bases, usuarios, contraseñas externas, volúmenes y redes independientes; puertos localhost 5434–5436.

4. **Código completo final:**

```text
# Copy to .env (ignored) and provide local credentials. No real secrets here.
POSTGRES_DB=camisetas360
POSTGRES_USER=orders
POSTGRES_PASSWORD=
CATALOG_POSTGRES_DB=camisetas360_catalog
CATALOG_POSTGRES_USER=catalog
CATALOG_POSTGRES_PASSWORD=
RABBITMQ_USERNAME=camisetas360
RABBITMQ_PASSWORD=
RABBITMQ_VHOST=/camisetas360
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=

AUTH_POSTGRES_DB=camisetas360_auth
AUTH_POSTGRES_USER=auth
AUTH_POSTGRES_PASSWORD=

CARRITO_POSTGRES_DB=camisetas360_carrito
CARRITO_POSTGRES_USER=carrito
CARRITO_POSTGRES_PASSWORD=

NOTIFICATIONS_POSTGRES_DB=camisetas360_notifications
NOTIFICATIONS_POSTGRES_USER=notifications
NOTIFICATIONS_POSTGRES_PASSWORD=
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** 29 controles de infraestructura y 13 del flujo completo en Compose; todos PASS.

SHA-256: `5f2dcfcaf0748640537ef44c2be99424a78dd12dc69bfd1d0f4ccc533eff6ebe`

### `.github/workflows/ci.yml`

1. **Ruta:** [.github/workflows/ci.yml](../../.github/workflows/ci.yml).

2. **Problema actual:** Docker y el guard H2 se comprobaban solo para orders/catalog.

3. **Cambio realizado:** Exige Docker y ausencia de H2 para los cinco servicios; conserva suites y job Compose obligatorios.

4. **Código completo final:**

```yaml
name: Backend tests

on:
  pull_request:
  push:
    # main calls this workflow from deploy-backend.yml before deployment.
    branches-ignore: [main]
  workflow_dispatch:
  workflow_call:

permissions:
  contents: read

defaults:
  run:
    shell: bash

jobs:
  services:
    name: Test ${{ matrix.service }}
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    strategy:
      fail-fast: false
      matrix:
        service: [auth, catalog, carrito, orders, notifications]
    steps:
      - name: Checkout
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          persist-credentials: false
      - name: Set up Java 21 and Maven cache
        uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          cache-dependency-path: |
            **/pom.xml
            **/.mvn/wrapper/maven-wrapper.properties
      - name: Check Docker for PostgreSQL persistence suites
        run: docker info
      - name: Verify tests and generate JaCoCo reports
        working-directory: ${{ matrix.service }}
        run: |
          set -euo pipefail
          bash ./mvnw -B -ntp clean verify 2>&1 | tee ci-verify.log
          test -s target/site/jacoco/index.html
          test -s target/site/jacoco/jacoco.xml
      - name: Assert no embedded H2 dependency
        working-directory: ${{ matrix.service }}
        run: |
          set -euo pipefail
          bash ./mvnw -B -ntp dependency:tree -Dincludes=com.h2database:h2 -DoutputFile=target/h2-dependencies.txt
          if grep -q 'com.h2database:h2' target/h2-dependencies.txt; then
            echo 'H2 must not return to the runtime or test classpath'
            exit 1
          fi
      - name: Upload test results and coverage
        if: ${{ always() }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: reports-${{ matrix.service }}
          retention-days: 14
          if-no-files-found: warn
          path: |
            ${{ matrix.service }}/ci-verify.log
            ${{ matrix.service }}/target/surefire-reports/
            ${{ matrix.service }}/target/failsafe-reports/
            ${{ matrix.service }}/target/site/jacoco/
            ${{ matrix.service }}/target/jacoco*.exec
      - name: Upload verified JAR for E2E
        if: ${{ success() }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: jar-${{ matrix.service }}
          path: ${{ matrix.service }}/target/*.jar
          if-no-files-found: error
          retention-days: 7

  messaging:
    name: Contracts, PostgreSQL and RabbitMQ
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    steps:
      - name: Checkout
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          persist-credentials: false
      - name: Set up Java 21 and Maven cache
        uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          cache-dependency-path: |
            **/pom.xml
            **/.mvn/wrapper/maven-wrapper.properties
      - name: Check Docker
        run: docker info
      - name: Verify event contracts and RabbitMQ integration
        run: |
          set -euo pipefail
          bash orders/mvnw -B -ntp -f tests/messaging/pom.xml -Prabbit clean verify 2>&1 | tee tests/messaging/ci-verify.log
      - name: Upload messaging results
        if: ${{ always() }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: reports-messaging
          retention-days: 14
          if-no-files-found: warn
          path: |
            tests/messaging/ci-verify.log
            tests/messaging/target/surefire-reports/
            tests/messaging/target/failsafe-reports/

  e2e:
    name: Checkout E2E
    needs: services
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    steps:
      - name: Checkout
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          persist-credentials: false
      - name: Set up Java 21 and Maven cache
        uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          cache-dependency-path: |
            **/pom.xml
            **/.mvn/wrapper/maven-wrapper.properties
      - name: Download verified carrito JAR
        uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8
        with:
          name: jar-carrito
          path: carrito/target
      - name: Download verified orders JAR
        uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8
        with:
          name: jar-orders
          path: orders/target
      - name: Download verified notifications JAR
        uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8
        with:
          name: jar-notifications
          path: notifications/target
      - name: Check Docker
        run: docker info
      - name: Verify PostgreSQL persistence, checkout, SMTP delivery and ownership
        run: |
          set -euo pipefail
          bash orders/mvnw -B -ntp -f tests/e2e/pom.xml -Pe2e clean verify 2>&1 | tee tests/e2e/ci-verify.log
      - name: Upload E2E results and service logs
        if: ${{ always() }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: reports-e2e
          retention-days: 14
          if-no-files-found: warn
          path: |
            tests/e2e/ci-verify.log
            tests/e2e/target/failsafe-reports/
            tests/e2e/target/e2e-logs/

  compose:
    name: PostgreSQL and full stack with Docker Compose
    needs: services
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    steps:
      - name: Checkout
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          persist-credentials: false
      - name: Set up Java 21 and Maven cache
        uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          cache-dependency-path: |
            **/pom.xml
            **/.mvn/wrapper/maven-wrapper.properties
      - name: Download all five verified service JARs
        uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8
        with:
          pattern: jar-*
          path: verified-jars
      - name: Prepare verified runtime artifacts
        run: |
          set -euo pipefail
          for service in auth catalog carrito orders notifications; do
            mkdir -p "$service/target"
            cp "verified-jars/jar-$service/"*.jar "$service/target/"
          done
      - name: Check Docker and Compose
        run: |
          set -euo pipefail
          docker info
          docker compose version
      - name: Verify PostgreSQL isolation, authentication and persistent volumes
        run: python3 tests/compose/verify_postgres_compose.py
      - name: Verify real checkout, RabbitMQ, PostgreSQL and SMTP with Compose
        run: python3 tests/compose/verify_stack_compose.py
      - name: Upload Compose evidence
        if: ${{ always() }}
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: reports-compose
          retention-days: 14
          if-no-files-found: warn
          path: |
            tests/compose/target/*.json
            tests/compose/target/*.log

  test-gate:
    name: Backend tests passed
    if: ${{ always() }}
    needs: [services, messaging, e2e, compose]
    runs-on: ubuntu-24.04
    timeout-minutes: 5
    steps:
      - name: Require every test suite to succeed
        env:
          SERVICES_RESULT: ${{ needs.services.result }}
          MESSAGING_RESULT: ${{ needs.messaging.result }}
          E2E_RESULT: ${{ needs.e2e.result }}
          COMPOSE_RESULT: ${{ needs.compose.result }}
        run: |
          set -euo pipefail
          test "$SERVICES_RESULT" = success
          test "$MESSAGING_RESULT" = success
          test "$E2E_RESULT" = success
          test "$COMPOSE_RESULT" = success
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** YAML y gate validados; ejecución remota pendiente, no se afirma GitHub Actions verde.

SHA-256: `6dbbae9a9635019535862c86cc09ebd7ce5c738470293a607fb4f65a0ddca7ca`

### `README.md`

1. **Ruta:** [README.md](../../README.md).

2. **Problema actual:** La documentación describía dos bases.

3. **Cambio realizado:** Documenta las cinco bases, variables, tablas, comandos, DBeaver y límites de la entrega distribuida.

4. **Código completo final:**

````markdown
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
````

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Comandos locales ejecutados y snapshots SHA-256 coincidentes.

SHA-256: `afaba39b25079ebece8201431dfbf5acb382ae9efb5e061516bb01723134a955`

### `auth/pom.xml`

1. **Ruta:** [auth/pom.xml](../../auth/pom.xml).

2. **Problema actual:** El servicio no tenía driver, JPA, Flyway ni PostgreSQL Testcontainers.

3. **Cambio realizado:** Añade las mismas dependencias administradas por Spring Boot que orders, sin H2.

4. **Código completo final:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
	<groupId>com.camisetas360</groupId>
	<artifactId>auth</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name/>
	<description/>
	<url/>
	<licenses>
		<license/>
	</licenses>
	<developers>
		<developer/>
	</developers>
	<scm>
		<connection/>
		<developerConnection/>
		<tag/>
		<url/>
	</scm>
	<properties>
		<java.version>21</java.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.15</version>
				<configuration>
					<formats>
						<format>HTML</format>
						<format>XML</format>
					</formats>
				</configuration>
				<executions>
					<execution>
						<id>coverage-test-agent</id>
						<goals><goal>prepare-agent</goal></goals>
						<configuration><append>false</append></configuration>
					</execution>
					<execution>
						<id>coverage-test-report</id>
						<phase>test</phase>
						<goals><goal>report</goal></goals>
						<configuration><title>${project.artifactId} - Surefire</title></configuration>
					</execution>
					<execution>
						<id>coverage-integration-agent</id>
						<goals><goal>prepare-agent-integration</goal></goals>
						<configuration><append>false</append></configuration>
					</execution>
					<execution>
						<id>coverage-merge</id>
						<phase>post-integration-test</phase>
						<goals><goal>merge</goal></goals>
						<configuration>
							<destFile>${project.build.directory}/jacoco-merged.exec</destFile>
							<fileSets>
								<fileSet>
									<directory>${project.build.directory}</directory>
									<includes>
										<include>jacoco.exec</include>
										<include>jacoco-it.exec</include>
									</includes>
								</fileSet>
							</fileSets>
						</configuration>
					</execution>
					<execution>
						<id>coverage-combined-report</id>
						<phase>verify</phase>
						<goals><goal>report</goal></goals>
						<configuration>
							<dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
							<title>${project.artifactId} - Surefire + Failsafe</title>
						</configuration>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-failsafe-plugin</artifactId>
				<executions>
					<execution>
						<goals>
							<goal>integration-test</goal>
							<goal>verify</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>

</project>
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify con PostgreSQL real y JaCoCo.

SHA-256: `1f6f6777d10f1feeee6206fdc3e42954be2c1d0d90eae8bbfcaa947d2f53272d`

### `auth/src/main/java/com/camisetas360/auth/controller/AuthController.java`

1. **Ruta:** [auth/src/main/java/com/camisetas360/auth/controller/AuthController.java](../../auth/src/main/java/com/camisetas360/auth/controller/AuthController.java).

2. **Problema actual:** El perfil se devolvía desde claims sin persistencia.

3. **Cambio realizado:** Sincroniza el perfil con upsert PostgreSQL; conserva claims opcionales null y devuelve 401 si falta identidad estable.

4. **Código completo final:**

```java
package com.camisetas360.auth.controller;

import com.camisetas360.auth.dtos.UserProfileDTO;
import com.camisetas360.auth.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserProfileService profiles;

    public AuthController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfileDTO> getAuthenticatedUserProfile(
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(profiles.synchronize(jwt));
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** UserProfilePersistenceIT, JWT real sin subject y perfil comprometido leído por SQL en Compose.

SHA-256: `8f8c6700ae4d68f6056c7bcbd262bf5923aed6e60e44d0a3cbea2c5ea8ec869d`

### `auth/src/main/java/com/camisetas360/auth/model/UserProfile.java`

1. **Ruta:** [auth/src/main/java/com/camisetas360/auth/model/UserProfile.java](../../auth/src/main/java/com/camisetas360/auth/model/UserProfile.java).

2. **Problema actual:** Archivo nuevo: faltaba representación persistente de los datos del servicio.

3. **Cambio realizado:** Mapea las tablas propias; perfiles por issuer/subject, checkouts con items o registros de envío.

4. **Código completo final:**

```java
package com.camisetas360.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "user_profiles")
@IdClass(UserProfile.Identity.class)
public class UserProfile {
    @Id @Column(nullable = false, columnDefinition = "text")
    private String issuer;
    @Id @Column(nullable = false, columnDefinition = "text")
    private String subject;
    @Column(name = "user_id", columnDefinition = "text")
    private String userId;
    @Column(name = "tenant_id", columnDefinition = "text")
    private String tenantId;
    @Column(columnDefinition = "text")
    private String email;
    @Column(columnDefinition = "text")
    private String name;

    protected UserProfile() { }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public String getName() { return name; }

    public static class Identity implements Serializable {
        private static final long serialVersionUID = 1L;
        public String issuer;
        public String subject;
        public Identity() { }
        public Identity(String issuer, String subject) {
            this.issuer = issuer;
            this.subject = subject;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Identity identity
                    && Objects.equals(issuer, identity.issuer) && Objects.equals(subject, identity.subject);
        }
        @Override public int hashCode() { return Objects.hash(issuer, subject); }
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Tests de repositorio/persistencia sobre PostgreSQL + Flyway y verificación SQL en Compose.

SHA-256: `2f3da78bdc06e9cc8e759c67ff61efe2d0c5eb2704ea2f4dcf16ec4aef321e0d`

### `auth/src/main/java/com/camisetas360/auth/repository/UserProfileRepository.java`

1. **Ruta:** [auth/src/main/java/com/camisetas360/auth/repository/UserProfileRepository.java](../../auth/src/main/java/com/camisetas360/auth/repository/UserProfileRepository.java).

2. **Problema actual:** Archivo nuevo: faltaba representación persistente de los datos del servicio.

3. **Cambio realizado:** Mapea las tablas propias; perfiles por issuer/subject, checkouts con items o registros de envío.

4. **Código completo final:**

```java
package com.camisetas360.auth.repository;

import com.camisetas360.auth.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserProfileRepository extends JpaRepository<UserProfile, UserProfile.Identity> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_profiles (issuer, subject, user_id, tenant_id, email, name)
            VALUES (:issuer, :subject, :userId, :tenantId, :email, :name)
            ON CONFLICT (issuer, subject) DO UPDATE SET
                user_id = EXCLUDED.user_id, tenant_id = EXCLUDED.tenant_id,
                email = EXCLUDED.email, name = EXCLUDED.name
            """, nativeQuery = true)
    void upsert(@Param("issuer") String issuer, @Param("subject") String subject,
                @Param("userId") String userId, @Param("tenantId") String tenantId,
                @Param("email") String email, @Param("name") String name);
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Tests de repositorio/persistencia sobre PostgreSQL + Flyway y verificación SQL en Compose.

SHA-256: `7410370fe377e29feb709e73dd12cdf72d7f863ce91dbfd8215146b616bc7fe7`

### `auth/src/main/java/com/camisetas360/auth/service/UserProfileService.java`

1. **Ruta:** [auth/src/main/java/com/camisetas360/auth/service/UserProfileService.java](../../auth/src/main/java/com/camisetas360/auth/service/UserProfileService.java).

2. **Problema actual:** El perfil se devolvía desde claims sin persistencia.

3. **Cambio realizado:** Sincroniza el perfil con upsert PostgreSQL; conserva claims opcionales null y devuelve 401 si falta identidad estable.

4. **Código completo final:**

```java
package com.camisetas360.auth.service;

import com.camisetas360.auth.dtos.UserProfileDTO;
import com.camisetas360.auth.repository.UserProfileRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
    private final UserProfileRepository profiles;
    public UserProfileService(UserProfileRepository profiles) { this.profiles = profiles; }

    @Transactional
    public UserProfileDTO synchronize(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new InvalidBearerTokenException("El JWT debe identificar issuer y subject para persistir el perfil");
        }
        var profile = new UserProfileDTO(jwt.getClaimAsString("oid"), jwt.getClaimAsString("tid"),
                jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("name"));
        profiles.upsert(issuer, subject, profile.userId(), profile.tenantId(), profile.email(), profile.name());
        return profile;
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** UserProfilePersistenceIT, JWT real sin subject y perfil comprometido leído por SQL en Compose.

SHA-256: `f29f561c945ad39d48d948de89a053d15f0a0970de44029e6b70f7cdb9b0c202`

### `auth/src/main/resources/application.yaml`

1. **Ruta:** [auth/src/main/resources/application.yaml](../../auth/src/main/resources/application.yaml).

2. **Problema actual:** Faltaba datasource y gestión de esquema propia.

3. **Cambio realizado:** Conexión por variables externas, Flyway habilitado y ddl-auto=validate.

4. **Código completo final:**

```yaml
server:
  port: 8083

spring:

  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver

  flyway:
    enabled: true
    clean-disabled: true

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    show-sql: false

  application:
    name: auth-service

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${ENTRA_ISSUER_URI:https://login.microsoftonline.com/e5372bf0-c5e3-4286-887c-79069f209c1f/v2.0}
          audiences:
            - ${ENTRA_AUDIENCE:719c999d-0f57-4ad5-9bd9-a72be5ca07e0}

management:
  endpoints:
    web:
      exposure:
        include: health

  endpoint:
    health:
      show-details: never

app:
  cors:
    allowed-origin-patterns:
      - ${CORS_ALLOWED_ORIGIN:http://localhost:*}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Arranque del servicio, migraciones y esquema real en integración y Compose.

SHA-256: `52ece40617be04fc8fd9e50fe13a8f0a2b12336f9053a80cb4bd1268c06f89d8`

### `auth/src/main/resources/db/migration/V1__create_user_profiles.sql`

1. **Ruta:** [auth/src/main/resources/db/migration/V1__create_user_profiles.sql](../../auth/src/main/resources/db/migration/V1__create_user_profiles.sql).

2. **Problema actual:** Archivo nuevo: esta operación existente no tenía tablas persistentes.

3. **Cambio realizado:** V1 propia con únicamente sus datos, PK/FK/NOT NULL e índices requeridos por sus consultas.

4. **Código completo final:**

```sql
CREATE TABLE user_profiles (
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    user_id TEXT,
    tenant_id TEXT,
    email TEXT,
    name TEXT,
    PRIMARY KEY (issuer, subject)
);
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Integración del servicio y consultas SQL independientes en el E2E Compose.

SHA-256: `d31313d8acbdd3f3da063f692614aa5e4037daf3b036dd6dc60e42835c4dff25`

### `auth/src/test/java/com/camisetas360/auth/AuthApplicationTests.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/AuthApplicationTests.java](../../auth/src/test/java/com/camisetas360/auth/AuthApplicationTests.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.auth;

import com.camisetas360.auth.controller.AuthController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;



import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuthApplicationTests extends com.camisetas360.auth.support.PostgresTestSupport {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    // IT-CFG-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(AuthController.class)).isNotNull();

    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `8b4cb11d3e760a51465c09f492d350267a953c5775fbfe562168b291d60c8ad8`

### `auth/src/test/java/com/camisetas360/auth/controller/AuthControllerTest.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/controller/AuthControllerTest.java](../../auth/src/test/java/com/camisetas360/auth/controller/AuthControllerTest.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.auth.controller;

import com.camisetas360.auth.dtos.UserProfileDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    private final AuthController controller = new AuthController(new com.camisetas360.auth.service.UserProfileService(
            org.mockito.Mockito.mock(com.camisetas360.auth.repository.UserProfileRepository.class)));

    // UT-AUTH-001
    @Test
    void getAuthenticatedUserProfile_shouldMapClaims_whenClaimsArePresent() {
        var response = controller.getAuthenticatedUserProfile(jwtWithout(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                new UserProfileDTO("user-123", "tenant-456", "buyer@example.test", "María Pérez"));
    }

    // UT-AUTH-002
    @ParameterizedTest
    @MethodSource("missingClaims")
    void getAuthenticatedUserProfile_shouldPreserveNull_whenClaimIsMissing(
            String missingClaim, UserProfileDTO expected) {
        var response = controller.getAuthenticatedUserProfile(jwtWithout(missingClaim));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    static Stream<Arguments> missingClaims() {
        return Stream.of(
                Arguments.of("oid", new UserProfileDTO(null, "tenant-456", "buyer@example.test", "María Pérez")),
                Arguments.of("tid", new UserProfileDTO("user-123", null, "buyer@example.test", "María Pérez")),
                Arguments.of("preferred_username", new UserProfileDTO("user-123", "tenant-456", null, "María Pérez")),
                Arguments.of("name", new UserProfileDTO("user-123", "tenant-456", "buyer@example.test", null)));
    }

    private static Jwt jwtWithout(String missingClaim) {
        var claims = new HashMap<String, Object>(Map.of(
                "oid", "user-123", "tid", "tenant-456",
                "preferred_username", "buyer@example.test", "name", "María Pérez"));
        claims.remove(missingClaim);
        return Jwt.withTokenValue("unit-test-token").header("alg", "none").issuer("https://issuer.example.test").subject("user-123")
                .claims(values -> values.putAll(claims)).build();
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `edf4f80a1993fc9547e5668c11bef155a4f856f598a5469472e0265c2ddc6148`

### `auth/src/test/java/com/camisetas360/auth/controller/AuthControllerWebMvcTest.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/controller/AuthControllerWebMvcTest.java](../../auth/src/test/java/com/camisetas360/auth/controller/AuthControllerWebMvcTest.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.auth.controller;

import com.camisetas360.auth.config.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, properties = "app.cors.allowed-origin-patterns[0]=https://frontend.example.test")
@Import({SecurityConfig.class, com.camisetas360.auth.service.UserProfileService.class})
class AuthControllerWebMvcTest {

    private static final String PATH = "/api/v1/auth/profile";
    private static final String EMAIL = "buyer@example.test";
    private static final String AUTHORITY = "SCOPE_Profile.Read";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.camisetas360.auth.repository.UserProfileRepository profiles;


    // MVC-AUTH-001
    @Test
    void profile_shouldSerializeClaims_whenAuthorized() throws Exception {
        mvc.perform(request(PATH).with(authorized()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"userId":"user-1","tenantId":"tenant-1",
                         "email":"buyer@example.test","name":"Buyer"}
                        """));
    }

    // SEC-AUTH-001
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn401_whenTokenIsMissing(String path) throws Exception {
        mvc.perform(request(path)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    // SEC-AUTH-002
    @ParameterizedTest
    @MethodSource("unauthorizedAuthorities")
    void endpoint_shouldReturn403_whenRequiredAuthorityIsMissing(String path, String authority) throws Exception {
        var token = authority.isEmpty() ? jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                : jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority(authority));
        mvc.perform(request(path).with(token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.email").doesNotExist());
        
    }

    // SEC-AUTH-003
    @Test
    void security_shouldDenyUnknownRouteAndDisallowedMethod_whenJwtHasRequiredScope() throws Exception {
        mvc.perform(get("/__test_denied__").with(authorized())).andExpect(status().isForbidden());
        mvc.perform(delete(PATH).with(authorized())).andExpect(status().isForbidden());
        
    }

    // CORS-AUTH-001
    @Test
    void preflight_shouldAllowConfiguredOriginAndHeaders_withoutToken() throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", "https://frontend.example.test")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://frontend.example.test"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,OPTIONS"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Content-Type")))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        
    }

    // CORS-AUTH-001
    @Test
    void preflight_shouldRejectUnconfiguredOrigin_withoutBusinessCall() throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", "https://untrusted.example.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "ROLE_OTHER"})
    void profile_shouldReturn403_whenScopeIsValidButRoleIsNot(String role) throws Exception {
        var token = role.isEmpty() ? jwt().authorities(new SimpleGrantedAuthority(AUTHORITY))
                : jwt().authorities(new SimpleGrantedAuthority(AUTHORITY), new SimpleGrantedAuthority(role));
        mvc.perform(request(PATH).with(token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    static Stream<Arguments> unauthorizedAuthorities() {
        return protectedPaths().flatMap(path -> Stream.of(
                "", "SCOPE_Other.Read", "Profile.Read", "SCOPE_profile.read")
                .map(authority -> Arguments.of(path, authority)));
    }

    private static JwtRequestPostProcessor authorized() {
        return jwt().jwt(token -> token
                        .claim("iss", "https://issuer.example.test").subject("user-1")
                        .claim("preferred_username", EMAIL)
                        .claim("oid", "user-1").claim("tid", "tenant-1").claim("name", "Buyer"))
                .authorities(new SimpleGrantedAuthority(AUTHORITY), new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return get(path);
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `7031b4dc136ef49a221f8561f74e25d1f7a03745dee34bdf0f3a9993f7bb725e`

### `auth/src/test/java/com/camisetas360/auth/integration/UserProfilePersistenceIT.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/integration/UserProfilePersistenceIT.java](../../auth/src/test/java/com/camisetas360/auth/integration/UserProfilePersistenceIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.auth.integration;

import com.camisetas360.auth.model.UserProfile;
import com.camisetas360.auth.repository.UserProfileRepository;
import com.camisetas360.auth.service.UserProfileService;
import com.camisetas360.auth.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class UserProfilePersistenceIT extends PostgresTestSupport {
    @Autowired UserProfileService service;
    @Autowired UserProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void cleanProfiles() { profiles.deleteAll(); }

    @Test void profileIsPersistedAndUpdatedWithoutDuplicatingItsIdentity() {
        service.synchronize(token("https://issuer.example.test", "person-1", "old@example.test", "Old"));
        var response = service.synchronize(token("https://issuer.example.test", "person-1", "new@example.test", "Nuevo 東京"));
        assertThat(response.email()).isEqualTo("new@example.test");
        assertThat(profiles.count()).isEqualTo(1);
        var stored = profiles.findById(new UserProfile.Identity("https://issuer.example.test", "person-1")).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("new@example.test");
        assertThat(stored.getName()).isEqualTo("Nuevo 東京");
        assertThat(stored.getUserId()).isEqualTo("object-1");
        assertThat(stored.getTenantId()).isEqualTo("tenant-1");
        assertThat(jdbc.queryForObject("SELECT email FROM user_profiles", String.class)).isEqualTo("new@example.test");
    }

    @Test void identicalSubjectsFromDifferentIssuersHaveSeparateProfiles() {
        service.synchronize(token("https://one.example.test", "same", "one@example.test", "One"));
        service.synchronize(token("https://two.example.test", "same", "two@example.test", "Two"));
        assertThat(profiles.count()).isEqualTo(2);
        assertThat(profiles.findById(new UserProfile.Identity("https://one.example.test", "same"))
                .orElseThrow().getEmail()).isEqualTo("one@example.test");
        assertThat(profiles.findById(new UserProfile.Identity("https://two.example.test", "same"))
                .orElseThrow().getEmail()).isEqualTo("two@example.test");
    }

    @Test void optionalClaimsRemainNullInTheDatabaseAndResponse() {
        var jwt = Jwt.withTokenValue("test").header("alg", "none")
                .issuer("https://issuer.example.test").subject("person-1").build();
        var response = service.synchronize(jwt);
        assertThat(response.email()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.userId()).isNull();
        assertThat(response.tenantId()).isNull();
        var row = jdbc.queryForMap("SELECT user_id, tenant_id, email, name FROM user_profiles");
        assertThat(row.values()).containsOnlyNulls();
    }

    @Test void missingStableJwtIdentityDoesNotCreateAProfile() {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").claim("name", "Buyer").build();
        assertThatThrownBy(() -> service.synchronize(jwt))
                .isInstanceOf(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class);
        assertThat(profiles.count()).isZero();
    }

    @Test void flywayAndTheCompositePrimaryKeyAreRealPostgreSqlConstraints() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success", String.class)).isEqualTo("1");
        service.synchronize(token("https://issuer.example.test", "person-1", "buyer@example.test", "Buyer"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_profiles (issuer, subject) VALUES (?, ?)",
                "https://issuer.example.test", "person-1"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_profiles (issuer, subject) VALUES (NULL, 'invalid')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(profiles.count()).isEqualTo(1);
    }

    private static Jwt token(String issuer, String subject, String email, String name) {
        return Jwt.withTokenValue("test").header("alg", "none").issuer(issuer).subject(subject)
                .claim("oid", "object-1").claim("tid", "tenant-1")
                .claim("preferred_username", email).claim("name", name).build();
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `c8f153f909d0a6ab8931d990ca95ceb31302db2ce7a7ac248b8b7a3c844d2887`

### `auth/src/test/java/com/camisetas360/auth/security/HealthSecurityIT.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/security/HealthSecurityIT.java](../../auth/src/test/java/com/camisetas360/auth/security/HealthSecurityIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.auth.security;

import com.camisetas360.auth.config.SecurityConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicReference;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HealthSecurityIT.HealthTestApplication.class, properties = {
        "management.health.defaults.enabled=false",
        "app.cors.allowed-origin-patterns[0]=https://frontend.example.test"
})
@AutoConfigureMockMvc
class HealthSecurityIT extends com.camisetas360.auth.support.PostgresTestSupport {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @Autowired
    private AtomicReference<Health> healthState;

    // HEALTH-AUTH-001: real Actuator endpoint, infrastructure state controlled by the test.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void health_shouldBePublicWithoutDetails_whenInfrastructureIsUpOrDown(boolean up) throws Exception {
        healthState.set((up ? Health.up() : Health.down())
                .withDetail("internal", "must-not-be-exposed").build());

        mvc.perform(get("/actuator/health"))
                .andExpect(status().is(up ? 200 : 503))
                .andExpect(jsonPath("$.status").value(up ? "UP" : "DOWN"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.internal").doesNotExist())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration
    @Import(SecurityConfig.class)
    static class HealthTestApplication {
        // No component scan: no application listeners, SMTP sender or business services.
        @Bean
        AtomicReference<Health> healthState() {
            return new AtomicReference<>(Health.up().build());
        }

        @Bean
        HealthIndicator controlledHealthIndicator(AtomicReference<Health> healthState) {
            return healthState::get;
        }
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `3efb3f8531a5c1919b60e507a47f1a2b2197cd1d60162829728c200559932ad2`

### `auth/src/test/java/com/camisetas360/auth/security/JwtSecurityIT.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/security/JwtSecurityIT.java](../../auth/src/test/java/com/camisetas360/auth/security/JwtSecurityIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.auth.security;

import com.camisetas360.auth.controller.AuthController;
import com.camisetas360.auth.config.SecurityConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, com.camisetas360.auth.service.UserProfileService.class})
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtSecurityIT {

    private static final String PATH = "/api/v1/auth/profile";
    private static final String EMAIL = "buyer@example.test";
    private static final String SCOPE = "Profile.Read";
    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder decoder;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.camisetas360.auth.repository.UserProfileRepository profiles;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
        properties.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> LocalJwtIssuer.AUDIENCE);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    // JWT-AUTH-001: actual signed bearer and textual scp, without jwt() or a mocked decoder.
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldAllowSignedJwt_whenIssuerAudienceSignatureAndScopeAreValid(String path) throws Exception {
        
        assertThat(mockingDetails(decoder).isMock()).isFalse();
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token(SCOPE, "valid")))
                .andExpect(status().is(200))
                .andExpect(jsonPath("$.email").value(EMAIL)).andExpect(jsonPath("$.userId").value("user-1"));
        assertThat(ISSUER.jwksRequests()).isPositive();
    }

    // JWT-AUTH-002
    @ParameterizedTest
    @MethodSource("invalidTokens")
    void endpoint_shouldReturn401_whenTokenValidationFails(String path, String variant) throws Exception {
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token(SCOPE, variant)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.email").doesNotExist());
        verifyNoInteractions(profiles);
        
    }

    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn403_whenSignedJwtHasNoRequiredScope(String path) throws Exception {
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token("", "valid")))
                .andExpect(status().isForbidden());
        
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    static Stream<Arguments> invalidTokens() {
        return protectedPaths().flatMap(path -> Stream.of("audience", "issuer", "expired", "signature", "malformed", "missing-subject")
                .map(variant -> Arguments.of(path, variant)));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return get(path);
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `7d82438e60dcff4a03cbe782958c1a1b91b3838e01edeb076a97db6ca4721880`

### `auth/src/test/java/com/camisetas360/auth/security/LocalJwtIssuer.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/security/LocalJwtIssuer.java](../../auth/src/test/java/com/camisetas360/auth/security/LocalJwtIssuer.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only issuer keys and loopback JWKS; no external identity provider. */
final class LocalJwtIssuer implements AutoCloseable {

    static final String AUDIENCE = "camisetas360-test";
    private final HttpServer server;
    private final RSAKey trustedKey;
    private final RSAKey wrongKey;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    LocalJwtIssuer() {
        try {
            trustedKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] jwks = new JWKSet(trustedKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                try (exchange) {
                    jwksRequests.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    exchange.getResponseBody().write(jwks);
                }
            });
            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException("Cannot start test JWKS server", exception);
        }
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
    }

    String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    int jwksRequests() {
        return jwksRequests.get();
    }

    String token(String scope, String variant) throws JOSEException {
        if ("malformed".equals(variant)) {
            return "not-a-jwt";
        }
        var claims = new JWTClaimsSet.Builder()
                .issuer("issuer".equals(variant) ? issuer() + "/wrong" : issuer())
                .audience("audience".equals(variant) ? "another-api" : AUDIENCE)
                .subject("missing-subject".equals(variant) ? null : "user-1")
                .claim("oid", "user-1")
                .claim("tid", "tenant-1")
                .claim("name", "Buyer")
                .claim("preferred_username", "buyer@example.test")
                .claim("scp", scope)
                .claim("roles", java.util.List.of("CUSTOMER"))
                .issueTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .notBeforeTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse(
                        "expired".equals(variant) ? "2021-01-01T00:00:00Z" : "2100-01-01T00:00:00Z")))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(trustedKey.getKeyID()).build(), claims);
        // Same kid for the wrong key: test signature verification, not key lookup failure.
        jwt.sign(new RSASSASigner("signature".equals(variant) ? wrongKey : trustedKey));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `6a6326a7c9fcfdeb2a6c72be3ff1209fb83c7e0d05e640c60eb8e390d51cec9f`

### `auth/src/test/java/com/camisetas360/auth/support/PostgresTestSupport.java`

1. **Ruta:** [auth/src/test/java/com/camisetas360/auth/support/PostgresTestSupport.java](../../auth/src/test/java/com/camisetas360/auth/support/PostgresTestSupport.java).

2. **Problema actual:** Las pruebas de contexto no arrancaban PostgreSQL.

3. **Cambio realizado:** PostgreSQLContainer aislado por clase, propiedades reales y cierre del contexto al terminar.

4. **Código completo final:**

```java
package com.camisetas360.auth.support;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One disposable database per test class; Docker failures fail the suite. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresTestSupport {
    private static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static synchronized void databaseProperties(DynamicPropertyRegistry registry) {
        if (postgres == null || !postgres.isRunning()) {
            postgres = new PostgreSQLContainer("postgres:17")
                    .withDatabaseName("auth_test");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @AfterAll
    static synchronized void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** auth clean verify inicia PostgreSQLContainer sin disabledWithoutDocker.

SHA-256: `6dc5db4deb5912ad7eec03d3173c6fee72c343169e03ab0229f8d1b569de7481`

### `carrito/pom.xml`

1. **Ruta:** [carrito/pom.xml](../../carrito/pom.xml).

2. **Problema actual:** El servicio no tenía driver, JPA, Flyway ni PostgreSQL Testcontainers.

3. **Cambio realizado:** Añade las mismas dependencias administradas por Spring Boot que orders, sin H2.

4. **Código completo final:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <groupId>com.camisetas360</groupId>
    <artifactId>carrito</artifactId>
    <version>0.0.1-SNAPSHOT</version>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
            <scope>test</scope>
        </dependency>


        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
		<plugins>
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.15</version>
				<configuration>
					<formats>
						<format>HTML</format>
						<format>XML</format>
					</formats>
				</configuration>
				<executions>
					<execution>
						<id>coverage-test-agent</id>
						<goals><goal>prepare-agent</goal></goals>
						<configuration><append>false</append></configuration>
					</execution>
					<execution>
						<id>coverage-test-report</id>
						<phase>test</phase>
						<goals><goal>report</goal></goals>
						<configuration><title>${project.artifactId} - Surefire</title></configuration>
					</execution>
					<execution>
						<id>coverage-integration-agent</id>
						<goals><goal>prepare-agent-integration</goal></goals>
						<configuration><append>false</append></configuration>
					</execution>
					<execution>
						<id>coverage-merge</id>
						<phase>post-integration-test</phase>
						<goals><goal>merge</goal></goals>
						<configuration>
							<destFile>${project.build.directory}/jacoco-merged.exec</destFile>
							<fileSets>
								<fileSet>
									<directory>${project.build.directory}</directory>
									<includes>
										<include>jacoco.exec</include>
										<include>jacoco-it.exec</include>
									</includes>
								</fileSet>
							</fileSets>
						</configuration>
					</execution>
					<execution>
						<id>coverage-combined-report</id>
						<phase>verify</phase>
						<goals><goal>report</goal></goals>
						<configuration>
							<dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
							<title>${project.artifactId} - Surefire + Failsafe</title>
						</configuration>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-failsafe-plugin</artifactId>
				<executions>
					<execution>
						<goals>
							<goal>integration-test</goal>
							<goal>verify</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** carrito clean verify con PostgreSQL real y JaCoCo.

SHA-256: `35a5227fdefca5643edda231ab91b18590acdcfbcf0260820005e3e8df87ca60`

### `carrito/src/main/java/com/camisetas360/carrito/model/CheckoutRequest.java`

1. **Ruta:** [carrito/src/main/java/com/camisetas360/carrito/model/CheckoutRequest.java](../../carrito/src/main/java/com/camisetas360/carrito/model/CheckoutRequest.java).

2. **Problema actual:** Archivo nuevo: faltaba representación persistente de los datos del servicio.

3. **Cambio realizado:** Mapea las tablas propias; perfiles por issuer/subject, checkouts con items o registros de envío.

4. **Código completo final:**

```java
package com.camisetas360.carrito.model;

import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "checkout_requests")
public class CheckoutRequest {
    @Id private UUID id;
    @Column(name = "user_email", nullable = false) private String userEmail;
    @Column(name = "total_amount", nullable = false) private Double totalAmount;
    @Column(nullable = false) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @OneToMany(mappedBy = "checkout", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CheckoutRequestItem> items = new ArrayList<>();

    protected CheckoutRequest() { }
    public CheckoutRequest(CheckoutRequestedEvent event, double total) {
        id = event.eventId();
        userEmail = event.userEmail();
        totalAmount = total;
        status = "PROCESSING";
        createdAt = event.occurredAt();
        event.items().forEach(item -> items.add(new CheckoutRequestItem(this, item.sku(), item.quantity(), item.unitPrice())));
    }
    public UUID getId() { return id; }
    public String getUserEmail() { return userEmail; }
    public Double getTotalAmount() { return totalAmount; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public List<CheckoutRequestItem> getItems() { return List.copyOf(items); }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Tests de repositorio/persistencia sobre PostgreSQL + Flyway y verificación SQL en Compose.

SHA-256: `c809bc06efdca00633650bf9a3fb2b164b6b5a2835717a0a4fdc94c4b700e57e`

### `carrito/src/main/java/com/camisetas360/carrito/model/CheckoutRequestItem.java`

1. **Ruta:** [carrito/src/main/java/com/camisetas360/carrito/model/CheckoutRequestItem.java](../../carrito/src/main/java/com/camisetas360/carrito/model/CheckoutRequestItem.java).

2. **Problema actual:** Archivo nuevo: faltaba representación persistente de los datos del servicio.

3. **Cambio realizado:** Mapea las tablas propias; perfiles por issuer/subject, checkouts con items o registros de envío.

4. **Código completo final:**

```java
package com.camisetas360.carrito.model;

import jakarta.persistence.*;

@Entity
@Table(name = "checkout_request_items")
public class CheckoutRequestItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "checkout_id", nullable = false)
    private CheckoutRequest checkout;
    @Column(nullable = false) private String sku;
    @Column(nullable = false) private Integer quantity;
    @Column(name = "unit_price", nullable = false) private Double unitPrice;

    protected CheckoutRequestItem() { }
    public CheckoutRequestItem(CheckoutRequest checkout, String sku, int quantity, double unitPrice) {
        this.checkout = checkout;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }
    public String getSku() { return sku; }
    public Integer getQuantity() { return quantity; }
    public Double getUnitPrice() { return unitPrice; }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Tests de repositorio/persistencia sobre PostgreSQL + Flyway y verificación SQL en Compose.

SHA-256: `4424a7175d05952be6cbceb56b223eee5a1911b573a551331a4d0bc058033bda`

### `carrito/src/main/java/com/camisetas360/carrito/repository/CheckoutRequestRepository.java`

1. **Ruta:** [carrito/src/main/java/com/camisetas360/carrito/repository/CheckoutRequestRepository.java](../../carrito/src/main/java/com/camisetas360/carrito/repository/CheckoutRequestRepository.java).

2. **Problema actual:** Archivo nuevo: faltaba representación persistente de los datos del servicio.

3. **Cambio realizado:** Mapea las tablas propias; perfiles por issuer/subject, checkouts con items o registros de envío.

4. **Código completo final:**

```java
package com.camisetas360.carrito.repository;

import com.camisetas360.carrito.model.CheckoutRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutRequestRepository extends JpaRepository<CheckoutRequest, UUID> {
    @Override @EntityGraph(attributePaths = "items")
    Optional<CheckoutRequest> findById(UUID id);
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Tests de repositorio/persistencia sobre PostgreSQL + Flyway y verificación SQL en Compose.

SHA-256: `30c982bb01d6e467e7d7848c00afe771dea04f3022307524817cc2662c998517`

### `carrito/src/main/java/com/camisetas360/carrito/service/CartService.java`

1. **Ruta:** [carrito/src/main/java/com/camisetas360/carrito/service/CartService.java](../../carrito/src/main/java/com/camisetas360/carrito/service/CartService.java).

2. **Problema actual:** Checkout publicaba un evento sin guardar su propia solicitud.

3. **Cambio realizado:** Persiste y valida la solicitud/items antes de publicar; revierte la transacción si RabbitMQ falla.

4. **Código completo final:**

```java
package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.CheckoutResponseDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.carrito.messaging.event.CheckoutItemEvent;
import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.carrito.model.CheckoutRequest;
import com.camisetas360.carrito.repository.CheckoutRequestRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CartService {

        private final CheckoutEventPublisher checkoutEventPublisher;
        private final CheckoutRequestRepository checkouts;

        public CartService(CheckoutEventPublisher checkoutEventPublisher, CheckoutRequestRepository checkouts) {
                this.checkoutEventPublisher = checkoutEventPublisher;
                this.checkouts = checkouts;
        }

        @Transactional
        public CheckoutResponseDTO createOrder(OrderRequestDTO request) {

                Jwt jwt = (Jwt) SecurityContextHolder
                                .getContext()
                                .getAuthentication()
                                .getPrincipal();

                String userEmail = jwt.getClaimAsString("preferred_username");

                if (userEmail == null || userEmail.isBlank()) {
                        throw new IllegalStateException(
                                        "El token JWT no contiene el claim preferred_username");
                }

                List<CheckoutItemEvent> items = request.items()
                                .stream()
                                .map(item -> new CheckoutItemEvent(
                                                item.sku(),
                                                item.quantity(),
                                                item.unitPrice()))
                                .toList();

                CheckoutRequestedEvent event = new CheckoutRequestedEvent(
                                UUID.randomUUID(),
                                userEmail,
                                items,
                                Instant.now());

                double total = items.stream()
                                .mapToDouble(item -> item.unitPrice() * item.quantity())
                                .sum();

                // Flush real constraints before publishing. A broker exception rolls
                // this database transaction back; this is not a distributed outbox.
                checkouts.saveAndFlush(new CheckoutRequest(event, total));
                checkoutEventPublisher.publishCheckoutRequested(event);

                return new CheckoutResponseDTO(
                                event.eventId(),
                                event.userEmail(),
                                total,
                                "PROCESSING");
        }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** CheckoutPersistenceIT y dos E2E reales con PostgreSQL independiente para carrito.

SHA-256: `8db6e72ae3822ec557657a9627584f17d4e06b7d5c473b9ac79570b8371ddbd3`

### `carrito/src/main/resources/application.yaml`

1. **Ruta:** [carrito/src/main/resources/application.yaml](../../carrito/src/main/resources/application.yaml).

2. **Problema actual:** Faltaba datasource y gestión de esquema propia.

3. **Cambio realizado:** Conexión por variables externas, Flyway habilitado y ddl-auto=validate.

4. **Código completo final:**

```yaml
server:
  port: 8082

spring:

  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver

  flyway:
    enabled: true
    clean-disabled: true

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    show-sql: false

  application:
    name: carrito-service

  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:camisetas360}
    password: ${RABBITMQ_PASSWORD:camisetas360_dev}
    virtual-host: ${RABBITMQ_VHOST:/camisetas360}

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${ENTRA_ISSUER_URI:https://login.microsoftonline.com/e5372bf0-c5e3-4286-887c-79069f209c1f/v2.0}
          audiences:
            - ${ENTRA_AUDIENCE:719c999d-0f57-4ad5-9bd9-a72be5ca07e0}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Arranque del servicio, migraciones y esquema real en integración y Compose.

SHA-256: `14f8239e6d47e08778acb9db3253efd22d6b771f72b2797147e619456a990adf`

### `carrito/src/main/resources/db/migration/V1__create_checkout_requests.sql`

1. **Ruta:** [carrito/src/main/resources/db/migration/V1__create_checkout_requests.sql](../../carrito/src/main/resources/db/migration/V1__create_checkout_requests.sql).

2. **Problema actual:** Archivo nuevo: esta operación existente no tenía tablas persistentes.

3. **Cambio realizado:** V1 propia con únicamente sus datos, PK/FK/NOT NULL e índices requeridos por sus consultas.

4. **Código completo final:**

```sql
CREATE TABLE checkout_requests (
    id UUID PRIMARY KEY,
    user_email VARCHAR(255) NOT NULL,
    total_amount DOUBLE PRECISION NOT NULL,
    status VARCHAR(255) NOT NULL CHECK (status = 'PROCESSING'),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE checkout_request_items (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    checkout_id UUID NOT NULL REFERENCES checkout_requests(id),
    sku VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price DOUBLE PRECISION NOT NULL CHECK (unit_price > 0)
);

CREATE INDEX ix_checkout_request_items_checkout ON checkout_request_items(checkout_id);
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Integración del servicio y consultas SQL independientes en el E2E Compose.

SHA-256: `8f19bd2ba49281536a8df00497f2895793b95c7904dfa5f73a348c8b9541f9e8`

### `carrito/src/test/java/com/camisetas360/carrito/CarritoApplicationTests.java`

1. **Ruta:** [carrito/src/test/java/com/camisetas360/carrito/CarritoApplicationTests.java](../../carrito/src/test/java/com/camisetas360/carrito/CarritoApplicationTests.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.carrito;

import com.camisetas360.carrito.controller.CartController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;


import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
})
class CarritoApplicationTests extends com.camisetas360.carrito.support.PostgresTestSupport {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    // IT-CFG-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(CartController.class)).isNotNull();

    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** carrito clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `5ddc77d966de2a3120afad1ecec26dbc40e0c527ae1b3cf773853c0e679418e6`

### `carrito/src/test/java/com/camisetas360/carrito/integration/CheckoutPersistenceIT.java`

1. **Ruta:** [carrito/src/test/java/com/camisetas360/carrito/integration/CheckoutPersistenceIT.java](../../carrito/src/test/java/com/camisetas360/carrito/integration/CheckoutPersistenceIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.carrito.integration;

import com.camisetas360.carrito.dtos.OrderItemDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.repository.CheckoutRequestRepository;
import com.camisetas360.carrito.service.CartService;
import com.camisetas360.carrito.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
class CheckoutPersistenceIT extends PostgresTestSupport {
    @Autowired CartService service;
    @Autowired CheckoutRequestRepository checkouts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void initialize() {
        checkouts.deleteAll();
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("buyer")
                .claim("preferred_username", "buyer@example.test").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void checkoutCommitsTheExactRequestAndItsRelationships() {
        Instant before = Instant.now();
        var response = service.createOrder(request());
        assertThat(response.totalAmount()).isEqualTo(69.0);
        assertThat(response.status()).isEqualTo("PROCESSING");
        var stored = checkouts.findById(response.requestId()).orElseThrow();
        assertThat(stored.getUserEmail()).isEqualTo("buyer@example.test");
        assertThat(stored.getTotalAmount()).isEqualTo(69.0);
        assertThat(stored.getStatus()).isEqualTo("PROCESSING");
        assertThat(stored.getCreatedAt()).isBetween(before.minusNanos(1000), Instant.now());
        assertThat(stored.getItems()).extracting(item -> List.of(item.getSku(), item.getQuantity(), item.getUnitPrice()))
                .containsExactlyInAnyOrder(List.of("CAM-Ñ-東京", 2, 19.5), List.of("CAM-002", 3, 10.0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkout_request_items", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id FROM checkout_requests", UUID.class)).isEqualTo(response.requestId());
    }

    @Test void brokerFailureRollsBackTheAlreadyFlushedRequestAndItems() {
        doThrow(new AmqpException("broker unavailable")).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        assertThatThrownBy(() -> service.createOrder(request())).isInstanceOf(AmqpException.class);
        assertThat(checkouts.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkout_request_items", Integer.class)).isZero();
    }

    @Test void invalidItemFailsRealConstraintsBeforePublicationAndRollsBackTheParent() {
        var invalid = new OrderRequestDTO(List.of(new OrderItemDTO("invalid", 0, 10.0)));
        assertThatThrownBy(() -> service.createOrder(invalid)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(checkouts.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkout_request_items", Integer.class)).isZero();
        verifyNoInteractions(rabbit);
    }

    @Test void flywayCreatesTheUuidKeyForeignKeyAndRequiredFields() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='checkout_requests' AND column_name='id'", String.class)).isEqualTo("uuid");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_request_items (checkout_id,sku,quantity,unit_price) VALUES (?, 'missing', 1, 10)", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_requests (id) VALUES (?)", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='ix_checkout_request_items_checkout'", Integer.class)).isEqualTo(1);
    }

    private static OrderRequestDTO request() {
        return new OrderRequestDTO(List.of(new OrderItemDTO("CAM-Ñ-東京", 2, 19.5), new OrderItemDTO("CAM-002", 3, 10.0)));
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** carrito clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `fbf34015d4817793907a39753df7ec778d63f73b1774134dfad6fc435b94f6ce`

### `carrito/src/test/java/com/camisetas360/carrito/service/CartServiceTest.java`

1. **Ruta:** [carrito/src/test/java/com/camisetas360/carrito/service/CartServiceTest.java](../../carrito/src/test/java/com/camisetas360/carrito/service/CartServiceTest.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.OrderItemDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.carrito.messaging.event.CheckoutItemEvent;
import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CheckoutEventPublisher publisher;

    @Mock
    private com.camisetas360.carrito.repository.CheckoutRequestRepository checkouts;

    @InjectMocks
    private CartService service;

    @BeforeEach
    void clearPreviousAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // UT-CART-001
    @Test
    void createOrder_shouldPublishCheckoutRequestedEvent_whenRequestIsValid() {
        authenticate("Buyer@example.test");
        var request = new OrderRequestDTO(List.of(
                new OrderItemDTO("SKU-A", 2, 19.5),
                new OrderItemDTO("SKU-B", 3, 10.0)));

        var response = service.createOrder(request);

        var captor = ArgumentCaptor.forClass(CheckoutRequestedEvent.class);
        verify(publisher, times(1)).publishCheckoutRequested(captor.capture());
        verifyNoMoreInteractions(publisher);
        var event = captor.getValue();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.userEmail()).isEqualTo("Buyer@example.test");
        assertThat(event.items()).containsExactly(
                new CheckoutItemEvent("SKU-A", 2, 19.5),
                new CheckoutItemEvent("SKU-B", 3, 10.0));
        assertThat(response.requestId()).isEqualTo(event.eventId());
        assertThat(response.userEmail()).isEqualTo(event.userEmail());
        assertThat(response.totalAmount()).isEqualTo(69.0);
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(request.items()).containsExactly(
                new OrderItemDTO("SKU-A", 2, 19.5),
                new OrderItemDTO("SKU-B", 3, 10.0));
    }

    // UT-CART-002
    @ParameterizedTest
    @MethodSource("validTotals")
    void createOrder_shouldCalculateTotal_whenItemsHaveDifferentQuantities(
            List<OrderItemDTO> items, double expectedTotal) {
        authenticate("buyer@example.test");

        var response = service.createOrder(new OrderRequestDTO(items));

        assertThat(response.totalAmount()).isEqualTo(expectedTotal);
        verify(publisher).publishCheckoutRequested(any(CheckoutRequestedEvent.class));
        verifyNoMoreInteractions(publisher);
    }

    static Stream<Arguments> validTotals() {
        return Stream.of(
                Arguments.of(List.of(new OrderItemDTO("A", 2, 19.5),
                        new OrderItemDTO("B", 3, 10.0)), 69.0),
                Arguments.of(List.of(new OrderItemDTO("A", 1, 19.5)), 19.5),
                Arguments.of(List.of(new OrderItemDTO("A", 1, 0.01)), 0.01));
    }

    // UT-CART-003
    @Test
    void createOrder_shouldThrowException_whenPreferredUsernameIsMissing() {
        authenticate(null);

        var failure = assertThrows(IllegalStateException.class,
                () -> service.createOrder(validRequest()));

        assertThat(failure).hasMessage("El token JWT no contiene el claim preferred_username");
        verify(publisher, never()).publishCheckoutRequested(any());
    }

    // UT-CART-004
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void createOrder_shouldNotPublish_whenPreferredUsernameIsBlank(String email) {
        authenticate(email);

        assertThrows(IllegalStateException.class, () -> service.createOrder(validRequest()));

        verifyNoInteractions(publisher);
    }

    // UT-CART-005
    @Test
    void createOrder_shouldPropagateFailure_whenPublisherFails() {
        authenticate("buyer@example.test");
        var failure = new AmqpException("broker unavailable");
        doThrow(failure).when(publisher).publishCheckoutRequested(any());

        assertThat(assertThrows(AmqpException.class,
                () -> service.createOrder(validRequest()))).isSameAs(failure);

        verify(publisher, times(1)).publishCheckoutRequested(any());
        verifyNoMoreInteractions(publisher);
    }

    private static OrderRequestDTO validRequest() {
        return new OrderRequestDTO(List.of(new OrderItemDTO("SKU-A", 1, 19.5)));
    }

    private static void authenticate(String email) {
        var builder = Jwt.withTokenValue("unit-test-token")
                .header("alg", "none")
                .subject("test-user");
        if (email != null) {
            builder.claim("preferred_username", email);
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(builder.build()));
        SecurityContextHolder.setContext(context);
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** carrito clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `c4ab818c02e77bc5dd91dbeca5eff2256e33b679a071a228f3c1abcd82f0d5a2`

### `carrito/src/test/java/com/camisetas360/carrito/support/PostgresTestSupport.java`

1. **Ruta:** [carrito/src/test/java/com/camisetas360/carrito/support/PostgresTestSupport.java](../../carrito/src/test/java/com/camisetas360/carrito/support/PostgresTestSupport.java).

2. **Problema actual:** Las pruebas de contexto no arrancaban PostgreSQL.

3. **Cambio realizado:** PostgreSQLContainer aislado por clase, propiedades reales y cierre del contexto al terminar.

4. **Código completo final:**

```java
package com.camisetas360.carrito.support;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One disposable database per test class; Docker failures fail the suite. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresTestSupport {
    private static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static synchronized void databaseProperties(DynamicPropertyRegistry registry) {
        if (postgres == null || !postgres.isRunning()) {
            postgres = new PostgreSQLContainer("postgres:17")
                    .withDatabaseName("carrito_test");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @AfterAll
    static synchronized void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** carrito clean verify inicia PostgreSQLContainer sin disabledWithoutDocker.

SHA-256: `f43f2c25c8f8b07c2ad4ac8df67ee73006e4b04582d270f7581771a25a6d2d09`

### `docker-compose.yml`

1. **Ruta:** [docker-compose.yml](../../docker-compose.yml).

2. **Problema actual:** Solo catalog y orders tenían PostgreSQL propio.

3. **Cambio realizado:** Añade auth/carrito/notifications con bases, usuarios, contraseñas externas, volúmenes y redes independientes; puertos localhost 5434–5436.

4. **Código completo final:**

```yaml
name: camisetas360-local

x-rabbitmq-environment: &rabbitmq-environment
  SPRING_RABBITMQ_HOST: rabbitmq
  SPRING_RABBITMQ_PORT: 5672
  SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USERNAME:-camisetas360}
  SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-camisetas360_dev}
  SPRING_RABBITMQ_VIRTUAL_HOST: ${RABBITMQ_VHOST:-/camisetas360}

x-resource-server-environment: &resource-server-environment
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: ${ENTRA_ISSUER_URI:-https://login.microsoftonline.com/e5372bf0-c5e3-4286-887c-79069f209c1f/v2.0}
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES: ${ENTRA_AUDIENCE:-719c999d-0f57-4ad5-9bd9-a72be5ca07e0}

x-mail-environment: &mail-environment
  MAIL_USERNAME: ${MAIL_USERNAME:-local}
  MAIL_PASSWORD: ${MAIL_PASSWORD:-local}
  MAIL_FROM: ${MAIL_FROM:-orders@camisetas360.test}

services:

  postgres:
    image: postgres:17
    restart: unless-stopped
    ports:
      - "127.0.0.1:5432:5432"
    environment:
      POSTGRES_DB: ${POSTGRES_DB:?Define POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER:?Define POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?Define POSTGRES_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 5s
      timeout: 5s
      retries: 12
      start_period: 15s
    networks:
      - database-network

  catalog-postgres:
    image: postgres:17
    restart: unless-stopped
    ports:
      - "127.0.0.1:5433:5432"
    environment:
      POSTGRES_DB: ${CATALOG_POSTGRES_DB:?Define CATALOG_POSTGRES_DB}
      POSTGRES_USER: ${CATALOG_POSTGRES_USER:?Define CATALOG_POSTGRES_USER}
      POSTGRES_PASSWORD: ${CATALOG_POSTGRES_PASSWORD:?Define CATALOG_POSTGRES_PASSWORD}
    volumes:
      - catalog-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 5s
      timeout: 5s
      retries: 12
      start_period: 15s
    networks:
      - catalog-database-network

  auth-postgres:
    image: postgres:17
    restart: unless-stopped
    ports:
      - "127.0.0.1:5434:5432"
    environment:
      POSTGRES_DB: ${AUTH_POSTGRES_DB:?Define AUTH_POSTGRES_DB}
      POSTGRES_USER: ${AUTH_POSTGRES_USER:?Define AUTH_POSTGRES_USER}
      POSTGRES_PASSWORD: ${AUTH_POSTGRES_PASSWORD:?Define AUTH_POSTGRES_PASSWORD}
    volumes:
      - auth-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 5s
      timeout: 5s
      retries: 12
      start_period: 15s
    networks:
      - auth-database-network

  carrito-postgres:
    image: postgres:17
    restart: unless-stopped
    ports:
      - "127.0.0.1:5435:5432"
    environment:
      POSTGRES_DB: ${CARRITO_POSTGRES_DB:?Define CARRITO_POSTGRES_DB}
      POSTGRES_USER: ${CARRITO_POSTGRES_USER:?Define CARRITO_POSTGRES_USER}
      POSTGRES_PASSWORD: ${CARRITO_POSTGRES_PASSWORD:?Define CARRITO_POSTGRES_PASSWORD}
    volumes:
      - carrito-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 5s
      timeout: 5s
      retries: 12
      start_period: 15s
    networks:
      - carrito-database-network

  notifications-postgres:
    image: postgres:17
    restart: unless-stopped
    ports:
      - "127.0.0.1:5436:5432"
    environment:
      POSTGRES_DB: ${NOTIFICATIONS_POSTGRES_DB:?Define NOTIFICATIONS_POSTGRES_DB}
      POSTGRES_USER: ${NOTIFICATIONS_POSTGRES_USER:?Define NOTIFICATIONS_POSTGRES_USER}
      POSTGRES_PASSWORD: ${NOTIFICATIONS_POSTGRES_PASSWORD:?Define NOTIFICATIONS_POSTGRES_PASSWORD}
    volumes:
      - notifications-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 5s
      timeout: 5s
      retries: 12
      start_period: 15s
    networks:
      - notifications-database-network

  rabbitmq:
    image: rabbitmq:4-management
    container_name: camisetas360-rabbitmq
    hostname: rabbitmq
    restart: unless-stopped

    ports:
      - "127.0.0.1:5672:5672"
      - "127.0.0.1:15672:15672"

    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USERNAME:-camisetas360}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-camisetas360_dev}
      RABBITMQ_DEFAULT_VHOST: ${RABBITMQ_VHOST:-/camisetas360}

    volumes:
      - rabbitmq-data:/var/lib/rabbitmq

    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 20s

    networks:
      - app-network


  mailpit:
    image: axllent/mailpit:v1.31.3
    restart: unless-stopped
    ports:
      - "127.0.0.1:8025:8025"
    networks:
      - app-network


  auth:
    build:
      context: ./auth

    container_name: app-auth
    restart: unless-stopped

    ports:
      - "127.0.0.1:8083:8080"

    environment:
      <<: *resource-server-environment

      SERVER_PORT: 8080
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://auth-postgres:5432/${AUTH_POSTGRES_DB:?Define AUTH_POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${AUTH_POSTGRES_USER:?Define AUTH_POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${AUTH_POSTGRES_PASSWORD:?Define AUTH_POSTGRES_PASSWORD}
      CORS_ALLOWED_ORIGIN: ${CORS_ALLOWED_ORIGIN:-http://localhost:*}

    depends_on:
      auth-postgres:
        condition: service_healthy

    networks:
      - app-network
      - auth-database-network


  catalog:
    build:
      context: ./catalog

    container_name: app-catalog
    restart: unless-stopped

    ports:
      - "127.0.0.1:8081:8080"

    environment:
      <<: *resource-server-environment

      SERVER_PORT: 8080
      SPRING_PROFILES_ACTIVE: local
      CORS_ALLOWED_ORIGIN: ${CORS_ALLOWED_ORIGIN:-http://localhost:*}

      SPRING_DATASOURCE_URL: jdbc:postgresql://catalog-postgres:5432/${CATALOG_POSTGRES_DB:?Define CATALOG_POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${CATALOG_POSTGRES_USER:?Define CATALOG_POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${CATALOG_POSTGRES_PASSWORD:?Define CATALOG_POSTGRES_PASSWORD}

    depends_on:
      catalog-postgres:
        condition: service_healthy

    networks:
      - app-network
      - catalog-database-network


  carrito:
    build:
      context: ./carrito

    container_name: app-carrito
    restart: unless-stopped

    ports:
      - "127.0.0.1:8082:8080"

    environment:
      <<: [*rabbitmq-environment, *resource-server-environment]

      SERVER_PORT: 8080
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://carrito-postgres:5432/${CARRITO_POSTGRES_DB:?Define CARRITO_POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${CARRITO_POSTGRES_USER:?Define CARRITO_POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${CARRITO_POSTGRES_PASSWORD:?Define CARRITO_POSTGRES_PASSWORD}

    depends_on:
      carrito-postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

    networks:
      - app-network
      - carrito-database-network


  orders:
    build:
      context: ./orders

    container_name: app-orders
    restart: unless-stopped

    ports:
      - "127.0.0.1:8084:8080"

    environment:
      <<: [*rabbitmq-environment, *resource-server-environment]

      SERVER_PORT: 8080
      SPRING_PROFILES_ACTIVE: local

      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:?Define POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:?Define POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?Define POSTGRES_PASSWORD}

    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

    networks:
      - app-network
      - database-network


  notifications:
    build:
      context: ./notifications

    container_name: app-notifications
    restart: unless-stopped

    ports:
      - "127.0.0.1:8085:8080"

    environment:
      <<: [*rabbitmq-environment, *mail-environment, *resource-server-environment]

      SERVER_PORT: 8080
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://notifications-postgres:5432/${NOTIFICATIONS_POSTGRES_DB:?Define NOTIFICATIONS_POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${NOTIFICATIONS_POSTGRES_USER:?Define NOTIFICATIONS_POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${NOTIFICATIONS_POSTGRES_PASSWORD:?Define NOTIFICATIONS_POSTGRES_PASSWORD}
      SPRING_MAIL_HOST: mailpit
      SPRING_MAIL_PORT: 1025
      SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH: "false"
      SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE: "false"
      SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED: "false"

    depends_on:
      notifications-postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      mailpit:
        condition: service_started

    networks:
      - app-network
      - notifications-database-network


volumes:
  rabbitmq-data:
  postgres-data:
  catalog-postgres-data:
  auth-postgres-data:
  carrito-postgres-data:
  notifications-postgres-data:


networks:
  database-network:
    driver: bridge
  catalog-database-network:
    driver: bridge
  app-network:
    driver: bridge
  auth-database-network:
    driver: bridge
  carrito-database-network:
    driver: bridge
  notifications-database-network:
    driver: bridge
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** 29 controles de infraestructura y 13 del flujo completo en Compose; todos PASS.

SHA-256: `b6d1cc61588fac73de12ab4dee0c2426519ddf3af3e9cb5fdbedffb26e87ddeb`

### `docs/postgresql/OPERACION.md`

1. **Ruta:** [docs/postgresql/OPERACION.md](../../docs/postgresql/OPERACION.md).

2. **Problema actual:** La documentación describía dos bases.

3. **Cambio realizado:** Documenta las cinco bases, variables, tablas, comandos, DBeaver y límites de la entrega distribuida.

4. **Código completo final:**

````markdown
# Estrategia PostgreSQL

Java 21 / Spring Boot 4.1.1 administran las versiones de PostgreSQL JDBC, Flyway y
Testcontainers. La BOM existente usa Testcontainers 2.0.5, cuyo módulo es
`org.testcontainers:testcontainers-postgresql` y clase
`org.testcontainers.postgresql.PostgreSQLContainer`; `org.testcontainers:postgresql`
es el nombre de la línea anterior. Se conserva la versión coherente con el proyecto.
[Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/).

`spring-boot-starter-flyway` activa la integración en Boot 4;
`flyway-database-postgresql` aporta soporte específico. Tanto local como tests y
despliegues usan las mismas migraciones; Hibernate solo valida.
[Inicialización Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

## Desarrollo local

Copiar `.env.example` a `.env` y definir contraseñas locales propias. Los ejemplos
no contienen credenciales reales. Los cinco servicios usan instancias, usuarios,
contraseñas, redes y volúmenes separados. El único Compose es para desarrollo
local: publica PostgreSQL orders en `127.0.0.1:5432`, catalog en `127.0.0.1:5433`,
auth en `127.0.0.1:5434`, carrito en `127.0.0.1:5435` y notifications en
`127.0.0.1:5436` para DBeaver o Java desde el host.

Antes de construir imágenes, verificar cada JAR con Docker activo:

```powershell
foreach ($service in @('auth','catalog','carrito','orders','notifications')) {
    Push-Location $service
    try {
        .\mvnw.cmd -B -ntp clean verify
        if ($LASTEXITCODE -ne 0) { throw "Falló verify: $service" }
    } finally { Pop-Location }
}
docker compose config --quiet
docker compose up -d --build
```

El Dockerfile empaqueta el JAR ya verificado. No ejecuta Maven ni omite pruebas
dentro del build Docker; CI descarga los mismos JARs antes de construir las imágenes.
Si no existe el JAR, el build falla. Repetir verify tras modificar fuentes.

Para ejecutar Java en el host, levantar solo infraestructura con el mismo Compose:

```powershell
docker compose up -d postgres catalog-postgres rabbitmq
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/camisetas360'
$env:SPRING_DATASOURCE_USERNAME = 'orders'
# Definir SPRING_DATASOURCE_PASSWORD de forma externa, igual a POSTGRES_PASSWORD local.
# Definir también RABBITMQ_* para el broker local.
Push-Location orders
try { .\mvnw.cmd -B -ntp spring-boot:run } finally { Pop-Location }
```

Para `catalog`, usar el puerto 5433 y sus propias variables. En DBeaver seleccionar
PostgreSQL, host localhost, el puerto correspondiente y las credenciales de `.env`.
Auth usa las variables `AUTH_POSTGRES_*`, carrito `CARRITO_POSTGRES_*` y
notifications `NOTIFICATIONS_POSTGRES_*`. Los cinco reciben en ejecución
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`.
El Compose publica solo 127.0.0.1 y es exclusivo de desarrollo local. Incluye Mailpit
en http://localhost:8025 para correos de prueba; notifications usa SMTP interno
`mailpit:1025`. Los volúmenes sobreviven a `docker compose down`;
`down -v` elimina datos y no debe usarse como rutina.

## Pruebas reproducibles

```bash
cd orders
./mvnw -B -ntp clean verify
# Desde la raíz, después de verificar los JARs carrito/orders/notifications:
./orders/mvnw -B -ntp -f tests/e2e/pom.xml -Pe2e clean verify
./orders/mvnw -B -ntp -f tests/messaging/pom.xml -Prabbit clean verify
```

En PowerShell, desde `orders`: `./mvnw.cmd -B -ntp clean verify`.
Desde la raíz:

```powershell
.\orders\mvnw.cmd -B -ntp -f tests\e2e\pom.xml -Pe2e clean verify
.\orders\mvnw.cmd -B -ntp -f tests\messaging\pom.xml -Prabbit clean verify
# O reconstruir/verificar participantes y luego E2E:
.\tests\e2e\run-e2e.ps1
```

Las pruebas de repositorio y servicio abren PostgreSQL efímero por clase, sin reuse,
en puertos asignados por Docker. `@DirtiesContext` evita reutilizar un pool después
de cerrar su contenedor. Los fixtures del repositorio se aíslan por rollback; las
pruebas de transacción limpian únicamente su DB temporal. No habilitar ejecución
paralela de clases con la base estática actual sin cambiar primero su lifecycle.

En Windows, si Docker Desktop usa un named pipe distinto del predeterminado,
establecer el endpoint del contexto activo en la sesión antes de Maven:

```powershell
$env:DOCKER_HOST = (docker context inspect --format '{{.Endpoints.docker.Host}}').Trim()
```

`tests/e2e/run-e2e.ps1` lo resuelve automáticamente si DOCKER_HOST no está definido.
CI Linux sigue usando su Docker Engine local; no se guarda una ruta Windows en POMs.

El E2E crea tres PostgreSQL independientes (carrito, orders, notifications),
RabbitMQ y Mailpit por método y ejecuta los JARs reales como
procesos Java. Usa JWT firmado por un issuer local, HTTP real, AMQP real y SMTP real.
Comprueba API protegida, estado CREATED, total 69, ítems exactos, propietario,
denegación 404 sin filtración de datos al usuario ajeno y entrega del correo completo.
Una conexión JDBC independiente verifica filas comprometidas y Flyway V1–V3.
También comprueba la solicitud y sus items en carrito y el correo confirmado
en notifications, con Flyway V1 propio en cada base. El E2E Compose cubre los cinco
servicios, incluyendo el perfil persistido de auth y los productos de catalog.
La cola de captura verifica el evento `order.created` sin consumir la cola de notifications.

Evidencias E2E: `tests/e2e/target/e2e-logs`, con respuestas HTTP, email, evento,
`postgres-persistence.txt` y logs de los procesos y contenedores. Informes JUnit:
`target/surefire-reports` y `target/failsafe-reports`; JaCoCo de cada servicio:
`target/site/jacoco/index.html` y `jacoco.xml`.

## Esquema y riesgos conservados

Las nuevas bases de auth/carrito/notifications administran solo sus datos:
`user_profiles`, `checkout_requests`/`checkout_request_items` y `email_deliveries`.
Sus migraciones V1 usan los campos de las operaciones existentes. Auth no almacena
contraseñas ni tokens JWT. Las PK/FK e índices son locales a cada base.
Un fallo de RabbitMQ revierte el checkout en carrito; un fallo SMTP revierte el
registro de envío en notifications. PostgreSQL y los efectos externos no forman
una transacción distribuida: no se promete exactly-once ni entrega sin duplicados
ante una caída entre el efecto externo y el commit.

V1 crea únicamente las columnas de Order; V2 las de OrderItem y su FK; V3 indexa
historial por propietario/fecha y FK de ítems. Los campos requeridos de órdenes e
ítems ahora tienen NOT NULL en JPA y SQL; el estado admite solo los valores del enum
existente. No se inventó una constraint única de negocio ni una API paginada.
`PostgresSchemaIT` verifica SQLSTATE 23502/23503/23505/23514/22P02, rollback tras flush,
rollback del padre al fallar el ítem, tipos, timestamps con zona, precisión e índices.
Los tests existentes conservan filtros, orden cronológico, cascada y orphan removal.

Los importes siguen siendo `Double` en entidades, DTO y eventos, por lo que la
migración correcta del contrato actual es DOUBLE PRECISION, no NUMERIC encubierto.
Persiste el riesgo de redondeo binario. Propuesta futura: acordar moneda/escala,
usar BigDecimal en todos los servicios, DTO/eventos/cálculos y NUMERIC(p,s) mediante
una migración versionada y tests de redondeo. Esta tarea no altera silenciosamente
el contrato monetario. Un decimal válido en JSON no garantiza aritmética decimal exacta.

`catalog/data.sql` se trasladó a V2 de Flyway; conserva exactamente sus tres filas.
El runner Datafaker existente sigue sin generar si ya hay datos. Los tests de
repositorio limpian el seed dentro de su transacción para probar sus propios fixtures;
el smoke test sigue exigiendo las tres filas reales del arranque.

V1 supone una DB vacía. No se activa baseline automático para saltar esquemas
preexistentes. La H2 encontrada era en memoria: esta tarea migra motor/esquema y
tests, no importa datos de una instancia antigua. Si hay datos externos, respaldar,
inspeccionar y planificar su traslado antes de aplicar V1. No editar una migración
ya aplicada; agregar una versión posterior.

La persistencia del agregado usa la transacción real del repositorio. No existe
outbox: commit DB y publicación AMQP siguen siendo operaciones separadas; el E2E
verifica el camino correcto, no garantiza exactly-once ni atomicidad distribuida.
Rediseñar entrega/idempotencia requiere un alcance independiente.

## CI y entornos

CI verifica los cinco servicios, PostgreSQL Testcontainers, mensajería y E2E;
`set -euo pipefail` propaga fallos a través de tee y Failsafe verify exige éxito.
Una comprobación del árbol de dependencias impide reintroducir H2 en orders/catalog.
El gate requiere servicios, mensajería, E2E y las dos pruebas Compose del stack real.
No hay continue-on-error ni omisión de tests.
Los artefactos locales no demuestran una ejecución verde de GitHub: requiere publicar
estos cambios y observar el run remoto.

Staging y producción reciben conexión por variables o Secrets externos y validan
el esquema administrado por Flyway. Ver [EKS](../../deploy/eks/README.md) para namespaces,
PVC, Secret, red privada, imágenes pendientes y límites académicos. Los manifests
están preparados; no se ha desplegado AWS ni configurado un stack de observabilidad.
````

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Comandos locales ejecutados y snapshots SHA-256 coincidentes.

SHA-256: `33d4ac8f2c3211b9c85368b87abb03b082a52d6bacc072c3c518cd4f21c498da`

### `notifications/pom.xml`

1. **Ruta:** [notifications/pom.xml](../../notifications/pom.xml).

2. **Problema actual:** El servicio no tenía driver, JPA, Flyway ni PostgreSQL Testcontainers.

3. **Cambio realizado:** Añade las mismas dependencias administradas por Spring Boot que orders, sin H2.

4. **Código completo final:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
	<groupId>com.camisetas360</groupId>
	<artifactId>notifications</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name/>
	<description/>
	<url/>
	<licenses>
		<license/>
	</licenses>
	<developers>
		<developer/>
	</developers>
	<scm>
		<connection/>
		<developerConnection/>
		<tag/>
		<url/>
	</scm>
	<properties>
		<java.version>21</java.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-amqp</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-mail</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-amqp-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-mail-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.15</version>
				<configuration>
					<formats>
						<format>HTML</format>
						<format>XML</format>
					</formats>
				</configuration>
				<executions>
					<execution>
						<id>coverage-test-agent</id>
						<goals><goal>prepare-agent</goal></goals>
						<configuration><append>false</append></configuration>
					</execution>
					<execution>
						<id>coverage-test-report</id>
						<phase>test</phase>
						<goals><goal>report</goal></goals>
						<configuration><title>${project.artifactId} - Surefire</title></configuration>
					</execution>
					<execution>
						<id>coverage-integration-agent</id>
						<goals><goal>prepare-agent-integration</goal></goals>
						<configuration><append>false</append></configuration>
					</execution>
					<execution>
						<id>coverage-merge</id>
						<phase>post-integration-test</phase>
						<goals><goal>merge</goal></goals>
						<configuration>
							<destFile>${project.build.directory}/jacoco-merged.exec</destFile>
							<fileSets>
								<fileSet>
									<directory>${project.build.directory}</directory>
									<includes>
										<include>jacoco.exec</include>
										<include>jacoco-it.exec</include>
									</includes>
								</fileSet>
							</fileSets>
						</configuration>
					</execution>
					<execution>
						<id>coverage-combined-report</id>
						<phase>verify</phase>
						<goals><goal>report</goal></goals>
						<configuration>
							<dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
							<title>${project.artifactId} - Surefire + Failsafe</title>
						</configuration>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-failsafe-plugin</artifactId>
				<executions>
					<execution>
						<goals>
							<goal>integration-test</goal>
							<goal>verify</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>

</project>
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** notifications clean verify con PostgreSQL real y JaCoCo.

SHA-256: `fb64d6c316832edde4cca80dcca8a7528db7772e1fe8dd5b6888763989f4cb04`

### `notifications/src/main/java/com/camisetas360/notifications/model/EmailDelivery.java`

1. **Ruta:** [notifications/src/main/java/com/camisetas360/notifications/model/EmailDelivery.java](../../notifications/src/main/java/com/camisetas360/notifications/model/EmailDelivery.java).

2. **Problema actual:** Archivo nuevo: faltaba representación persistente de los datos del servicio.

3. **Cambio realizado:** Mapea las tablas propias; perfiles por issuer/subject, checkouts con items o registros de envío.

4. **Código completo final:**

```java
package com.camisetas360.notifications.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "email_deliveries")
public class EmailDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, columnDefinition = "text") private String sender;
    @Column(nullable = false, columnDefinition = "text") private String recipient;
    @Column(nullable = false, columnDefinition = "text") private String subject;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Column(name = "sent_at", nullable = false) private Instant sentAt;

    protected EmailDelivery() { }
    public EmailDelivery(String sender, String recipient, String subject, String body) {
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        sentAt = Instant.now();
    }
    public void markSent() { sentAt = Instant.now(); }
    public Long getId() { return id; }
    public String getSender() { return sender; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getSentAt() { return sentAt; }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Tests de repositorio/persistencia sobre PostgreSQL + Flyway y verificación SQL en Compose.

SHA-256: `2e9adb2e3e0bd36d508245304fa38b19ed68f2b35ad0d4f0a3b9fcf048ba7c3a`

### `notifications/src/main/java/com/camisetas360/notifications/repository/EmailDeliveryRepository.java`

1. **Ruta:** [notifications/src/main/java/com/camisetas360/notifications/repository/EmailDeliveryRepository.java](../../notifications/src/main/java/com/camisetas360/notifications/repository/EmailDeliveryRepository.java).

2. **Problema actual:** Archivo nuevo: faltaba representación persistente de los datos del servicio.

3. **Cambio realizado:** Mapea las tablas propias; perfiles por issuer/subject, checkouts con items o registros de envío.

4. **Código completo final:**

```java
package com.camisetas360.notifications.repository;

import com.camisetas360.notifications.model.EmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {
    List<EmailDelivery> findByRecipientOrderBySentAtDesc(String recipient);
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Tests de repositorio/persistencia sobre PostgreSQL + Flyway y verificación SQL en Compose.

SHA-256: `252e36416e00252a9cca1d85f6bc04ca329cc408d27605dfb9e200dca463e4ac`

### `notifications/src/main/java/com/camisetas360/notifications/service/EmailService.java`

1. **Ruta:** [notifications/src/main/java/com/camisetas360/notifications/service/EmailService.java](../../notifications/src/main/java/com/camisetas360/notifications/service/EmailService.java).

2. **Problema actual:** Los correos no tenían registro persistente propio.

3. **Cambio realizado:** Valida el registro antes de SMTP y confirma el timestamp tras el envío; SMTP fallido revierte el registro.

4. **Código completo final:**

```java
package com.camisetas360.notifications.service;

import com.camisetas360.notifications.model.EmailDelivery;
import com.camisetas360.notifications.repository.EmailDeliveryRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String from;
    private final EmailDeliveryRepository deliveries;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String from,
            EmailDeliveryRepository deliveries
    ) {
        this.mailSender = mailSender;
        this.from = from;
        this.deliveries = deliveries;
    }

    @Transactional
    public void sendEmail(
            String to,
            String subject,
            String body
    ) {

        SimpleMailMessage message =
                new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        var delivery = new EmailDelivery(from, to, subject, body);
        // Validate persistence before SMTP. SMTP exceptions roll back the log;
        // SMTP and the database are not an atomic distributed transaction.
        deliveries.saveAndFlush(delivery);
        mailSender.send(message);
        delivery.markSent();
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** EmailDeliveryPersistenceIT y correo/log exactos en Mailpit y PostgreSQL.

SHA-256: `7a9168b6efcb79dead82cea6d9827f27f25918b435856f68ad3de25b08a6033f`

### `notifications/src/main/resources/application.yaml`

1. **Ruta:** [notifications/src/main/resources/application.yaml](../../notifications/src/main/resources/application.yaml).

2. **Problema actual:** Faltaba datasource y gestión de esquema propia.

3. **Cambio realizado:** Conexión por variables externas, Flyway habilitado y ddl-auto=validate.

4. **Código completo final:**

```yaml
server:
  port: 8085

spring:

  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver

  flyway:
    enabled: true
    clean-disabled: true

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    show-sql: false

  application:
    name: notifications-service

  mail:
    host: smtp.gmail.com
    port: 587
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}

    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true

  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:camisetas360}
    password: ${RABBITMQ_PASSWORD:camisetas360_dev}
    virtual-host: ${RABBITMQ_VHOST:/camisetas360}

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${ENTRA_ISSUER_URI:https://login.microsoftonline.com/e5372bf0-c5e3-4286-887c-79069f209c1f/v2.0}
          audiences:
            - ${ENTRA_AUDIENCE:719c999d-0f57-4ad5-9bd9-a72be5ca07e0}

management:
  endpoints:
    web:
      exposure:
        include: health

  endpoint:
    health:
      show-details: never

app:
  mail:
    from: ${MAIL_FROM:${MAIL_USERNAME}}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Arranque del servicio, migraciones y esquema real en integración y Compose.

SHA-256: `a94259eb5aedd0bf70dc636bef8779b75176ba61229730ace896e26edcad7934`

### `notifications/src/main/resources/db/migration/V1__create_email_deliveries.sql`

1. **Ruta:** [notifications/src/main/resources/db/migration/V1__create_email_deliveries.sql](../../notifications/src/main/resources/db/migration/V1__create_email_deliveries.sql).

2. **Problema actual:** Archivo nuevo: esta operación existente no tenía tablas persistentes.

3. **Cambio realizado:** V1 propia con únicamente sus datos, PK/FK/NOT NULL e índices requeridos por sus consultas.

4. **Código completo final:**

```sql
CREATE TABLE email_deliveries (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sender TEXT NOT NULL,
    recipient TEXT NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    sent_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_email_deliveries_recipient_sent_at ON email_deliveries(recipient, sent_at DESC);
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Integración del servicio y consultas SQL independientes en el E2E Compose.

SHA-256: `94fe4566c46f0490c4c21cc0492f945fe7f9924b159f8c788136f82996fda260`

### `notifications/src/test/java/com/camisetas360/notifications/NotificationsApplicationTests.java`

1. **Ruta:** [notifications/src/test/java/com/camisetas360/notifications/NotificationsApplicationTests.java](../../notifications/src/test/java/com/camisetas360/notifications/NotificationsApplicationTests.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.notifications;

import com.camisetas360.notifications.controller.NotificationController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.health.mail.enabled=false",
        "MAIL_USERNAME=sender@example.test",
        "MAIL_PASSWORD=test-only",
        "MAIL_FROM=from@example.test"
})
class NotificationsApplicationTests extends com.camisetas360.notifications.support.PostgresTestSupport {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private JavaMailSender mailSender;

    // IT-CFG-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(NotificationController.class)).isNotNull();

    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** notifications clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `5a6ba3d233ea54269418eeaca7c788f467ef9febe31b61259458879561397f11`

### `notifications/src/test/java/com/camisetas360/notifications/integration/EmailConfigurationIT.java`

1. **Ruta:** [notifications/src/test/java/com/camisetas360/notifications/integration/EmailConfigurationIT.java](../../notifications/src/test/java/com/camisetas360/notifications/integration/EmailConfigurationIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.notifications.integration;

import com.camisetas360.notifications.service.EmailService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmailConfigurationIT {

    // IT-NOT-001: load the application's actual placeholder expression from its YAML.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sendEmail_shouldResolveConfiguredFrom_whenExplicitOrFallbackIsUsed(boolean explicit) {
        var sender = mock(JavaMailSender.class);
        var runner = new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    try {
                        new YamlPropertySourceLoader()
                                .load("application", new ClassPathResource("application.yaml"))
                                .forEach(sources::addLast);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                })
                .withPropertyValues("MAIL_USERNAME=fallback@example.test", "MAIL_PASSWORD=test-only")
                .withBean(JavaMailSender.class, () -> sender)
                .withBean(com.camisetas360.notifications.repository.EmailDeliveryRepository.class,
                        () -> mock(com.camisetas360.notifications.repository.EmailDeliveryRepository.class))
                .withUserConfiguration(EmailService.class);
        if (explicit) {
            runner = runner.withPropertyValues("MAIL_FROM=explicit@example.test");
        }

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(EmailService.class).sendEmail("buyer@example.test", "Order", "Created");
            var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getFrom())
                    .isEqualTo(explicit ? "explicit@example.test" : "fallback@example.test");
            assertThat(message.getValue().getTo()).containsExactly("buyer@example.test");
            verifyNoMoreInteractions(sender);
        });
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** notifications clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `b02c9e1fe07bda3dc222fbfad24d05fc1c71bb23adb824964c7b65813aed69d0`

### `notifications/src/test/java/com/camisetas360/notifications/integration/EmailDeliveryPersistenceIT.java`

1. **Ruta:** [notifications/src/test/java/com/camisetas360/notifications/integration/EmailDeliveryPersistenceIT.java](../../notifications/src/test/java/com/camisetas360/notifications/integration/EmailDeliveryPersistenceIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.notifications.integration;

import com.camisetas360.notifications.repository.EmailDeliveryRepository;
import com.camisetas360.notifications.service.EmailService;
import com.camisetas360.notifications.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.health.mail.enabled=false", "MAIL_USERNAME=test", "MAIL_PASSWORD=test",
        "MAIL_FROM=orders@camisetas360.test"
})
class EmailDeliveryPersistenceIT extends PostgresTestSupport {
    @Autowired EmailService service;
    @Autowired EmailDeliveryRepository deliveries;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender sender;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void cleanDeliveries() { deliveries.deleteAll(); }

    @Test void successfulSmtpSendCommitsTheExactDeliveryLog() {
        Instant before = Instant.now();
        service.sendEmail("buyer@example.test", "Orden creada #42", "Gracias, 東京");
        var rows = deliveries.findByRecipientOrderBySentAtDesc("buyer@example.test");
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.getSender()).isEqualTo("orders@camisetas360.test");
        assertThat(row.getRecipient()).isEqualTo("buyer@example.test");
        assertThat(row.getSubject()).isEqualTo("Orden creada #42");
        assertThat(row.getBody()).isEqualTo("Gracias, 東京");
        assertThat(row.getSentAt()).isBetween(before.minusNanos(1000), Instant.now());
        assertThat(jdbc.queryForObject("SELECT body FROM email_deliveries", String.class)).isEqualTo("Gracias, 東京");
        assertThat(deliveries.findByRecipientOrderBySentAtDesc("other@example.test")).isEmpty();
    }

    @Test void smtpFailureRollsBackTheFlushedDeliveryInsteadOfRecordingSuccess() {
        doThrow(new MailSendException("SMTP unavailable")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> service.sendEmail("buyer@example.test", "Order", "Body")).isInstanceOf(MailSendException.class);
        assertThat(deliveries.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries", Integer.class)).isZero();
    }

    @Test void invalidPersistencePreventsSmtpAndDoesNotLeaveAFalseDelivery() {
        assertThatThrownBy(() -> service.sendEmail("buyer@example.test", null, "Body"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verifyNoInteractions(sender);
        assertThat(deliveries.count()).isZero();
    }

    @Test void flywayCreatesRequiredDeliveryFieldsAndTheHistoryIndex() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='email_deliveries' AND column_name='sent_at'", String.class)).isEqualTo("timestamp with time zone");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO email_deliveries (sender,recipient,subject,body,sent_at) VALUES ('from','to',NULL,'body',now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='ix_email_deliveries_recipient_sent_at'", Integer.class)).isEqualTo(1);
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** notifications clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `8e6c6dc0ed6d5030a98635ad2d678c902c59255918572f0a6a052fba4de02146`

### `notifications/src/test/java/com/camisetas360/notifications/security/HealthSecurityIT.java`

1. **Ruta:** [notifications/src/test/java/com/camisetas360/notifications/security/HealthSecurityIT.java](../../notifications/src/test/java/com/camisetas360/notifications/security/HealthSecurityIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.notifications.security;


import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicReference;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HealthSecurityIT.HealthTestApplication.class, properties = {
        "management.health.defaults.enabled=false",
        "app.cors.allowed-origin-patterns[0]=https://frontend.example.test"
})
@AutoConfigureMockMvc
class HealthSecurityIT extends com.camisetas360.notifications.support.PostgresTestSupport {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @Autowired
    private AtomicReference<Health> healthState;

    // HEALTH-NOT-001: real Actuator endpoint, infrastructure state controlled by the test.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void health_shouldBePublicWithoutDetails_whenInfrastructureIsUpOrDown(boolean up) throws Exception {
        healthState.set((up ? Health.up() : Health.down())
                .withDetail("internal", "must-not-be-exposed").build());

        mvc.perform(get("/actuator/health"))
                .andExpect(status().is(up ? 200 : 503))
                .andExpect(jsonPath("$.status").value(up ? "UP" : "DOWN"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.internal").doesNotExist())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration(exclude = {RabbitAutoConfiguration.class, MailSenderAutoConfiguration.class})
    @Import(SecurityConfig.class)
    static class HealthTestApplication {
        // No component scan: no application listeners, SMTP sender or business services.
        @Bean
        AtomicReference<Health> healthState() {
            return new AtomicReference<>(Health.up().build());
        }

        @Bean
        HealthIndicator controlledHealthIndicator(AtomicReference<Health> healthState) {
            return healthState::get;
        }
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** notifications clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `477a8b198c9532c894f9786ffe76fce01ca81670227e0528879e2aa98405052f`

### `notifications/src/test/java/com/camisetas360/notifications/service/EmailServiceTest.java`

1. **Ruta:** [notifications/src/test/java/com/camisetas360/notifications/service/EmailServiceTest.java](../../notifications/src/test/java/com/camisetas360/notifications/service/EmailServiceTest.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.notifications.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;
    @Mock
    private com.camisetas360.notifications.repository.EmailDeliveryRepository deliveries;
    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService(mailSender, "shop@example.test", deliveries);
    }

    // UT-NOT-001
    @Test
    void sendEmail_shouldSendCompleteMessage_whenArgumentsAreValid() {
        service.sendEmail("buyer@example.test", "Orden creada #42", "Gracias por tu compra.");

        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        var message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("shop@example.test");
        assertThat(message.getTo()).containsExactly("buyer@example.test");
        assertThat(message.getSubject()).isEqualTo("Orden creada #42");
        assertThat(message.getText()).isEqualTo("Gracias por tu compra.");
        verifyNoMoreInteractions(mailSender);
    }

    // UT-NOT-002
    @Test
    void sendEmail_shouldPropagateFailure_whenMailSenderFails() {
        var failure = new MailSendException("smtp unavailable");
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(assertThrows(MailSendException.class, () ->
                service.sendEmail("buyer@example.test", "Subject", "Body"))).isSameAs(failure);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        verifyNoMoreInteractions(mailSender);
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** notifications clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `9b31f1501508a64f186833bddc1b758dc0464d78735c7a3325b501a78392e56e`

### `notifications/src/test/java/com/camisetas360/notifications/support/PostgresTestSupport.java`

1. **Ruta:** [notifications/src/test/java/com/camisetas360/notifications/support/PostgresTestSupport.java](../../notifications/src/test/java/com/camisetas360/notifications/support/PostgresTestSupport.java).

2. **Problema actual:** Las pruebas de contexto no arrancaban PostgreSQL.

3. **Cambio realizado:** PostgreSQLContainer aislado por clase, propiedades reales y cierre del contexto al terminar.

4. **Código completo final:**

```java
package com.camisetas360.notifications.support;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One disposable database per test class; Docker failures fail the suite. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresTestSupport {
    private static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static synchronized void databaseProperties(DynamicPropertyRegistry registry) {
        if (postgres == null || !postgres.isRunning()) {
            postgres = new PostgreSQLContainer("postgres:17")
                    .withDatabaseName("notifications_test");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @AfterAll
    static synchronized void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** notifications clean verify inicia PostgreSQLContainer sin disabledWithoutDocker.

SHA-256: `5377fee2cdad8cb03b40e03a293ad639b9b5f1332520ef393c7c7a790b405869`

### `tests/compose/ComposeJwtIssuer.java`

1. **Ruta:** [tests/compose/ComposeJwtIssuer.java](../../tests/compose/ComposeJwtIssuer.java).

2. **Problema actual:** La documentación describía dos bases.

3. **Cambio realizado:** Documenta las cinco bases, variables, tablas, comandos, DBeaver y límites de la entrega distribuida.

4. **Código completo final:**

```java
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Real ephemeral RS256 issuer, reachable by the Compose containers. */
public final class ComposeJwtIssuer {
    public static void main(String[] args) throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("compose-qa").generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/jwks", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
            }
        });
        server.start();
        try {
            String base = "http://host.docker.internal:" + server.getAddress().getPort();
            String issuer = base + "/issuer";
            String email = "compose-buyer@example.test";
            var metadata = Map.of("issuer", issuer, "jwks", base + "/jwks",
                    "audience", "camisetas360-compose-qa", "email", email,
                    "ownerToken", token(key, issuer, email, true),
                    "foreignToken", token(key, issuer, "compose-other@example.test", true),
                    "missingSubjectToken", token(key, issuer, email, false));
            Files.writeString(Path.of(args[0]), JsonMapper.builder().build().writeValueAsString(metadata));
            System.in.read(); // Parent closes stdin when the isolated test finishes.
        } finally {
            server.stop(0);
        }
    }

    private static String token(RSAKey key, String issuer, String email, boolean includeSubject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("camisetas360-compose-qa")
                .subject(includeSubject ? email : null).claim("preferred_username", email).claim("name", "Compose QA Buyer")
                .claim("oid", "compose-user").claim("tid", "compose-tenant")
                .claim("scp", "Checkout.Create Orders.Read Profile.Read Catalog.Read")
                .claim("roles", List.of("CUSTOMER"))
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plusSeconds(3600))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Comandos locales ejecutados y snapshots SHA-256 coincidentes.

SHA-256: `7395299c86e42255cfb3240b91cda79d217bc45561c86cf3e627ae50948dbb10`

### `tests/compose/README.md`

1. **Ruta:** [tests/compose/README.md](../../tests/compose/README.md).

2. **Problema actual:** La documentación describía dos bases.

3. **Cambio realizado:** Documenta las cinco bases, variables, tablas, comandos, DBeaver y límites de la entrega distribuida.

4. **Código completo final:**

````markdown
# Verificación real de PostgreSQL y del stack con Docker Compose

Desde la raíz, con Docker Desktop/Engine activo y Python 3:

```powershell
python tests/compose/verify_postgres_compose.py
```

Usa el `docker-compose.yml` real y levanta los cinco PostgreSQL (orders, catalog,
auth, carrito, notifications) en un proyecto de nombre único. No lee tu `.env`: genera
credenciales temporales en memoria y no las imprime ni las escribe en el informe.

Verifica:

- Configuración JDBC de los cinco servicios y dependencias `service_healthy`.
- PostgreSQL 17, healthchecks, identidad de la conexión y SQL real.
- Autenticación TCP: password válido aceptado e inválido rechazado.
- Service DNS y separación entre las cinco instancias.
- Commit y rollback de filas de prueba.
- Redes bridge separadas, volúmenes diferentes y puertos publicados solo en localhost.
- Handshake PostgreSQL real desde el host, como usa DBeaver.
- Persistencia tras reinicio y reemplazo completo de los contenedores.
- Limpieza exclusiva de los contenedores, redes y volúmenes del proyecto temporal.

El informe queda en `tests/compose/target/postgres-compose-report.json`, ignorado
por Git. Cualquier fallo inesperado termina con código distinto de cero.

La tabla `compose_probe` existe solo en las bases temporales de verificación.
Esta primera prueba comprueba la infraestructura de las dos bases de datos.

## Stack completo y RabbitMQ

Requiere Java/Javac 21, Python 3, Docker Compose >= 2.24.4 y los JAR verificados
de los cinco microservicios. Si necesitas reconstruirlos, ejecuta desde la raíz:

```powershell
foreach ($service in @('auth', 'catalog', 'carrito', 'orders', 'notifications')) {
    Push-Location $service
    try {
        .\mvnw.cmd -B -ntp clean verify
        if ($LASTEXITCODE -ne 0) { throw "Falló verify para $service" }
    } finally { Pop-Location }
}
python tests/compose/verify_stack_compose.py
```

El script usa el Compose real junto con un override temporal. Conserva las URLs
JDBC y las conexiones AMQP del archivo base. Cambia únicamente los nombres de
contenedores, puertos de acceso y emisor JWT para aislar la prueba. Utiliza la
configuración SMTP/Mailpit del archivo principal, sin sustituirla en el test.
Los puertos HTTP, AMQP y PostgreSQL son aleatorios y se publican solo en localhost
durante QA, para no chocar con el stack de desarrollo. El Compose local publica
las cinco bases en localhost:5432–5436 para DBeaver. No lee `.env` ni toca
los volúmenes de desarrollo. Mailpit ya forma parte del único Compose local.

`ComposeJwtIssuer.java` genera claves RSA temporales, sirve JWKS real y firma
tokens para dos usuarios. Solo el endpoint de claves públicas escucha en las
interfaces del host durante la prueba, para que Docker pueda acceder a él.
No deshabilita Spring Security ni sustituye HTTP, AMQP, SQL o SMTP con mocks.
La clase se compila usando las dependencias existentes del módulo E2E.

Se comprueba:

- Los cinco servicios arrancan con sus imágenes Java 21 y usuario no root.
- Flyway aplica orders V1/V2/V3, catalog V1/V2 y auth/carrito/notifications V1;
  Hibernate valida los cinco esquemas.
- Auth persiste los claims firmados sin duplicar perfiles; catalog lee sus tres productos.
- Las peticiones anónimas a checkout y orders reciben 401.
- RabbitMQ tiene consumidores reales para orders y notifications.
- Checkout devuelve 202, total 69.00 y propietario correcto.
- Carrito confirma su solicitud UUID y sus dos items en su propia base.
- Orders persiste una orden CREATED y dos items exactos, incluyendo SKU Unicode.
- Una conexión SQL independiente verifica la orden y los items confirmados.
- El propietario obtiene 200; otro usuario obtiene 404 y una lista vacía.
- Una cola durable independiente captura el evento real `order.created`.
- Notifications entrega el correo por SMTP a Mailpit con remitente, destinatario,
  asunto y cuerpo exactos.
- Notifications confirma un registro del envío que coincide con el correo recibido.
- Carrito, orders y notifications tienen conexiones AMQP autenticadas en el vhost
  esperado; todos los microservicios siguen ejecutándose sin reinicios.
- Se eliminan exclusivamente los recursos del proyecto temporal y se cierra el
  emisor JWT. El código de salida es distinto de cero ante cualquier fallo.

Auth y catalog no son clientes RabbitMQ en la arquitectura existente. Su
funcionamiento se verifica por HTTP; el flujo de mensajería cubre carrito,
orders y notifications.

Evidencia local, ignorada por Git, en `tests/compose/target/`:

- `postgres-compose-report.json` y `stack-compose-report.json`.
- `compose-stack.log` y `issuer-build.log`.
- Respuestas de checkout/orden/usuario ajeno, fila PostgreSQL, evento y correo.

Los informes no contienen contraseñas ni JWT. Las credenciales y tokens efímeros
solo existen en memoria y en el directorio temporal del emisor, que se elimina
al finalizar. Esta prueba complementa los tests JPA, los de mensajería y el E2E
con Testcontainers; no sustituye sus ejecuciones Maven ni una ejecución de CI.
````

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Comandos locales ejecutados y snapshots SHA-256 coincidentes.

SHA-256: `75ed2b98679b7fd136a182eddafbd1ea5c2272aed6de47df30109f90e1d1b7f0`

### `tests/compose/qa_databases.py`

1. **Ruta:** [tests/compose/qa_databases.py](../../tests/compose/qa_databases.py).

2. **Problema actual:** La verificación asumía únicamente dos bases.

3. **Cambio realizado:** Comprueba las cinco asignaciones, aislamiento, persistencia y datos reales de los cinco servicios.

4. **Código completo final:**

```python
"""Independent expected service/database assignments for real Compose verification."""

import secrets

DATABASES = {
    "postgres": {"app": "orders", "prefix": "POSTGRES_", "port": 5432},
    "catalog-postgres": {"app": "catalog", "prefix": "CATALOG_POSTGRES_", "port": 5433},
    "auth-postgres": {"app": "auth", "prefix": "AUTH_POSTGRES_", "port": 5434},
    "carrito-postgres": {"app": "carrito", "prefix": "CARRITO_POSTGRES_", "port": 5435},
    "notifications-postgres": {"app": "notifications", "prefix": "NOTIFICATIONS_POSTGRES_", "port": 5436},
}


def temporary_settings():
    return {service: {"database": spec["app"] + "_compose_qa", "user": spec["app"] + "_qa",
                      "password": secrets.token_urlsafe(32)} for service, spec in DATABASES.items()}


def connection_environment(settings):
    return {spec["prefix"] + suffix: settings[service][field]
            for service, spec in DATABASES.items()
            for suffix, field in (("DB", "database"), ("USER", "user"), ("PASSWORD", "password"))}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** 42 comprobaciones PASS y limpieza de recursos temporales.

SHA-256: `7604629ed8897a7e6095bb8ec1d69a36ff3248b9abcfbb7b2d5f4a133a769c7c`

### `tests/compose/verify_postgres_compose.py`

1. **Ruta:** [tests/compose/verify_postgres_compose.py](../../tests/compose/verify_postgres_compose.py).

2. **Problema actual:** La verificación asumía únicamente dos bases.

3. **Cambio realizado:** Comprueba las cinco asignaciones, aislamiento, persistencia y datos reales de los cinco servicios.

4. **Código completo final:**

```python
"""Exercise the actual Compose PostgreSQL services in an isolated temporary project."""

import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import tempfile
import time
import uuid
from qa_databases import DATABASES, temporary_settings, connection_environment


ROOT = Path(__file__).resolve().parents[2]
RESULT = ROOT / "tests/compose/target/postgres-compose-report.json"
PROJECT = "camisetas360-pg-qa-" + uuid.uuid4().hex[:12]
SERVICES = tuple(DATABASES)
SETTINGS = temporary_settings()
for settings in SETTINGS.values():
    settings["marker"] = uuid.uuid4().hex

environment = os.environ.copy()
environment.update(connection_environment(SETTINGS))
environment.update({
    "RABBITMQ_USERNAME": "compose_qa",
    "RABBITMQ_PASSWORD": secrets.token_urlsafe(32),
    "MAIL_USERNAME": "compose-qa@example.test",
    "MAIL_PASSWORD": secrets.token_urlsafe(32),
    "MAIL_FROM": "compose-qa@example.test",
})
report = {"project": PROJECT, "checks": [], "success": False, "cleanup_success": False}


def run(*arguments, allowed_failure=False, timeout=180):
    result = subprocess.run(arguments, cwd=ROOT, env=environment, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode != 0 and not allowed_failure:
        # Never print command arguments: temporary passwords are passed to Docker exec.
        raise RuntimeError(f"{arguments[0]} failed ({result.returncode}): "
                           f"{result.stdout.strip()} {result.stderr.strip()}")
    return result


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def record(name, details):
    report["checks"].append({"name": name, "result": "PASS", "details": details})
    print(f"PASS: {name}", flush=True)


def inspect_container(container):
    return json.loads(run("docker", "inspect", container).stdout)[0]


def psql(container, service, sql, password=None, allowed_failure=False, host=None):
    settings = SETTINGS[service]
    return run("docker", "exec", "-e", "PGPASSWORD=" + (password or settings["password"]),
               container, "psql", "-X", "-h", host or service, "-U", settings["user"],
               "-d", settings["database"], "-v", "ON_ERROR_STOP=1", "-At", "-c", sql,
               allowed_failure=allowed_failure)


def check_row(container, service):
    result = psql(container, service, "SELECT marker FROM compose_probe WHERE id=1;")
    require(result.stdout.strip() == SETTINGS[service]["marker"],
            f"Stored marker changed or vanished in {service}")


failure = None
started = False
with tempfile.TemporaryDirectory(prefix="camisetas360-compose-") as temporary:
    empty_environment = Path(temporary) / "empty.env"
    empty_environment.write_text("", encoding="utf-8")
    base_arguments = ("docker", "compose", "--project-name", PROJECT, "--file",
                      str(ROOT / "docker-compose.yml"), "--env-file", str(empty_environment))
    override = Path(temporary) / "qa.yaml"
    override.write_text("services:\n" + "".join(
        f"  {service}:\n    ports: !override\n      - '127.0.0.1::5432'\n"
        for service in SERVICES), encoding="utf-8")
    compose_arguments = (*base_arguments, "--file", str(override))

    def compose(*arguments, **kwargs):
        return run(*compose_arguments, *arguments, **kwargs)

    try:
        require(not compose("ps", "--all", "--quiet").stdout.strip(),
                "Temporary Compose project must be empty before starting")
        config = json.loads(run(*base_arguments, "config", "--format", "json").stdout)
        for service, spec in DATABASES.items():
            app = spec["app"]
            database = SETTINGS[service]["database"]
            app_settings = config["services"][app]
            expected_url = f"jdbc:postgresql://{service}:5432/{database}"
            require(app_settings["environment"]["SPRING_DATASOURCE_URL"] == expected_url,
                    f"Wrong datasource URL for {app}")
            require(app_settings["environment"]["SPRING_DATASOURCE_USERNAME"] == SETTINGS[service]["user"],
                    f"Wrong datasource user for {app}")
            require(app_settings["environment"]["SPRING_DATASOURCE_PASSWORD"] == SETTINGS[service]["password"],
                    f"Wrong datasource password for {app}")
            require(app_settings["depends_on"][service]["condition"] == "service_healthy",
                    f"{app} does not wait for a healthy PostgreSQL")
            ports = config["services"][service].get("ports", [])
            expected_port = str(spec["port"])
            require(len(ports) == 1 and ports[0]["host_ip"] == "127.0.0.1"
                    and str(ports[0]["published"]) == expected_port and ports[0]["target"] == 5432,
                    f"Incorrect local DBeaver port or non-loopback exposure: {service}")
        record("Compose configuration and application connection settings", "Real Compose file, external credentials, healthy dependencies")

        started = True  # Also clean up resources left by a partially failed up.
        compose("up", "--detach", "--wait", "--wait-timeout", "120", *SERVICES)
        containers = {service: compose("ps", "--quiet", service).stdout.strip() for service in SERVICES}
        volumes = []
        networks = {}
        for service, container in containers.items():
            require(bool(container), f"Missing container: {service}")
            state = inspect_container(container)
            require(state["State"]["Health"]["Status"] == "healthy", f"Unhealthy DB: {service}")
            bindings = state["NetworkSettings"]["Ports"].get("5432/tcp", [])
            require(len(bindings) == 1 and bindings[0]["HostIp"] == "127.0.0.1",
                    f"PostgreSQL port is not restricted to loopback: {service}")
            with socket.create_connection(("127.0.0.1", int(bindings[0]["HostPort"])), timeout=5) as connection:
                # PostgreSQL SSLRequest proves the host port reaches PostgreSQL,
                # rather than merely asserting that a port mapping exists.
                connection.sendall(struct.pack("!II", 8, 80877103))
                require(connection.recv(1) in (b"S", b"N"), f"Host cannot reach PostgreSQL: {service}")
            record(f"{service}: PostgreSQL reachable from the host", "PostgreSQL protocol handshake over published loopback TCP port")
            database_volumes = [mount for mount in state["Mounts"]
                                if mount["Destination"] == "/var/lib/postgresql/data" and mount["Type"] == "volume"]
            require(len(database_volumes) == 1, f"Persistent volume missing: {service}")
            require(database_volumes[0]["Name"].startswith(PROJECT + "_"), "Unexpected volume owner")
            volumes.append(database_volumes[0]["Name"])
            connections = state["NetworkSettings"]["Networks"]
            require(len(connections) == 1, f"Unexpected PostgreSQL network: {service}")
            network = next(iter(connections))
            database_network = json.loads(run("docker", "network", "inspect", network).stdout)[0]
            require(database_network["Driver"] == "bridge" and not database_network["Internal"],
                    f"DB network does not support the local host access required for DBeaver: {service}")
            networks[service] = network
            version = psql(container, service, "SHOW server_version_num;").stdout.strip()
            require(170000 <= int(version) < 180000, f"Wrong PostgreSQL major version: {version}")
            identity = psql(container, service, "SELECT current_database() || ':' || current_user;").stdout.strip()
            require(identity == SETTINGS[service]["database"] + ":" + SETTINGS[service]["user"],
                    f"Connected to the wrong database/user: {service}")
            record(f"{service}: healthy PostgreSQL 17 with dedicated network and persistent volume", {"version": version, "database": SETTINGS[service]["database"]})

            denied = psql(container, service, "SELECT 1;", password=secrets.token_urlsafe(32), allowed_failure=True)
            require(denied.returncode != 0 and "password authentication failed" in denied.stderr.lower(),
                    f"Wrong password was not rejected by PostgreSQL TCP authentication: {service}")
            record(f"{service}: TCP password authentication", "Correct password accepted; incorrect password rejected")

            sql = ("CREATE TABLE compose_probe (id INTEGER PRIMARY KEY, marker TEXT NOT NULL); "
                   f"INSERT INTO compose_probe VALUES (1, '{SETTINGS[service]['marker']}');")
            psql(container, service, sql)
            check_row(container, service)
            rollback = psql(container, service,
                            "BEGIN; INSERT INTO compose_probe VALUES (2, 'rolled-back'); ROLLBACK; "
                            "SELECT count(*) FROM compose_probe WHERE id=2;")
            require(rollback.stdout.strip().splitlines()[-1] == "0", f"Rollback failed: {service}")
            record(f"{service}: committed SQL and rollback", "Probe row committed; rolled-back row absent")

            dns = psql(container, service, "SELECT current_database();", host=service)
            require(dns.stdout.strip() == SETTINGS[service]["database"], f"Internal service DNS failed: {service}")
            other_databases = ",".join("'" + SETTINGS[other]["database"] + "'" for other in SERVICES if other != service)
            isolated = psql(container, service, f"SELECT count(*) FROM pg_database WHERE datname IN ({other_databases});")
            require(isolated.stdout.strip() == "0", f"Databases are not separated: {service}")
            record(f"{service}: service DNS and database isolation", "Service name resolves; other service DB absent from this instance")

        require(len(set(volumes)) == len(SERVICES) and len(set(networks.values())) == len(SERVICES), "Services share storage or DB network")
        compose("restart", *SERVICES)
        compose("up", "--detach", "--wait", "--wait-timeout", "120", *SERVICES)
        for service in SERVICES:
            check_row(containers[service], service)
        record("Data survives database restart", "All five committed markers remain")

        compose("up", "--detach", "--force-recreate", "--wait", "--wait-timeout", "120", *SERVICES)
        for service in SERVICES:
            replacement = compose("ps", "--quiet", service).stdout.strip()
            require(replacement != containers[service], f"Container was not recreated: {service}")
            check_row(replacement, service)
        record("Data survives container replacement", "Different container IDs, same stored markers in all five databases")
        report["success"] = True
    except Exception as error:
        failure = error
        report["error"] = str(error)
    finally:
        if started:
            try:
                compose("down", "--volumes", "--timeout", "30")
                remaining = run("docker", "ps", "--all", "--quiet", "--filter", "label=com.docker.compose.project=" + PROJECT)
                remaining_volumes = run("docker", "volume", "ls", "--quiet", "--filter", "label=com.docker.compose.project=" + PROJECT)
                remaining_networks = run("docker", "network", "ls", "--quiet", "--filter", "label=com.docker.compose.project=" + PROJECT)
                require(not any(result.stdout.strip() for result in (remaining, remaining_volumes, remaining_networks)),
                        "Temporary project resources remain after cleanup")
                report["cleanup_success"] = True
                record("Cleanup of isolated temporary project", "Only this project's containers, volumes and networks removed")
            except Exception as error:
                report["cleanup_error"] = str(error)
                report["success"] = False
                failure = failure or error
        else:
            report["cleanup_success"] = True
        report["finished_at_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        RESULT.parent.mkdir(parents=True, exist_ok=True)
        RESULT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {RESULT}", flush=True)
if failure:
    raise SystemExit(f"FAIL: {failure}")
print("PASS: all PostgreSQL Compose checks", flush=True)
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** 42 comprobaciones PASS y limpieza de recursos temporales.

SHA-256: `253c08d31e02680bcaccebc0f646c96705c6784b734537ee17dcad81e0dcda7e`

### `tests/compose/verify_stack_compose.py`

1. **Ruta:** [tests/compose/verify_stack_compose.py](../../tests/compose/verify_stack_compose.py).

2. **Problema actual:** La verificación asumía únicamente dos bases.

3. **Cambio realizado:** Comprueba las cinco asignaciones, aislamiento, persistencia y datos reales de los cinco servicios.

4. **Código completo final:**

```python
"""Run the real five-service Compose stack and checkout/AMQP/PostgreSQL/SMTP flow.

Requires Python 3, Java/Javac 21, Maven Wrapper, Docker Compose >= 2.24.4,
and previously built target/*-SNAPSHOT.jar files for the five services.
"""

import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from qa_databases import DATABASES, temporary_settings, connection_environment

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "tests/compose/target"
PROJECT = "camisetas360-stack-qa-" + uuid.uuid4().hex[:12]
APPS = ("auth", "catalog", "carrito", "orders", "notifications")
SERVICES = (*DATABASES, "rabbitmq", "mailpit", *APPS)
ITEMS = [{"sku": "CAM-Ñ-東京", "quantity": 2, "unitPrice": 19.5},
         {"sku": "CAM-002", "quantity": 3, "unitPrice": 10.0}]
FROM = "orders@camisetas360.test"
environment = os.environ.copy()
environment.update(connection_environment(temporary_settings()))
environment.update({
    "RABBITMQ_USERNAME": "compose_qa", "RABBITMQ_PASSWORD": secrets.token_urlsafe(32),
    "RABBITMQ_VHOST": "/camisetas360", "MAIL_USERNAME": "compose_qa",
    "MAIL_PASSWORD": secrets.token_urlsafe(32), "MAIL_FROM": FROM,
})
report = {"project": PROJECT, "checks": [], "success": False, "cleanup_success": False}
TARGET.mkdir(parents=True, exist_ok=True)


def run(*args, timeout=240):
    result = subprocess.run(args, cwd=ROOT, env=environment, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{args[0]} exited {result.returncode}: {result.stdout} {result.stderr}")
    return result.stdout.strip()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def record(name, details):
    report["checks"].append({"name": name, "result": "PASS", "details": details})
    print("PASS: " + name, flush=True)


def save(name, data):
    (TARGET / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def request(method, url, expected=200, body=None, token=None, basic=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if basic:
        headers["Authorization"] = "Basic " + base64.b64encode(basic.encode()).decode()
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        text = response.read().decode("utf-8")
        require(response.code == expected,
                f"{method} {url}: expected {expected}, got {response.code}: {text}")
        return json.loads(text) if text.strip() else None


def eventually(check, label, seconds=90):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (AssertionError, urllib.error.URLError, TimeoutError, ConnectionError) as error:
            last = error
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {label}: {last}")


def timestamp_recent(value):
    elapsed = (datetime.now(timezone.utc) - datetime.fromisoformat(value.replace("Z", "+00:00"))).total_seconds()
    require(0 <= elapsed < 180, "Missing, stale, or future timestamp: " + value)


def assert_items(items):
    require(sorted(items, key=lambda item: item["sku"]) == sorted(ITEMS, key=lambda item: item["sku"]),
            "Incorrect item SKU, quantity, price, or extra/missing items")


failure = None
started = False
issuer_process = None
containers = {}
with tempfile.TemporaryDirectory(prefix="camisetas360-stack-") as temporary:
    directory = Path(temporary)
    empty_env = directory / "empty.env"
    empty_env.write_text("", encoding="utf-8")
    overlay = directory / "compose-qa.yaml"

    def compose(*args, **kwargs):
        return run("docker", "compose", "--project-name", PROJECT, "--file", str(ROOT / "docker-compose.yml"),
                   "--file", str(overlay), "--env-file", str(empty_env), *args, **kwargs)

    def sql(service, statement):
        prefix = DATABASES[service]["prefix"]
        return run("docker", "exec", "-e", "PGPASSWORD=" + environment[prefix + "PASSWORD"], containers[service],
                   "psql", "-X", "-h", service, "-U", environment[prefix + "USER"],
                   "-d", environment[prefix + "DB"], "-v", "ON_ERROR_STOP=1", "-At", "-c", statement)

    def port(service, internal):
        bindings = json.loads(run("docker", "inspect", containers[service]))[0]["NetworkSettings"]["Ports"]
        binding = bindings[str(internal) + "/tcp"][0]
        require(binding["HostIp"] == "127.0.0.1", "QA endpoint must bind only to loopback")
        return "http://127.0.0.1:" + binding["HostPort"]

    try:
        for app in APPS:
            require(len(list((ROOT / app / "target").glob("*-SNAPSHOT.jar"))) == 1,
                    f"Build {app} with its Maven Wrapper clean verify before this test")
        classpath_file = ROOT / "tests/e2e/target/compose-classpath.txt"
        wrapper = str(ROOT / ("orders/mvnw.cmd" if os.name == "nt" else "orders/mvnw"))
        maven_command = [wrapper] if os.name == "nt" else ["bash", wrapper]
        build_output = run(*maven_command, "-B", "-ntp", "-f", "tests/e2e/pom.xml", "test-compile",
                           "dependency:build-classpath", "-Dmdep.includeScope=test",
                           "-Dmdep.outputFile=" + str(classpath_file), timeout=300)
        (TARGET / "issuer-build.log").write_text(build_output, encoding="utf-8")
        require("BUILD SUCCESS" in build_output, "JWT issuer dependencies did not build")
        dependencies = classpath_file.read_text(encoding="utf-8").strip()
        run("javac", "-encoding", "UTF-8", "-cp", dependencies, "-d", str(TARGET),
            str(ROOT / "tests/compose/ComposeJwtIssuer.java"))
        metadata_file = directory / "jwt-fixture.json"
        issuer_log = (TARGET / "issuer.log").open("w", encoding="utf-8")
        issuer_process = subprocess.Popen(["java", "-cp", str(TARGET) + os.pathsep + dependencies,
                                          "ComposeJwtIssuer", str(metadata_file)],
                                         cwd=ROOT, stdin=subprocess.PIPE, stdout=issuer_log, stderr=issuer_log)
        def issuer_ready():
            require(issuer_process.poll() is None, "Ephemeral JWT issuer exited unexpectedly")
            return metadata_file.exists() and metadata_file.stat().st_size > 0
        eventually(issuer_ready, "RS256 issuer", 30)
        fixture = json.loads(metadata_file.read_text(encoding="utf-8"))
        environment["ENTRA_ISSUER_URI"] = fixture["issuer"]
        environment["ENTRA_AUDIENCE"] = fixture["audience"]
        owner = fixture["ownerToken"]
        foreign = fixture["foreignToken"]
        email = fixture["email"]
        extra = "services:\n"
        for service in (*APPS, "rabbitmq"):
            extra += f"  {service}:\n    container_name: {PROJECT}-{service}\n    restart: 'no'\n    ports: !override\n"
            for exposed in ((5672, 15672) if service == "rabbitmq" else (8080,)):
                extra += f"      - '127.0.0.1::{exposed}'\n"
            if service in APPS:
                extra += ("    extra_hosts:\n      - 'host.docker.internal:host-gateway'\n    environment:\n"
                          f"      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: '{fixture['jwks']}'\n"
                          "      JAVA_TOOL_OPTIONS: '-Xmx256m -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US'\n")
        for database in DATABASES:
            extra += f"  {database}:\n    ports: !override\n      - '127.0.0.1::5432'\n"
        extra += ("  mailpit:\n    ports: !override\n      - '127.0.0.1::8025'\n")
        overlay.write_text(extra, encoding="utf-8")
        config = json.loads(compose("config", "--format", "json"))
        for database in DATABASES:
            ports = config["services"][database].get("ports", [])
            require(len(ports) == 1 and ports[0]["host_ip"] == "127.0.0.1",
                    "PostgreSQL host access must be restricted to loopback")
        record("Isolated Compose configuration", "Actual base file; random loopback HTTP/AMQP/SQL ports and ephemeral credentials")
        started = True
        output = compose("up", "--detach", "--build", "--wait", "--wait-timeout", "180", timeout=600)
        (TARGET / "compose-up.log").write_text(output, encoding="utf-8")
        containers = {service: compose("ps", "--quiet", service) for service in SERVICES}
        for service, container in containers.items():
            state = json.loads(run("docker", "inspect", container))[0]
            require(state["State"]["Running"], service + " is not running")
            if service in APPS:
                require(state["Config"]["User"] == "10001:10001", service + " runs as root")
                eventually(lambda: "Started " in run("docker", "logs", container), service + " startup", 150)
        bases = {app: port(app, 8080) for app in APPS}
        record("All five real microservices started", "Java 21 runtime images running as UID 10001; no HTTP or infrastructure mocks")
        for database in DATABASES:
            versions = {"postgres": "1,2,3", "catalog-postgres": "1,2"}.get(database, "1")
            require(sql(database, "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success;") == versions,
                    "Incorrect Flyway schema history: " + database)
            state = json.loads(run("docker", "inspect", containers[database]))[0]
            bindings = state["NetworkSettings"]["Ports"].get("5432/tcp", [])
            require(len(bindings) == 1 and bindings[0]["HostIp"] == "127.0.0.1", "PostgreSQL is exposed outside loopback")
        require(request("GET", bases["orders"] + "/actuator/health/readiness")["status"] == "UP", "Orders DB readiness failed")
        record("Flyway and PostgreSQL connections", "Five separate migrated databases; orders 1,2,3; catalog 1,2; auth/carrito/notifications 1; Hibernate validate")
        request("POST", bases["carrito"] + "/api/v1/carrito/checkout", expected=401, body={"items": ITEMS})
        request("GET", bases["orders"] + "/api/v1/orders", expected=401)
        profile = request("GET", bases["auth"] + "/api/v1/auth/profile", token=owner)
        require(profile == {"userId": "compose-user", "tenantId": "compose-tenant", "email": email, "name": "Compose QA Buyer"}, "Auth profile differs from signed claims")
        request("GET", bases["auth"] + "/api/v1/auth/profile", token=owner)
        request("GET", bases["auth"] + "/api/v1/auth/profile", expected=401, token=fixture["missingSubjectToken"])
        stored_profile = json.loads(sql("auth-postgres", "SELECT row_to_json(p) FROM user_profiles p;"))
        require(stored_profile == {"issuer": fixture["issuer"], "subject": email,
                                  "user_id": "compose-user", "tenant_id": "compose-tenant",
                                  "email": email, "name": "Compose QA Buyer"}, "Auth profile is not committed in its own PostgreSQL")
        require(sql("auth-postgres", "SELECT count(*) FROM user_profiles;") == "1", "Repeated profile request created duplicates")
        save("auth-profile-postgres.json", stored_profile)
        products = request("GET", bases["catalog"] + "/api/v1/catalog/products", token=owner)
        require(len(products) == 3 and {item["sku"] for item in products} == {"CAM-001", "CAM-002", "CAM-003"}, "Catalog seeds missing or duplicated")
        require(sql("catalog-postgres", "SELECT count(*) FROM products;") == "3", "Catalog is not reading real PostgreSQL seeds")
        record("Auth, catalog, and security", "RS256 JWT/JWKS; auth profile committed without duplicates; catalog's 3 products in its own DB; anonymous checkout/orders 401")
        rabbit_base = port("rabbitmq", 15672)
        basic = environment["RABBITMQ_USERNAME"] + ":" + environment["RABBITMQ_PASSWORD"]
        vhost = urllib.parse.quote(environment["RABBITMQ_VHOST"], safe="")
        def rabbit(method, path, body=None, expected=200):
            return request(method, rabbit_base + path, expected=expected, body=body, basic=basic)
        for queue in ("orders.checkout-requested.q", "notifications.order-created.q"):
            eventually(lambda: rabbit("GET", f"/api/queues/{vhost}/{queue}").get("consumers", 0) >= 1,
                       queue + " real consumer", 60)
        require(request("GET", bases["notifications"] + "/actuator/health")["status"] == "UP", "Rabbit/SMTP readiness failed")
        record("RabbitMQ listeners and SMTP connected", "orders.checkout-requested.q and notifications.order-created.q have real consumers; notifications health UP")
        capture = "compose.order-created.capture"
        rabbit("PUT", f"/api/queues/{vhost}/{capture}", {"durable": True, "auto_delete": False, "arguments": {}}, 201)
        rabbit("POST", f"/api/bindings/{vhost}/e/camisetas360.orders/q/{capture}",
               {"routing_key": "order.created", "arguments": {}}, 201)
        mail_base = port("mailpit", 8025) + "/api/v1"
        require(request("GET", mail_base + "/messages")["messages"] == [], "Unexpected email before checkout")
        accepted = request("POST", bases["carrito"] + "/api/v1/carrito/checkout", 202, {"items": ITEMS}, owner)
        require(accepted["status"] == "PROCESSING" and accepted["userEmail"] == email and accepted["totalAmount"] == 69.0,
                "Checkout accepted the wrong status, owner or total")
        uuid.UUID(accepted["requestId"])
        save("checkout-response.json", accepted)
        checkout_id = str(uuid.UUID(accepted["requestId"]))
        stored_checkout = json.loads(sql("carrito-postgres", f"SELECT row_to_json(c) FROM checkout_requests c WHERE id='{checkout_id}';"))
        require(stored_checkout["id"] == checkout_id and stored_checkout["user_email"] == email
                and stored_checkout["total_amount"] == 69.0 and stored_checkout["status"] == "PROCESSING",
                "Checkout was not committed correctly in carrito's own database")
        timestamp_recent(stored_checkout["created_at"])
        checkout_items = json.loads(sql("carrito-postgres", "SELECT json_agg(i) FROM (SELECT sku,quantity,unit_price AS \"unitPrice\" "
                                       f"FROM checkout_request_items WHERE checkout_id='{checkout_id}') i;"))
        assert_items(checkout_items)
        save("carrito-checkout-postgres.json", {"checkout": stored_checkout, "items": checkout_items})
        record("Carrito persists its own checkout aggregate", "Committed UUID request, PROCESSING, owner, total and exact items before returning HTTP 202")
        def persisted_order():
            rows = request("GET", bases["orders"] + "/api/v1/orders", token=owner)
            require(len(rows) <= 1, "Duplicate orders created from a single checkout")
            return rows[0] if rows else None
        order = eventually(persisted_order, "committed order", 45)
        order_id = order["orderId"]
        require(isinstance(order_id, int) and order_id > 0, "Invalid persisted order ID")
        require(order["userEmail"] == email and order["totalAmount"] == 69.0 and order["status"] == "CREATED", "Incorrect order owner, total or status")
        timestamp_recent(order["createdAt"])
        assert_items(order["items"])
        require(request("GET", bases["orders"] + f"/api/v1/orders/{order_id}", token=owner) == order, "Owner detail differs from list")
        database_order = json.loads(sql("postgres", f"SELECT row_to_json(o) FROM orders o WHERE id={order_id};"))
        require(database_order["user_email"] == email and database_order["total_amount"] == 69.0 and database_order["status"] == "CREATED", "HTTP result not committed correctly in PostgreSQL")
        timestamp_recent(database_order["created_at"])
        stored_items = json.loads(sql("postgres", "SELECT json_agg(i) FROM (SELECT sku, quantity, unit_price AS \"unitPrice\" "
                                     f"FROM order_items WHERE order_id={order_id}) i;"))
        assert_items(stored_items)
        require(sql("postgres", "SELECT count(*) FROM orders;") == "1", "Unexpected extra committed order")
        save("order-response.json", order)
        save("postgres-order.json", {"order": database_order, "items": stored_items})
        record("Checkout -> RabbitMQ -> orders -> PostgreSQL", "HTTP 202; committed order and two exact Unicode items; owner GET 200; total 69.00 and CREATED")
        denied = request("GET", bases["orders"] + f"/api/v1/orders/{order_id}", 404, token=foreign)
        require(denied["status"] == 404 and denied["error"] == "Not Found" and "items" not in denied and "userEmail" not in denied, "Another user can see order data")
        require(request("GET", bases["orders"] + "/api/v1/orders", token=foreign) == [], "Foreign user list leaks orders")
        require(request("GET", bases["orders"] + f"/api/v1/orders/{order_id}", token=owner) == order, "Owner lost access")
        save("foreign-order-response.json", denied)
        record("Isolation between users", "Foreign detail 404 and empty list; owner still sees the unchanged order")
        captured = eventually(lambda: rabbit("POST", f"/api/queues/{vhost}/{capture}/get",
                                            {"count": 10, "ackmode": "ack_requeue_true", "encoding": "auto", "truncate": 50000}),
                              "order.created event", 45)
        require(len(captured) == 1 and captured[0]["routing_key"] == "order.created", "Missing or duplicate order.created event")
        event = json.loads(captured[0]["payload"])
        require(event["orderId"] == order_id and event["userEmail"] == email and event["totalAmount"] == 69.0, "Incorrect event payload")
        assert_items(event["items"])
        timestamp_recent(event["occurredAt"])
        save("order-created-event.json", event)
        record("Actual order.created published on RabbitMQ", "Independent durable queue received the matching committed order event")
        messages = eventually(lambda: request("GET", mail_base + "/messages")["messages"], "SMTP email delivered", 45)
        require(len(messages) == 1, "Expected exactly one delivered email")
        message = request("GET", mail_base + "/message/" + messages[0]["ID"])
        expected_text = (f"Hola,\n\nTu orden fue creada correctamente.\n\nNúmero de orden: {order_id}\n"
                         "Total: $69.00\nEstado: CREATED\n\nGracias por comprar en Camisetas360.\n")
        require(message["From"]["Address"] == FROM and [item["Address"] for item in message["To"]] == [email], "Incorrect email sender/recipient")
        require(message["Subject"] == f"Orden creada #{order_id}" and message["Text"].replace("\r\n", "\n") == expected_text, "Incorrect email subject/body")
        save("delivered-email.json", message)
        record("RabbitMQ -> notifications -> Mailpit", "Exact SMTP sender, recipient, subject, order number, total and status verified")
        def delivery_committed():
            return sql("notifications-postgres", "SELECT count(*) FROM email_deliveries;") == "1"
        eventually(delivery_committed, "SMTP delivery log committed in notifications PostgreSQL", 30)
        delivery = json.loads(sql("notifications-postgres", "SELECT row_to_json(d) FROM email_deliveries d;"))
        require(delivery["sender"] == FROM and delivery["recipient"] == email
                and delivery["subject"] == f"Orden creada #{order_id}" and delivery["body"] == expected_text,
                "Notifications log differs from the actual delivered SMTP email")
        timestamp_recent(delivery["sent_at"])
        save("notifications-delivery-postgres.json", delivery)
        record("Notifications persists its own delivery log", "Committed sender, recipient, subject, exact body and sent timestamp match Mailpit")
        amqp_peers = {}
        for app in ("carrito", "orders", "notifications"):
            state = json.loads(run("docker", "inspect", containers[app]))[0]
            amqp_peers[app] = state["NetworkSettings"]["Networks"][PROJECT + "_app-network"]["IPAddress"]
        def authenticated_connections():
            connections = rabbit("GET", "/api/connections")
            connected = {connection["peer_host"] for connection in connections
                         if connection["user"] == environment["RABBITMQ_USERNAME"]
                         and connection["vhost"] == environment["RABBITMQ_VHOST"]}
            # RabbitMQ management statistics are collected asynchronously. Keep
            # the expected three real clients; wait for the next metrics sample.
            return connections if set(amqp_peers.values()).issubset(connected) else None
        connections = eventually(authenticated_connections, "all three authenticated AMQP clients", 30)
        save("rabbitmq-connections.json", [{"service": app, "peer_host": peer,
                                           "user": environment["RABBITMQ_USERNAME"],
                                           "vhost": environment["RABBITMQ_VHOST"]}
                                          for app, peer in amqp_peers.items()])
        for app in APPS:
            state = json.loads(run("docker", "inspect", containers[app]))[0]
            require(state["State"]["Running"] and state["RestartCount"] == 0, app + " crashed/restarted during the test")
        record("Stable stack and AMQP connections", "Five services running without restarts; authenticated AMQP connections verified by each client's container IP")
        report["success"] = True
    except Exception as error:
        failure = error
        report["error"] = str(error)
    finally:
        if started:
            try:
                (TARGET / "compose-stack.log").write_text(compose("logs", "--no-color"), encoding="utf-8")
                compose("down", "--volumes", "--timeout", "30")
                for resource, args in (("containers", ("ps", "--all", "--quiet")),
                                       ("volumes", ("volume", "ls", "--quiet")),
                                       ("networks", ("network", "ls", "--quiet"))):
                    require(not run("docker", *args, "--filter", "label=com.docker.compose.project=" + PROJECT),
                            "Temporary " + resource + " remain")
                report["cleanup_success"] = True
                record("Cleanup of isolated Compose stack", "Only this project's temporary containers, volumes and networks removed")
            except Exception as error:
                failure = failure or error
                report["cleanup_error"] = str(error)
                report["success"] = False
        if issuer_process:
            issuer_process.stdin.close()
            try:
                issuer_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                issuer_process.terminate()
                issuer_process.wait(timeout=10)
            issuer_log.close()
        report["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        save("stack-compose-report.json", report)
        print("Report: " + str(TARGET / "stack-compose-report.json"), flush=True)
if failure:
    raise SystemExit("FAIL: " + str(failure))
print("PASS: complete Compose checkout/PostgreSQL/RabbitMQ/SMTP flow", flush=True)
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** 42 comprobaciones PASS y limpieza de recursos temporales.

SHA-256: `ed4b83865d161a3e925334a4b11231879727a7230678ae7394e56fc04e3f3a8b`

### `tests/e2e/run-e2e.ps1`

1. **Ruta:** [tests/e2e/run-e2e.ps1](../../tests/e2e/run-e2e.ps1).

2. **Problema actual:** El flujo solo necesitaba una base PostgreSQL para orders.

3. **Cambio realizado:** PostgreSQL independientes para carrito/orders/notifications; verifica SQL comprometido y Flyway de cada servicio. El script Windows usa el contexto Docker activo.

4. **Código completo final:**

```powershell
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

# Testcontainers must use the same Docker Desktop named pipe as the active CLI
# context. Keep an explicitly configured DOCKER_HOST if the caller supplies one.
if (-not $env:DOCKER_HOST) {
    $dockerEndpoint = & docker context inspect --format '{{.Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the active Docker context' }
    $env:DOCKER_HOST = $dockerEndpoint.Trim()
}

# Windows PowerShell 5 can treat native stderr warnings as terminating errors
# when the caller redirects output. Maven's exit code determines success.
function Invoke-MavenVerify {
    param([string]$Wrapper, [string[]]$MavenArguments)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Wrapper @MavenArguments
        $mavenExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($mavenExitCode -ne 0) { throw "Maven failed ($mavenExitCode): $Wrapper $MavenArguments" }
}

# Build current executable JARs and run each participating service's own suite first.
foreach ($module in @('carrito', 'orders', 'notifications')) {
    Push-Location (Join-Path $repoRoot $module)
    try {
        Invoke-MavenVerify -Wrapper '.\mvnw.cmd' -MavenArguments @('-B', '-ntp', 'verify')
    } finally {
        Pop-Location
    }
}
Push-Location $repoRoot
try {
    Invoke-MavenVerify -Wrapper '.\orders\mvnw.cmd' -MavenArguments @('-B', '-ntp', '-f', 'tests/e2e/pom.xml', '-Pe2e', 'verify')
} finally {
    Pop-Location
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** Dos E2E: 0 Failures, 0 Errors, con RabbitMQ y SMTP reales.

SHA-256: `3bea090077c697a311d4fba1bc6ea0e24b65aaa85bb8102a89d31d68961ec38e`

### `tests/e2e/src/test/java/com/camisetas360/testing/e2e/CheckoutFlowE2EIT.java`

1. **Ruta:** [tests/e2e/src/test/java/com/camisetas360/testing/e2e/CheckoutFlowE2EIT.java](../../tests/e2e/src/test/java/com/camisetas360/testing/e2e/CheckoutFlowE2EIT.java).

2. **Problema actual:** La prueba existente no contemplaba persistencia, o es una integración nueva identificada como tal.

3. **Cambio realizado:** Conserva los resultados del contrato; adapta colaboradores y valida identidad, constraints, relaciones o rollback según su alcance.

4. **Código completo final:**

```java
package com.camisetas360.testing.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CheckoutFlowE2EIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FROM = "orders@camisetas360.test";

    private static final String CUSTOMER_ROLE = "CUSTOMER";

    private static final String ITEMS = """
            [
              {
                "sku": "CAM-Ñ-東京",
                "quantity": 2,
                "unitPrice": 19.5
              },
              {
                "sku": "CAM-002",
                "quantity": 3,
                "unitPrice": 10.0
              }
            ]
            """;

    private final List<AutoCloseable> resources = new ArrayList<>();

    private HttpClient http;
    private RabbitMQContainer rabbit;
    private PostgreSQLContainer postgres;
    private PostgreSQLContainer carritoPostgres;
    private PostgreSQLContainer notificationsPostgres;
    private GenericContainer<?> smtp;
    private TestJwtIssuer issuer;
    private ServiceProcess carrito;
    private ServiceProcess orders;
    private ServiceProcess notifications;
    private Path logs;

    @SuppressWarnings("resource")
    @BeforeEach
    void startIsolatedSystem(
            TestInfo info) throws Exception {

        var root = Path.of(
                System.getProperty("repo.root"))
                .toAbsolutePath()
                .normalize();

        logs = Path.of(
                System.getProperty("e2e.logs"))
                .resolve(
                        info.getTestMethod()
                                .orElseThrow()
                                .getName()
                                + "-"
                                + UUID.randomUUID());

        Files.createDirectories(logs);

        http = register(
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofSeconds(5))
                        .build());

        postgres = register(new PostgreSQLContainer("postgres:17")
                .withDatabaseName("orders_e2e"));
        postgres.start();

        carritoPostgres = register(new PostgreSQLContainer("postgres:17")
                .withDatabaseName("carrito_e2e"));
        carritoPostgres.start();
        notificationsPostgres = register(new PostgreSQLContainer("postgres:17")
                .withDatabaseName("notifications_e2e"));
        notificationsPostgres.start();

        rabbit = register(
                new RabbitMQContainer(
                        "rabbitmq:4-management"));

        rabbit.start();

        var mailpit = new GenericContainer<>(
                DockerImageName.parse(
                        "axllent/mailpit:v1.31.3"))
                .withExposedPorts(
                        1025,
                        8025)
                .waitingFor(
                        Wait.forHttp(
                                "/api/v1/messages")
                                .forPort(8025))
                .withStartupTimeout(
                        Duration.ofSeconds(60));

        smtp = register(mailpit);

        smtp.start();

        issuer = register(
                new TestJwtIssuer());

        var common = new HashMap<String, String>();

        common.put(
                "spring.rabbitmq.host",
                rabbit.getHost());

        common.put(
                "spring.rabbitmq.port",
                rabbit.getAmqpPort().toString());

        common.put(
                "spring.rabbitmq.username",
                rabbit.getAdminUsername());

        common.put(
                "spring.rabbitmq.password",
                rabbit.getAdminPassword());

        common.put(
                "spring.rabbitmq.virtual-host",
                "/");

        common.put(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                issuer.issuer());

        common.put(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                issuer.jwks());

        common.put(
                "spring.security.oauth2.resourceserver.jwt.audiences[0]",
                TestJwtIssuer.AUDIENCE);

        carrito = register(
                new ServiceProcess(
                        root,
                        logs,
                        "carrito",
                        databaseProperties(common, carritoPostgres)));

        var orderProperties = new HashMap<>(common);

        orderProperties.put(
                "spring.datasource.url",
                postgres.getJdbcUrl());
        orderProperties.put("spring.datasource.username", postgres.getUsername());
        orderProperties.put("spring.datasource.password", postgres.getPassword());

        orderProperties.put(
                "spring.jpa.hibernate.ddl-auto",
                "validate");

        orders = register(
                new ServiceProcess(
                        root,
                        logs,
                        "orders",
                        orderProperties));

        var notificationProperties = databaseProperties(common, notificationsPostgres);

        notificationProperties.putAll(
                Map.of(
                        "spring.mail.host",
                        smtp.getHost(),

                        "spring.mail.port",
                        smtp.getMappedPort(1025)
                                .toString(),

                        "spring.mail.username",
                        "e2e",

                        "spring.mail.password",
                        "e2e",

                        "spring.mail.properties.mail.smtp.auth",
                        "false",

                        "spring.mail.properties.mail.smtp.starttls.enable",
                        "false",

                        "spring.mail.properties.mail.smtp.starttls.required",
                        "false",

                        "app.mail.from",
                        FROM));

        for (var timeout : List.of(
                "connectiontimeout",
                "timeout",
                "writetimeout")) {
            notificationProperties.put(
                    "spring.mail.properties.mail.smtp."
                            + timeout,
                    "5000");
        }

        notifications = register(
                new ServiceProcess(
                        root,
                        logs,
                        "notifications",
                        notificationProperties));

        carrito.awaitStarted();
        orders.awaitStarted();
        notifications.awaitStarted();

        assertThat(
                request(
                        "POST",
                        carrito.baseUrl()
                                + "/api/v1/carrito/checkout",
                        null,
                        "{}").statusCode())
                .as(
                        "Checkout is protected in the actual running service")
                .isEqualTo(401);

        var readinessToken = issuer.token(
                "readiness@example.test",
                "Orders.Read",
                CUSTOMER_ROLE);

        assertThat(
                getJson(
                        orders.baseUrl()
                                + "/api/v1/orders",
                        readinessToken,
                        200).size())
                .isZero();

        await()
                .alias(
                        "Listeners and SMTP are ready")
                .atMost(
                        Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {

                            assertQueueHasConsumer(
                                    "orders.checkout-requested.q");

                            assertQueueHasConsumer(
                                    "notifications.order-created.q");

                            assertThat(
                                    getJson(
                                            notifications.baseUrl()
                                                    + "/actuator/health",
                                            null,
                                            200)
                                            .get("status")
                                            .asString())
                                    .isEqualTo(
                                            "UP");
                        });

        assertThat(
                getJson(
                        mailApi()
                                + "/messages",
                        null,
                        200)
                        .get("messages")
                        .size())
                .isZero();

        rabbitManagement("PUT", "/api/queues/%2F/e2e.order-created.capture",
                "{\"durable\":true,\"auto_delete\":false,\"arguments\":{}}", 201);
        rabbitManagement("POST", "/api/bindings/%2F/e/camisetas360.orders/q/e2e.order-created.capture",
                "{\"routing_key\":\"order.created\",\"arguments\":{}}", 201);
    }

    @Test
    void checkout_shouldPersistOrderAndDeliverEmail_whenRequestIsValid()
            throws Exception {

        checkoutAndAssertDelivery();
    }

    @Test
    void getOrder_shouldReturn404ForAnotherUser_whenCheckoutBelongsToOwner()
            throws Exception {

        var flow = checkoutAndAssertDelivery();

        var otherToken = issuer.token(
                "other-"
                        + UUID.randomUUID()
                        + "@example.test",
                "Orders.Read",
                CUSTOMER_ROLE);

        var denied = getJson(
                orders.baseUrl()
                        + "/api/v1/orders/"
                        + flow.orderId(),
                otherToken,
                404);

        assertThat(
                denied.get("status")
                        .asInt())
                .isEqualTo(404);

        assertThat(
                denied.get("error")
                        .asString())
                .isEqualTo(
                        "Not Found");

        assertThat(
                denied.has("items"))
                .isFalse();

        assertThat(
                denied.has("userEmail"))
                .isFalse();

        assertThat(
                getJson(
                        orders.baseUrl()
                                + "/api/v1/orders",
                        otherToken,
                        200).size())
                .isZero();

        assertThat(
                getJson(
                        orders.baseUrl()
                                + "/api/v1/orders/"
                                + flow.orderId(),
                        flow.token(),
                        200))
                .isEqualTo(
                        flow.order());

        save(
                "foreign-order-response.json",
                denied);
    }

    private Flow checkoutAndAssertDelivery()
            throws Exception {

        var email = "buyer-"
                + UUID.randomUUID()
                + "@example.test";

        var token = issuer.token(
                email,
                "Checkout.Create Orders.Read",
                CUSTOMER_ROLE);

        var checkout = request(
                "POST",
                carrito.baseUrl()
                        + "/api/v1/carrito/checkout",
                token,
                "{\"items\":"
                        + ITEMS
                        + "}");

        assertThat(
                checkout.statusCode())
                .as(
                        checkout.body())
                .isEqualTo(202);

        var accepted = JSON.readTree(
                checkout.body());

        assertThat(
                accepted.get("status")
                        .asString())
                .isEqualTo(
                        "PROCESSING");

        assertThat(
                accepted.get("userEmail")
                        .asString())
                .isEqualTo(
                        email);

        assertThat(
                accepted.get("totalAmount")
                        .asDouble())
                .isEqualTo(
                        69.0);

        assertThat(
                UUID.fromString(
                        accepted.get("requestId")
                                .asString()))
                .isNotNull();

        save(
                "checkout-response.json",
                accepted);

        assertCheckoutPersisted(UUID.fromString(accepted.get("requestId").asString()), email);

        var listUrl = orders.baseUrl()
                + "/api/v1/orders";

        await()
                .alias(
                        "Order becomes visible to its owner")
                .atMost(
                        Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {

                            var found = getJson(
                                    listUrl,
                                    token,
                                    200);

                            assertThat(
                                    found.size())
                                    .isEqualTo(
                                            1);

                            assertOrder(
                                    found.get(0),
                                    email);
                        });

        var order = getJson(
                listUrl,
                token,
                200)
                .get(0);

        long id = order.get("orderId")
                .asLong();

        assertPersistedInPostgres(id, email);
        assertOrderCreatedEvent(id, email);

        assertThat(
                getJson(
                        listUrl
                                + "/"
                                + id,
                        token,
                        200))
                .isEqualTo(
                        order);

        save(
                "order-response.json",
                order);

        await()
                .alias(
                        "Notification arrives over SMTP")
                .atMost(
                        Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {

                            var messages = getJson(
                                    mailApi()
                                            + "/messages",
                                    null,
                                    200)
                                    .get(
                                            "messages");

                            assertThat(
                                    messages.size())
                                    .isEqualTo(
                                            1);

                            var message = getJson(
                                    mailApi()
                                            + "/message/"
                                            + messages
                                                    .get(0)
                                                    .get("ID")
                                                    .asString(),
                                    null,
                                    200);

                            assertThat(
                                    message.get("From")
                                            .get("Address")
                                            .asString())
                                    .isEqualTo(
                                            FROM);

                            assertThat(
                                    message.get("To")
                                            .size())
                                    .isEqualTo(
                                            1);

                            assertThat(
                                    message.get("To")
                                            .get(0)
                                            .get("Address")
                                            .asString())
                                    .isEqualTo(
                                            email);

                            assertThat(
                                    message.get("Subject")
                                            .asString())
                                    .isEqualTo(
                                            "Orden creada #"
                                                    + id);

                            assertThat(
                                    message.get("Text")
                                            .asString()
                                            .replace(
                                                    "\r\n",
                                                    "\n"))
                                    .isEqualTo(
                                            """
                                                    Hola,

                                                    Tu orden fue creada correctamente.

                                                    Número de orden: %s
                                                    Total: $69.00
                                                    Estado: CREATED

                                                    Gracias por comprar en Camisetas360.
                                                    """.formatted(id));

                            save(
                                    "delivered-email.json",
                                    message);
                        });

        assertDeliveryPersisted(id, email);

        return new Flow(
                id,
                token,
                order);
    }

    private void assertOrder(
            JsonNode order,
            String email) throws Exception {

        assertThat(
                order.get("orderId")
                        .asLong())
                .isPositive();

        assertThat(
                order.get("userEmail")
                        .asString())
                .isEqualTo(
                        email);

        assertThat(
                order.get("totalAmount")
                        .asDouble())
                .isEqualTo(
                        69.0);

        assertThat(
                order.get("status")
                        .asString())
                .isEqualTo(
                        "CREATED");

        assertThat(
                Instant.parse(
                        order.get("createdAt")
                                .asString()))
                .isNotNull();

        var items = new ArrayList<JsonNode>();

        order.get("items")
                .forEach(
                        items::add);

        var expected = JSON.readTree(
                ITEMS);

        assertThat(
                items)
                .containsExactlyInAnyOrder(
                        expected.get(0),
                        expected.get(1));
    }

    private void assertPersistedInPostgres(long id, String email) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword())) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            try (var query = connection.prepareStatement(
                    "SELECT user_email, total_amount, status, created_at FROM orders WHERE id = ?")) {
                query.setLong(1, id);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).as("Committed order exists in PostgreSQL").isTrue();
                    assertThat(result.getString("user_email")).isEqualTo(email);
                    assertThat(result.getDouble("total_amount")).isEqualTo(69.0);
                    assertThat(result.getString("status")).isEqualTo("CREATED");
                    assertThat(result.getObject("created_at", OffsetDateTime.class).toInstant())
                            .isBetween(Instant.now().minusSeconds(120), Instant.now());
                    assertThat(result.next()).isFalse();
                }
            }
            var storedItems = new ArrayList<String>();
            try (var query = connection.prepareStatement(
                    "SELECT sku, quantity, unit_price FROM order_items WHERE order_id = ?")) {
                query.setLong(1, id);
                try (var result = query.executeQuery()) {
                    while (result.next()) {
                        storedItems.add(result.getString("sku") + ":" + result.getInt("quantity")
                                + ":" + result.getDouble("unit_price"));
                    }
                }
            }
            assertThat(storedItems).containsExactlyInAnyOrder("CAM-Ñ-東京:2:19.5", "CAM-002:3:10.0");
            try (var query = connection.createStatement();
                 var result = query.executeQuery(
                         "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
                var versions = new ArrayList<String>();
                while (result.next()) versions.add(result.getString(1));
                assertThat(versions).containsExactly("1", "2", "3");
            }
        }
        Files.writeString(logs.resolve("postgres-persistence.txt"),
                "PostgreSQL: committed order " + id + "; owner=" + email
                        + "; total=69.0; status=CREATED; items=2; Flyway=1,2,3\n");
    }

    private HashMap<String, String> databaseProperties(Map<String, String> common, PostgreSQLContainer database) {
        var properties = new HashMap<>(common);
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        return properties;
    }

    private void assertCheckoutPersisted(UUID requestId, String email) throws Exception {
        try (var connection = DriverManager.getConnection(carritoPostgres.getJdbcUrl(),
                carritoPostgres.getUsername(), carritoPostgres.getPassword())) {
            try (var query = connection.prepareStatement(
                    "SELECT user_email,total_amount,status,created_at FROM checkout_requests WHERE id=?")) {
                query.setObject(1, requestId);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).as("Checkout committed in carrito's own PostgreSQL").isTrue();
                    assertThat(result.getString("user_email")).isEqualTo(email);
                    assertThat(result.getDouble("total_amount")).isEqualTo(69.0);
                    assertThat(result.getString("status")).isEqualTo("PROCESSING");
                    assertThat(result.getObject("created_at", OffsetDateTime.class).toInstant())
                            .isBetween(Instant.now().minusSeconds(120), Instant.now());
                    assertThat(result.next()).isFalse();
                }
            }
            var items = new ArrayList<String>();
            try (var query = connection.prepareStatement(
                    "SELECT sku,quantity,unit_price FROM checkout_request_items WHERE checkout_id=?")) {
                query.setObject(1, requestId);
                try (var result = query.executeQuery()) {
                    while (result.next()) items.add(result.getString("sku") + ":" + result.getInt("quantity") + ":" + result.getDouble("unit_price"));
                }
            }
            assertThat(items).containsExactlyInAnyOrder("CAM-Ñ-東京:2:19.5", "CAM-002:3:10.0");
            assertSingleMigration(connection);
        }
        Files.writeString(logs.resolve("carrito-persistence.txt"),
                "Carrito PostgreSQL: committed checkout=" + requestId + "; owner=" + email + "; total=69; items=2; Flyway=1\n");
    }

    private void assertDeliveryPersisted(long id, String email) {
        await().alias("SMTP success committed in notifications PostgreSQL").atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (var connection = DriverManager.getConnection(notificationsPostgres.getJdbcUrl(),
                    notificationsPostgres.getUsername(), notificationsPostgres.getPassword());
                 var query = connection.createStatement();
                 var result = query.executeQuery("SELECT sender,recipient,subject,body,sent_at FROM email_deliveries")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("sender")).isEqualTo(FROM);
                assertThat(result.getString("recipient")).isEqualTo(email);
                assertThat(result.getString("subject")).isEqualTo("Orden creada #" + id);
                assertThat(result.getString("body")).isEqualTo("""
                        Hola,

                        Tu orden fue creada correctamente.

                        Número de orden: %s
                        Total: $69.00
                        Estado: CREATED

                        Gracias por comprar en Camisetas360.
                        """.formatted(id));
                assertThat(result.getObject("sent_at", OffsetDateTime.class).toInstant())
                        .isBetween(Instant.now().minusSeconds(120), Instant.now());
                assertThat(result.next()).as("Exactly one successful delivery logged").isFalse();
                assertSingleMigration(connection);
            }
        });
    }

    private void assertSingleMigration(java.sql.Connection connection) throws Exception {
        try (var query = connection.createStatement();
             var result = query.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("1");
            assertThat(result.next()).isFalse();
        }
    }

    private void assertQueueHasConsumer(
            String queue) throws Exception {

        var credentials = rabbit.getAdminUsername()
                + ":"
                + rabbit.getAdminPassword();

        var request = HttpRequest.newBuilder(
                URI.create(
                        "http://"
                                + rabbit.getHost()
                                + ":"
                                + rabbit.getHttpPort()
                                + "/api/queues/%2F/"
                                + queue))
                .timeout(
                        Duration.ofSeconds(10))
                .header(
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                credentials
                                                        .getBytes(
                                                                StandardCharsets.UTF_8)))
                .GET()
                .build();

        var response = http.send(
                request,
                HttpResponse.BodyHandlers
                        .ofString());

        assertThat(
                response.statusCode())
                .as(
                        "Queue " + queue)
                .isEqualTo(
                        200);

        var consumers = JSON.readTree(
                response.body())
                .get(
                        "consumers");

        assertThat(
                consumers)
                .as(
                        "Consumer statistics available for "
                                + queue)
                .isNotNull();

        assertThat(
                consumers.asInt())
                .isGreaterThanOrEqualTo(
                        1);
    }

    private void assertOrderCreatedEvent(long orderId, String email) {
        await().alias("Actual order.created on RabbitMQ").atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var captured = rabbitManagement("POST", "/api/queues/%2F/e2e.order-created.capture/get",
                    "{\"count\":10,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\",\"truncate\":50000}", 200);
            assertThat(captured.size()).isEqualTo(1);
            assertThat(captured.get(0).get("routing_key").asString()).isEqualTo("order.created");
            var event = JSON.readTree(captured.get(0).get("payload").asString());
            assertThat(event.get("orderId").asLong()).isEqualTo(orderId);
            assertThat(event.get("userEmail").asString()).isEqualTo(email);
            assertThat(event.get("totalAmount").asDouble()).isEqualTo(69.0);
            var eventItems = new ArrayList<JsonNode>();
            event.get("items").forEach(eventItems::add);
            var expected = JSON.readTree(ITEMS);
            assertThat(eventItems).containsExactlyInAnyOrder(expected.get(0), expected.get(1));
            assertThat(Instant.parse(event.get("occurredAt").asString()))
                    .isBetween(Instant.now().minusSeconds(120), Instant.now());
            save("order-created-event.json", event);
        });
    }

    private JsonNode rabbitManagement(String method, String path, String body, int expectedStatus) throws Exception {
        var credentials = rabbit.getAdminUsername() + ":" + rabbit.getAdminPassword();
        var request = HttpRequest.newBuilder(URI.create(rabbit.getHttpUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isEqualTo(expectedStatus);
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    private HttpResponse<String> request(
            String method,
            String url,
            String token,
            String body) throws Exception {

        var builder = HttpRequest.newBuilder(
                URI.create(url))
                .timeout(
                        Duration.ofSeconds(10));

        if (token != null) {
            builder.header(
                    "Authorization",
                    "Bearer " + token);
        }

        if (body != null) {
            builder.header(
                    "Content-Type",
                    "application/json");
        }

        return http.send(
                builder.method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers
                                        .noBody()
                                : HttpRequest.BodyPublishers
                                        .ofString(body))
                        .build(),
                HttpResponse.BodyHandlers
                        .ofString());
    }

    private JsonNode getJson(
            String url,
            String token,
            int expectedStatus) throws Exception {

        var response = request(
                "GET",
                url,
                token,
                null);

        assertThat(
                response.statusCode())
                .as(
                        "GET "
                                + url
                                + ": "
                                + response.body())
                .isEqualTo(
                        expectedStatus);

        return JSON.readTree(
                response.body());
    }

    private String mailApi() {
        return "http://"
                + smtp.getHost()
                + ":"
                + smtp.getMappedPort(8025)
                + "/api/v1";
    }

    private void save(
            String name,
            JsonNode value) throws Exception {

        Files.writeString(
                logs.resolve(name),
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(
                                value));
    }

    private <T extends AutoCloseable> T register(
            T resource) {

        resources.add(
                resource);

        return resource;
    }

    @AfterEach
    void stopIsolatedSystem()
            throws Exception {

        Exception failure = null;

        try {

            if (postgres != null && postgres.isRunning()) {
                Files.writeString(logs.resolve("postgres.log"), postgres.getLogs());
            }
            if (carritoPostgres != null && carritoPostgres.isRunning()) {
                Files.writeString(logs.resolve("carrito-postgres.log"), carritoPostgres.getLogs());
            }
            if (notificationsPostgres != null && notificationsPostgres.isRunning()) {
                Files.writeString(logs.resolve("notifications-postgres.log"), notificationsPostgres.getLogs());
            }

            if (rabbit != null
                    && rabbit.isRunning()) {

                Files.writeString(
                        logs.resolve(
                                "rabbitmq.log"),
                        rabbit.getLogs());
            }

            if (smtp != null
                    && smtp.isRunning()) {

                Files.writeString(
                        logs.resolve(
                                "smtp.log"),
                        smtp.getLogs());
            }

        } catch (Exception exception) {

            failure = exception;
        }

        for (var resource : resources.reversed()) {

            try {

                resource.close();

            } catch (Exception exception) {

                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(
                            exception);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private record Flow(
            long orderId,
            String token,
            JsonNode order) {
    }
}
```

5. **Por qué:** cada servicio es propietario de sus datos y prueba el mismo motor/esquema real que utiliza en ejecución.

6. **Prueba que valida el cambio:** tests clean verify: 0 Failures, 0 Errors; sin mocks de PostgreSQL.

SHA-256: `45240b33b95bd278895d891e56d8f2c23b7126e9eb8fbfdffa81c7e8f2a1d112`
