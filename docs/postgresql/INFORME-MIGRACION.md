# Migración H2 → PostgreSQL — auditoría, cambios y verificación

Informe del 4 de octubre de 2026. Los cambios están aplicados en el workspace.
Cada archivo incluye problema previo, cambio, razón, validación y **código completo
final**, preparado para copiar. Los archivos nuevos se identifican como nuevos;
no se presenta infraestructura propuesta como si ya existiera o estuviera desplegada.
Este informe es una instantánea; no es una migración adicional ni un archivo de aplicación.

## Auditoría anterior a los cambios

La auditoría fue comunicada antes de modificar código. H2 aparecía en tres módulos:

| Ubicación previa | Uso encontrado | Impacto de retirarlo |
| --- | --- | --- |
| orders/pom.xml | H2 runtime y spring-boot-h2console | Requiere driver PostgreSQL y elimina consola |
| catalog/pom.xml | H2 runtime y spring-boot-h2console | También debe migrarse para eliminar H2 del repositorio |
| orders/src/main/resources/application.yaml | ordersdb en memoria, org.h2.Driver, consola, ddl-auto=update | DB real externa por variables, persistencia y schema versionado |
| catalog/src/main/resources/application.yaml | camisetasdb en memoria, consola y ddl-auto=update | PostgreSQL independiente, Flyway y seed ejecutado una vez |
| OrdersApplicationTests y CatalogApplicationTests | URLs H2 de smoke y create-drop | Requieren Docker/PostgreSQL real |
| OrderRepositoryTest, ProductRepositoryTest y OrderServicePersistenceIT | H2 implícita de DataJpaTest y create-drop | Replace.NONE y mismos scripts Flyway de la aplicación |
| tests/e2e/CheckoutFlowE2EIT | jdbc:h2:mem:e2e_UUID en el proceso orders | PostgreSQLContainer por método y credenciales obtenidas del contenedor |
| tests/messaging/pom.xml y MessagingRabbitIT | H2 test, EmbeddedDatabaseBuilder, create-drop | PostgreSQL/Flyway en la integración AMQP |
| catalog/src/main/resources/data.sql | Tres productos insertados al arrancar | Migración V2 para no duplicarlos en una DB persistente |
| catalog/config/DataSeederConfig | Texto/logs H2, runner si la DB está vacía | Texto corregido; comportamiento conservado |

No había dependencia PostgreSQL, Flyway, migraciones, dialecto H2 explícito, perfiles
exclusivos H2 ni workaround SQL específico H2. Solo existían application.yaml comunes;
local/prod se activaban desde Docker/entorno sin archivos de perfil dedicados.
Las entidades reales eran Order, OrderItem y Product, con repositorios JpaRepository.
OrderRepository exponía findByUserEmailOrderByCreatedAtDesc; no había API de paginación
ni constraint única de negocio declarada. Los importes eran Double en entidades/DTO/eventos.

Compose no tenía PostgreSQL. El E2E ya ejecutaba carrito/orders/notifications como
JARs reales con RabbitMQ, Mailpit e issuer JWT local. CI ya tenía verify y gate;
los Dockerfiles omitían tests. El despliegue AWS encontrado era EC2/SSM, no EKS.

## Resultado y evidencia

Los siete módulos terminaron con BUILD SUCCESS. Conteos extraídos de los XML JUnit
actuales (Surefire + Failsafe), sin sumar resultados de ejecuciones fallidas anteriores:

| Módulo | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |

| auth | 32 | 0 | 0 | 0 |
| catalog | 30 | 0 | 0 | 0 |
| carrito | 57 | 0 | 0 | 0 |
| orders | 84 | 0 | 0 | 0 |
| notifications | 47 | 0 | 0 | 0 |
| tests/messaging | 8 | 0 | 0 | 0 |
| tests/e2e | 2 | 0 | 0 | 0 |

| **TOTAL** | 260 | 0 | 0 | 0 |


Evidencia adicional:

- PostgreSQLContainer postgres:17 iniciado en repositorios, integración y ambos E2E.
- RabbitMQContainer rabbitmq:4-management y Mailpit v1.31.3 iniciados en ambos E2E.
- Flyway V1/V2/V3 aplicado y registrado; Hibernate validate al arrancar.
- Orden comprometida y leída por JDBC independiente, con propietario, total 69,
  CREATED y dos ítems exactos, además de las lecturas HTTP reales.
- Usuario ajeno recibe 404 sin datos de la orden y listado vacío; el dueño conserva acceso.
- order.created capturado y verificado en cola durable independiente; correo exacto
  enviado por notifications a Mailpit, sin mocks E2E.
- PostgreSQL local Compose healthy en dos instancias; SQL real y dato conservado
  tras restart. El proyecto temporal camisetas360-postgres-verification fue retirado.
- Las cinco imágenes Docker locales se construyeron con JARs verificados.
- JaCoCo HTML/XML y datos de ejecución generados para los cinco servicios.
- Árboles Maven de orders/catalog/messaging/E2E inspeccionados: sin H2 transitiva.
- Ambos overlays Kustomize se renderizaron con éxito. POM XML válido y diff --check limpio.

Logs completos: orders/verify-postgresql.log, catalog/verify-postgresql.log y
tests/e2e/verify-postgresql.log, además de los demás módulos. JUnit/JaCoCo están
en target de cada servicio. E2E guarda postgres.log, postgres-persistence.txt,
order-created-event.json, delivered-email.json, order-response.json y
foreign-order-response.json bajo tests/e2e/target/e2e-logs. Estos artefactos
temporales están ignorados por Git; reproducir verify vuelve a generarlos.

La migración detectó una diferencia de nombres entre Hibernate manual y Spring
Boot (unitPrice/unit_price), resuelta con nombres JPA explícitos. RabbitMQ 4 rechazó
la primera cola de captura temporal no exclusiva: se usó una cola durable soportada,
sin habilitar características obsoletas. Las ejecuciones finales son las de la tabla.

El Compose único local se verificó con puertos SQL publicados únicamente en loopback para DBeaver,
handshake PostgreSQL desde el host y el flujo completo con RabbitMQ/Mailpit. Código y evidencia adicional:
[tests/compose/INFORME-VALIDACION.md](../../tests/compose/INFORME-VALIDACION.md).

La ampliación posterior añade PostgreSQL propio a auth/carrito/notifications. Resultado y código completo: [BASES-POR-MICROSERVICIO.md](BASES-POR-MICROSERVICIO.md).

## Comandos verificados

Desde orders:

```bash
./mvnw -B -ntp clean verify
```

Desde la raíz, después de verificar los JAR participantes:

```bash
./orders/mvnw -B -ntp -f tests/e2e/pom.xml -Pe2e clean verify
./orders/mvnw -B -ntp -f tests/messaging/pom.xml -Prabbit clean verify
```

En Windows se ejecutaron sus equivalentes Maven Wrapper:

```powershell
# Desde orders
.\mvnw.cmd -B -ntp clean verify
# Desde la raíz
.\orders\mvnw.cmd -B -ntp -f tests\e2e\pom.xml -Pe2e clean verify
.\orders\mvnw.cmd -B -ntp -f tests\messaging\pom.xml -Prabbit clean verify
```

## Checklist y límites

- [x] H2 eliminado de dependencias, URLs, driver, consola y persistencia de tests.
- [x] PostgreSQL driver configurado.
- [x] PostgreSQL local Compose probado, con volumen persistente.
- [x] PostgreSQL Testcontainers funcionando.
- [x] Flyway funcionando en aplicaciones, integración y E2E.
- [x] Integration tests verdes localmente.
- [x] E2E verdes localmente.
- [x] JaCoCo generado.
- [ ] GitHub Actions verde remoto: workflow actualizado; requiere publicar cambios y observar el run.
- [x] EKS preparado y overlays renderizados.
- [ ] EKS desplegado/verificado: requiere cluster, imágenes y Secrets reales.
- [x] PostgreSQL local accesible solo por loopback; PostgreSQL EKS sin exposición pública.
- [x] Credenciales PostgreSQL externas; ningún secreto real nuevo en repositorio.
- [x] ddl-auto=validate en producción y los demás entornos.

No se afirma que un stack de observabilidad remoto esté instalado. Se agregaron
Actuator/probes y guía de operación. El Compose original conserva defaults locales
de ejemplo para RabbitMQ; EKS exige sus valores a través de orders-runtime.

Double conserva un riesgo real de precisión monetaria: no se sustituyó por NUMERIC
a escondidas. El cambio coordinado propuesto es BigDecimal en entidades, contratos,
cálculos y eventos, con moneda/escala acordadas y NUMERIC(p,s) en una migración futura.
No se creó unicidad de negocio ficticia. La tabla products conserva su nullabilidad.

Flyway V1 está pensada para una DB vacía. No se activa baseline automático ni se
importan datos de instancias previas. Si hay datos externos, se necesita un traslado
planificado. La H2 encontrada era en memoria.

El E2E comprueba el camino real; no añade outbox ni garantiza exactly-once/atomicidad
entre commit DB y publicación AMQP. El lifecycle actual de soporte JPA es secuencial
por clase; no habilitar ejecución paralela sin adaptar el contenedor por contexto.

En EKS académico se mantiene un StatefulSet PostgreSQL con PVC y Secrets externos.
Para producción comercial se propone evaluar Amazon RDS/Aurora PostgreSQL y roles
mínimos, TLS, backups/PITR y recuperación probada. Ver OPERACION.md y deploy/eks/README.md.

## Archivos y código completo final

Las referencias H2 que puedan leerse en esta auditoría o en el guard CI son texto
de documentación/detección, no dependencias ni configuraciones activas.


### `.env.example`

1. **Ruta:** [.env.example](../../.env.example)

2. **Problema previo:** Archivo nuevo: faltaba inventario de variables para las bases locales separadas.

3. **Cambio realizado:** Nombres DB/usuarios de ejemplo y contraseñas vacías para PostgreSQL, RabbitMQ y SMTP.

4. **Por qué:** No contiene secretos reales; se copia a .env ignorado y se completa externamente.

5. **Prueba/validación:** Compose exige las variables DB; inspección confirma valores password vacíos.

6. **Código completo final:**

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

SHA-256 de los bytes del archivo: `5f2dcfcaf0748640537ef44c2be99424a78dd12dc69bfd1d0f4ccc533eff6ebe`


### `.github/workflows/ci.yml`

1. **Ruta:** [.github/workflows/ci.yml](../../.github/workflows/ci.yml)

2. **Problema previo:** CI no comprobaba Docker para suites JPA ni impedía reintroducir H2; solo publicaba JARs de participantes E2E.

3. **Cambio realizado:** Checks Docker para orders/catalog, guard del árbol H2, JARs verificados de los cinco servicios y nombres que reflejan PostgreSQL en mensajería/E2E. Se añadió un job Compose obligatorio con las dos bases y los cinco servicios reales. Se añadió un job Compose obligatorio con las dos bases y los cinco servicios reales.

4. **Por qué:** Conserva clean verify, JaCoCo, set -euo pipefail y gate obligatorio; no usa continue-on-error ni omisión de pruebas.

5. **Prueba/validación:** Las mismas suites pasaron localmente (260 tests). La ejecución GitHub remota está pendiente de publicar cambios; no se afirma CI remoto verde.

6. **Código completo final:**

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

SHA-256 de los bytes del archivo: `6dbbae9a9635019535862c86cc09ebd7ce5c738470293a607fb4f65a0ddca7ca`


### `.github/workflows/deploy-backend.yml`

1. **Ruta:** [.github/workflows/deploy-backend.yml](../../.github/workflows/deploy-backend.yml)

2. **Problema previo:** El workflow reconstruía código dentro de Docker con tests omitidos y publicaba solo tres imágenes.

3. **Cambio realizado:** Descarga los cinco JARs aprobados por CI, los coloca en sus contextos y agrega imágenes orders/notifications. Conserva el destino EC2 existente.

4. **Por qué:** Empaqueta exactamente los artefactos probados. EKS se prepara aparte sin inventar cluster o credenciales ni desplegar automáticamente.

5. **Prueba/validación:** Construcción local de las cinco imágenes exitosa. ECR/EC2 remoto no ejecutado.

6. **Código completo final:**

```yaml
name: Deploy to AWS ECR & EC2 via SSM

on:
  push:
    branches: [ "main" ]

permissions:
  contents: read

concurrency:
  group: backend-deploy-main
  cancel-in-progress: false

env:
  AWS_REGION: us-east-1 # Cambia a tu región de AWS (ej. us-east-2, sa-east-1)

jobs:
  tests:
    name: Verify backend before deployment
    uses: ./.github/workflows/ci.yml

  build-and-deploy:
    needs: tests
    if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' && needs.tests.result == 'success' }}
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - name: Checkout code
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          persist-credentials: false

      - name: Configure AWS Credentials
        # Images contain the same JARs verified by the required CI workflow.
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-session-token: ${{ secrets.AWS_SESSION_TOKEN }}
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Download JARs verified by CI
        uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8
        with:
          pattern: jar-*
          path: verified-jars

      - name: Place verified JARs in Docker build contexts
        run: |
          set -euo pipefail
          for service in auth catalog carrito orders notifications; do
            mkdir -p "$service/target"
            cp "verified-jars/jar-$service/"*.jar "$service/target/"
          done

      - name: Build, Tag and Push Auth
        env:
          REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          REPOSITORY: camisetas360-auth
        run: |
          docker build -t "$REGISTRY/$REPOSITORY:latest" ./auth
          docker push "$REGISTRY/$REPOSITORY:latest"

      - name: Build, Tag and Push Catalog
        env:
          REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          REPOSITORY: camisetas360-catalog
        run: |
          docker build -t "$REGISTRY/$REPOSITORY:latest" ./catalog
          docker push "$REGISTRY/$REPOSITORY:latest"

      - name: Build, Tag and Push Carrito
        env:
          REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          REPOSITORY: camisetas360-carrito
        run: |
          docker build -t "$REGISTRY/$REPOSITORY:latest" ./carrito
          docker push "$REGISTRY/$REPOSITORY:latest"

      - name: Build, Tag and Push Orders and Notifications
        env:
          REGISTRY: ${{ steps.login-ecr.outputs.registry }}
        run: |
          set -euo pipefail
          for service in orders notifications; do
            docker build -t "$REGISTRY/camisetas360-$service:latest" "./$service"
            docker push "$REGISTRY/camisetas360-$service:latest"
          done

      - name: Trigger Deployment on EC2 via AWS SSM
        # The existing EC2 deployment is retained; EKS manifests are prepared separately.
        run: |
          aws ssm send-command \
            --document-name "AWS-RunShellScript" \
            --targets "Key=tag:Name,Values=camisetas360-backend" \
            --parameters 'commands=[
              "cd /home/ec2-user/camisetas360",
              "aws ecr get-login-password --region ${{ env.AWS_REGION }} | docker login --username AWS --password-stdin ${{ steps.login-ecr.outputs.registry }}",
              "docker compose pull",
              "docker compose up -d --remove-orphans"
            ]' \
            --region ${{ env.AWS_REGION }}
```

SHA-256 de los bytes del archivo: `cc6cba3ae8f0b84295b811addf2f6feeec499d37819e276b701f3adb402a13a6`


### `.gitignore`

1. **Ruta:** [.gitignore](../../.gitignore)

2. **Problema previo:** Solo .env estaba ignorado, sin variantes de entorno y manifests secret exportados.

3. **Cambio realizado:** Ignora .env.*, permite únicamente .env.example e ignora *.secret.yaml/yml.

4. **Por qué:** Reduce riesgo de publicar archivos locales de credenciales sin ocultar el ejemplo vacío.

5. **Prueba/validación:** git check-ignore de archivos de secretos; inspección de .env.example y manifests sin credenciales.

6. **Código completo final:**

```text
# Compiled class file
*.class

# Log file
*.log

# BlueJ files
*.ctxt

# Mobile Tools for Java (J2ME)
.mtj.tmp/

# Package Files #
*.jar
*.war
*.nar
*.ear
*.zip
*.tar.gz
*.rar

# virtual machine crash logs, see http://www.java.com/en/download/help/error_hotspot.xml
hs_err_pid*
replay_pid*
.idea
.junie

.env
.env.*
!.env.example
*.secret.yaml
*.secret.yml
.vscode
```

SHA-256 de los bytes del archivo: `0f53749bbd16f27359103b009dc6aac1474e8100157c9b6e5b94824d91f8f4dd`


### `README.md`

1. **Ruta:** [README.md](../../README.md)

2. **Problema previo:** Indicaba que todas las suites funcionaban sin Docker y enlazaba documentos de testing ausentes.

3. **Cambio realizado:** Aclara PostgreSQL/Testcontainers, JARs verificados y el límite EC2/EKS; enlaza operación e informe reales.

4. **Por qué:** Los comandos y requisitos deben coincidir con la arquitectura migrada.

5. **Prueba/validación:** Revisión de comandos, resultados JUnit y existencia de enlaces locales.

6. **Código completo final:**

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

SHA-256 de los bytes del archivo: `afaba39b25079ebece8201431dfbf5acb382ae9efb5e061516bb01723134a955`


### `auth/.dockerignore`

1. **Ruta:** [auth/.dockerignore](../../auth/.dockerignore)

2. **Problema previo:** target estaba excluido, por lo que el Dockerfile no podía recibir el JAR generado por verify.

3. **Cambio realizado:** Permite únicamente target/*-SNAPSHOT.jar dentro del contexto Docker.

4. **Por qué:** Incluye el artefacto necesario y excluye fuentes, cachés y archivos de secretos. El patrón corresponde a las versiones SNAPSHOT actuales.

5. **Prueba/validación:** Construcción Docker local del servicio auth aprobada.

6. **Código completo final:**

```text
**
!target/
!target/*-SNAPSHOT.jar
```

SHA-256 de los bytes del archivo: `d44b5a313dec8f4cb4dd5a8038a1fb85378627c418b1ed10895913322d16d485`


### `auth/Dockerfile`

1. **Ruta:** [auth/Dockerfile](../../auth/Dockerfile)

2. **Problema previo:** El build Docker ejecutaba Maven con -DskipTests y podía producir un binario distinto del verificado por CI.

3. **Cambio realizado:** Imagen Java 21 de runtime que copia el JAR verificado, ejecuta con UID/GID 10001 y no incluye credenciales.

4. **Por qué:** La compilación y las pruebas se completan con Maven Wrapper antes del empaquetado. Un JAR ausente hace fallar Docker.

5. **Prueba/validación:** clean verify del servicio y docker build local exitoso de camisetas360-auth:postgres-verification.

6. **Código completo final:**

```text
# Build and verify with the Maven Wrapper before docker build.
# CI supplies the exact JAR that passed Surefire, Failsafe and E2E.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --chown=10001:10001 target/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

SHA-256 de los bytes del archivo: `69d91777c239a5bf2564da5277e0cf1aa17029acf71e64e5765f7bd4991c406c`


### `carrito/.dockerignore`

1. **Ruta:** [carrito/.dockerignore](../../carrito/.dockerignore)

2. **Problema previo:** target estaba excluido, por lo que el Dockerfile no podía recibir el JAR generado por verify.

3. **Cambio realizado:** Permite únicamente target/*-SNAPSHOT.jar dentro del contexto Docker.

4. **Por qué:** Incluye el artefacto necesario y excluye fuentes, cachés y archivos de secretos. El patrón corresponde a las versiones SNAPSHOT actuales.

5. **Prueba/validación:** Construcción Docker local del servicio carrito aprobada.

6. **Código completo final:**

```text
**
!target/
!target/*-SNAPSHOT.jar
```

SHA-256 de los bytes del archivo: `d44b5a313dec8f4cb4dd5a8038a1fb85378627c418b1ed10895913322d16d485`


### `carrito/Dockerfile`

1. **Ruta:** [carrito/Dockerfile](../../carrito/Dockerfile)

2. **Problema previo:** El build Docker ejecutaba Maven con -DskipTests y podía producir un binario distinto del verificado por CI.

3. **Cambio realizado:** Imagen Java 21 de runtime que copia el JAR verificado, ejecuta con UID/GID 10001 y no incluye credenciales.

4. **Por qué:** La compilación y las pruebas se completan con Maven Wrapper antes del empaquetado. Un JAR ausente hace fallar Docker.

5. **Prueba/validación:** clean verify del servicio y docker build local exitoso de camisetas360-carrito:postgres-verification.

6. **Código completo final:**

```text
# Build and verify with the Maven Wrapper before docker build.
# CI supplies the exact JAR that passed Surefire, Failsafe and E2E.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --chown=10001:10001 target/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

SHA-256 de los bytes del archivo: `69d91777c239a5bf2564da5277e0cf1aa17029acf71e64e5765f7bd4991c406c`


### `catalog/.dockerignore`

1. **Ruta:** [catalog/.dockerignore](../../catalog/.dockerignore)

2. **Problema previo:** target estaba excluido, por lo que el Dockerfile no podía recibir el JAR generado por verify.

3. **Cambio realizado:** Permite únicamente target/*-SNAPSHOT.jar dentro del contexto Docker.

4. **Por qué:** Incluye el artefacto necesario y excluye fuentes, cachés y archivos de secretos. El patrón corresponde a las versiones SNAPSHOT actuales.

5. **Prueba/validación:** Construcción Docker local del servicio catalog aprobada.

6. **Código completo final:**

```text
**
!target/
!target/*-SNAPSHOT.jar
```

SHA-256 de los bytes del archivo: `d44b5a313dec8f4cb4dd5a8038a1fb85378627c418b1ed10895913322d16d485`


### `catalog/Dockerfile`

1. **Ruta:** [catalog/Dockerfile](../../catalog/Dockerfile)

2. **Problema previo:** El build Docker ejecutaba Maven con -DskipTests y podía producir un binario distinto del verificado por CI.

3. **Cambio realizado:** Imagen Java 21 de runtime que copia el JAR verificado, ejecuta con UID/GID 10001 y no incluye credenciales.

4. **Por qué:** La compilación y las pruebas se completan con Maven Wrapper antes del empaquetado. Un JAR ausente hace fallar Docker.

5. **Prueba/validación:** clean verify del servicio y docker build local exitoso de camisetas360-catalog:postgres-verification.

6. **Código completo final:**

```text
# Build and verify with the Maven Wrapper before docker build.
# CI supplies the exact JAR that passed Surefire, Failsafe and E2E.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --chown=10001:10001 target/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

SHA-256 de los bytes del archivo: `69d91777c239a5bf2564da5277e0cf1aa17029acf71e64e5765f7bd4991c406c`


### `catalog/pom.xml`

1. **Ruta:** [catalog/pom.xml](../../catalog/pom.xml)

2. **Problema previo:** Dependencias de H2 y consola H2; sin driver PostgreSQL, Flyway ni contenedores PostgreSQL.

3. **Cambio realizado:** Elimina H2/consola; añade PostgreSQL JDBC, starter Flyway, soporte PostgreSQL de Flyway, Testcontainers y Actuator.

4. **Por qué:** Mantiene las versiones administradas por Spring Boot 4.1.1 y los plugins Surefire/Failsafe/JaCoCo existentes. Testcontainers 2 usa testcontainers-postgresql.

5. **Prueba/validación:** clean verify de catalog: BUILD SUCCESS. dependency:tree filtrado no encontró H2 ni transitivamente.

6. **Código completo final:**

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
	<groupId>com.example</groupId>
	<artifactId>catalog</artifactId>
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
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
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
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
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
			<groupId>net.datafaker</groupId>
			<artifactId>datafaker</artifactId>
			<version>2.1.0</version>
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
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
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

SHA-256 de los bytes del archivo: `d47baf331c129c8c6855796d3f382718d3439bc5095e9e57637917d23cb603ca`


### `catalog/src/main/java/com/camisetas360/catalog/config/DataSeederConfig.java`

1. **Ruta:** [catalog/src/main/java/com/camisetas360/catalog/config/DataSeederConfig.java](../../catalog/src/main/java/com/camisetas360/catalog/config/DataSeederConfig.java)

2. **Problema previo:** Comentarios y mensaje de log afirmaban que se poblaba H2 en memoria.

3. **Cambio realizado:** Actualiza esas referencias a PostgreSQL/base de datos; conserva el runner y su comprobación count()==0.

4. **Por qué:** Evita documentación/logs incorrectos sin alterar generación ni resultados esperados.

5. **Prueba/validación:** DataSeederConfigTest y smoke de catalog aprobados.

6. **Código completo final:**

```java
package com.camisetas360.catalog.config;

import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.repository.ProductRepository;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Configuración para poblar la base de datos PostgreSQL al arrancar la
 * aplicación.
 * Utiliza Datafaker para generar 3 equipaciones aleatorias por cada equipo de
 * las 5 grandes ligas.
 */
@Configuration
public class DataSeederConfig {

    @Bean
    CommandLineRunner initDatabase(ProductRepository repository) {
        return args -> {
            Faker faker = new Faker(Locale.of("es", "CL"));

            // Solo poblamos si la base de datos está vacía
            if (repository.count() == 0) {
                Map<String, List<String>> leaguesAndTeams = getLeaguesAndTeams();
                String[] kitTypes = { "Local", "Visita", "Tercera Equipación" };

                for (Map.Entry<String, List<String>> entry : leaguesAndTeams.entrySet()) {
                    String league = entry.getKey();
                    List<String> teams = entry.getValue();

                    for (String team : teams) {
                        for (String kitType : kitTypes) {
                            Product product = new Product();

                            String season = faker.options().option("2023/2024", "2024/2025", "Retro 1998",
                                    "Retro 2006");

                            product.setName("Camiseta " + team + " - " + kitType + " " + season);
                            product.setCategory(league);
                            product.setSku("FUT-" + faker.code().ean8());

                            double price = faker.number().randomDouble(2, 45, 120);
                            product.setPrice(price);

                            product.setStock(faker.number().numberBetween(10, 100));
                            product.setDescription("Camiseta " + kitType.toLowerCase() + " oficial del " + team
                                    + " para la competición " + league + ". Material transpirable de alta calidad.");

                            repository.save(product);
                        }
                    }
                }
                System.out.println("Base de datos poblada con " + repository.count() + " camisetas en total.");
            }
        };
    }

    private Map<String, List<String>> getLeaguesAndTeams() {
        return Map.of(
                "Premier League", List.of(
                        "Arsenal FC", "Manchester City", "Chelsea FC", "Liverpool FC",
                        "Manchester United", "Tottenham Hotspur", "Newcastle United",
                        "Brighton & Hove Albion", "Brentford FC", "AFC Bournemouth",
                        "Nottingham Forest", "Crystal Palace", "Aston Villa",
                        "Everton FC", "Leeds United", "Sunderland AFC", "Fulham FC",
                        "Ipswich Town", "Coventry City", "Hull City"),
                "La Liga", List.of(
                        "Real Madrid CF", "FC Barcelona", "Atlético de Madrid", "Villarreal CF",
                        "Real Sociedad", "Real Betis Balompié", "Athletic Club", "RC Celta de Vigo",
                        "Valencia CF", "RCD Espanyol", "Sevilla FC", "Levante UD",
                        "Real Racing Club", "Getafe CF", "RC Deportivo A Coruña", "Elche CF",
                        "Rayo Vallecano", "CA Osasuna", "Deportivo Alavés", "Málaga CF"),
                "Serie A", List.of(
                        "Inter de Milán", "Juventus de Turín", "AS Roma", "Como 1907",
                        "AC Milan", "Atalanta de Bérgamo", "SSC Nápoles", "Fiorentina",
                        "SS Lazio", "Bolonia", "US Sassuolo", "Génova", "Udinese",
                        "Parma", "Torino FC", "Cagliari", "Venezia FC", "Frosinone Calcio",
                        "AC Monza", "US Lecce"),
                "Bundesliga", List.of(
                        "Bayern Múnich", "Borussia Dortmund", "RB Leipzig", "Bayer 04 Leverkusen",
                        "VfB Stuttgart", "Eintracht Fráncfort", "TSG 1899 Hoffenheim", "SC Friburgo",
                        "1.FSV Mainz 05", "FC Augsburgo", "FC Colonia", "Borussia Mönchengladbach",
                        "1.FC Unión Berlín", "SV Werder Bremen", "Hamburgo SV", "FC Schalke 04",
                        "SV 07 Elversberg", "SC Paderborn 07"),
                "Ligue 1", List.of(
                        "París Saint-Germain", "AS Mónaco", "Racing Club de Estrasburgo",
                        "Olympique de Lyon", "LOSC Lille", "Stade Rennais FC",
                        "Olympique de Marsella", "RC Lens", "Paris FC", "OGC Niza",
                        "Toulouse FC", "FC Lorient", "AJ Auxerre", "Stade Brestois 29",
                        "Angers SCO", "Le Havre AC", "ESTAC Troyes", "Le Mans FC"));
    }
}
```

SHA-256 de los bytes del archivo: `b19e978752637a9c4092e70ad7f9ecd4e5cc5c6688baeb22010235c6b0e02c0d`


### `catalog/src/main/resources/application.yaml`

1. **Ruta:** [catalog/src/main/resources/application.yaml](../../catalog/src/main/resources/application.yaml)

2. **Problema previo:** jdbc:h2 en memoria, driver H2, consola web y Hibernate ddl-auto=update.

3. **Cambio realizado:** Conexión por SPRING_DATASOURCE_*; driver PostgreSQL, Flyway habilitado, clean deshabilitado y ddl-auto=validate. Actuator publica health/info bajo la seguridad del servicio.

4. **Por qué:** La aplicación prueba y usa un esquema versionado; no genera el esquema con Hibernate. En orders, readiness incluye DB y liveness evita depender de DB.

5. **Prueba/validación:** Arranque real de catalog en clean verify con PostgreSQL/Flyway; orders también arrancó en ambos E2E.

6. **Código completo final:**

```yaml
server:
  port: 8081

spring:
  application:
    name: catalog-service

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
    show-sql: false
    open-in-view: false

  sql:
    init:
      mode: never

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${ENTRA_ISSUER_URI:https://login.microsoftonline.com/e5372bf0-c5e3-4286-887c-79069f209c1f/v2.0}
          audiences:
            - ${ENTRA_AUDIENCE:719c999d-0f57-4ad5-9bd9-a72be5ca07e0}

app:
  cors:
    allowed-origin-patterns:
      - ${CORS_ALLOWED_ORIGIN:http://localhost:*}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
  health:
    readinessstate:
      enabled: true
    livenessstate:
      enabled: true
```

SHA-256 de los bytes del archivo: `fcf9542e104549ae67fd544b4e71dad12df8884bca4d86bfe57cb2a1ac9437f8`


### `catalog/src/main/resources/data.sql`

1. **Ruta:** [catalog/src/main/resources/data.sql](../../catalog/src/main/resources/data.sql)

2. **Problema previo:** Inicialización SQL repetida en cada arranque.

3. **Cambio realizado:** Archivo retirado; su código completo final se conserva en db/migration/V2__seed_existing_products.sql.

4. **Por qué:** El seed pasa a tener versión y checksum; no se eliminan datos útiles.

5. **Prueba/validación:** Comparación exacta con el original y smoke de catalog.

6. **Código completo final:**

Archivo eliminado. No copiar un data.sql adicional: el SQL completo se incluye en la sección de V2__seed_existing_products.sql.


### `catalog/src/main/resources/db/migration/V1__create_products.sql`

1. **Ruta:** [catalog/src/main/resources/db/migration/V1__create_products.sql](../../catalog/src/main/resources/db/migration/V1__create_products.sql)

2. **Problema previo:** Archivo nuevo: Hibernate administraba la tabla products.

3. **Cambio realizado:** Crea exactamente las columnas Product existentes con sus tipos, identidad y nullabilidad.

4. **Por qué:** Conserva el contrato real sin introducir nuevas reglas de negocio o columnas.

5. **Prueba/validación:** Flyway y Hibernate validate al arrancar catalog; pruebas de repositorio aprobadas.

6. **Código completo final:**

```sql
CREATE TABLE products (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sku VARCHAR(255),
    name VARCHAR(255),
    category VARCHAR(255),
    price DOUBLE PRECISION,
    stock INTEGER,
    description VARCHAR(255)
);
```

SHA-256 de los bytes del archivo: `93556239f9581b4a92e0059fe7f6e6f891a957231c1d2bcd4c948ae9bbeb551a`


### `catalog/src/main/resources/db/migration/V2__seed_existing_products.sql`

1. **Ruta:** [catalog/src/main/resources/db/migration/V2__seed_existing_products.sql](../../catalog/src/main/resources/db/migration/V2__seed_existing_products.sql)

2. **Problema previo:** data.sql insertaba tres filas en cada arranque, apropiado para una DB volátil pero duplicable en una persistente.

3. **Cambio realizado:** Traslada sin cambiar el contenido de data.sql a una migración Flyway V2.

4. **Por qué:** Flyway registra y aplica esas filas una sola vez.

5. **Prueba/validación:** Smoke exige CAM-001, CAM-002 y CAM-003, exactamente. SQL final coincide con data.sql original en Git.

6. **Código completo final:**

```sql
INSERT INTO products (sku, name, category, price, stock, description) VALUES ('CAM-001', 'Camiseta Titular 2026', 'Fútbol', 39.99, 100, 'Camiseta oficial de local para la temporada');
INSERT INTO products (sku, name, category, price, stock, description) VALUES ('CAM-002', 'Camiseta Visitante 2026', 'Fútbol', 39.99, 80, 'Camiseta alternativa de visitante');
INSERT INTO products (sku, name, category, price, stock, description) VALUES ('CAM-003', 'Camiseta Retro Edición Especial', 'Colección', 49.99, 25, 'Diseño clásico conmemorativo');
```

SHA-256 de los bytes del archivo: `c269b3a8e0bbf27cc2f59a6ea82648fa12ef4aad4c9892c2c44357b909d0ed43`


### `catalog/src/test/java/com/camisetas360/catalog/CatalogApplicationTests.java`

1. **Ruta:** [catalog/src/test/java/com/camisetas360/catalog/CatalogApplicationTests.java](../../catalog/src/test/java/com/camisetas360/catalog/CatalogApplicationTests.java)

2. **Problema previo:** Smoke H2/create-drop con seed data.sql en cada arranque.

3. **Cambio realizado:** Usa PostgreSQL y validate; conserva la exigencia de los tres SKU originales, ahora insertados por Flyway V2.

4. **Por qué:** Comprueba el arranque con migraciones reales y preservación del contenido del catálogo inicial.

5. **Prueba/validación:** Smoke de catalog aprobado dentro de clean verify.

6. **Código completo final:**

```java
package com.camisetas360.catalog;

import com.camisetas360.catalog.support.PostgresTestSupport;

import com.camisetas360.catalog.controller.CatalogController;
import com.camisetas360.catalog.repository.ProductRepository;
import com.camisetas360.catalog.models.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;



import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate"
})
class CatalogApplicationTests extends PostgresTestSupport {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    // IT-CFG-001 and IT-CAT-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(CatalogController.class)).isNotNull();
        // Real data.sql and real startup runner: the seed must remain the three SQL products.
        assertThat(context.getBean(ProductRepository.class).findAll())
                .extracting(Product::getSku)
                .containsExactlyInAnyOrder("CAM-001", "CAM-002", "CAM-003");
    }
}
```

SHA-256 de los bytes del archivo: `6041d628a068e91d846ed9f4675123b8b3d4b17a62c41f9735169bf768db7639`


### `catalog/src/test/java/com/camisetas360/catalog/repository/ProductRepositoryTest.java`

1. **Ruta:** [catalog/src/test/java/com/camisetas360/catalog/repository/ProductRepositoryTest.java](../../catalog/src/test/java/com/camisetas360/catalog/repository/ProductRepositoryTest.java)

2. **Problema previo:** @DataJpaTest usaba la base embebida y create-drop; ahora Flyway carga un seed real.

3. **Cambio realizado:** Replace.NONE y PostgreSQL/validate; limpia el seed en la transacción de cada fixture y conserva las assertions existentes.

4. **Por qué:** El test de repositorio debe aislar sus fixtures; el smoke separado sigue verificando íntegramente el seed real.

5. **Prueba/validación:** Las tres pruebas existentes pasaron; el rollback de cada test preserva el aislamiento.

6. **Código completo final:**

```java
package com.camisetas360.catalog.repository;

import com.camisetas360.catalog.models.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import com.camisetas360.catalog.support.PostgresTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryTest extends PostgresTestSupport {

    @Autowired
    private ProductRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void isolateRepositoryFixturesFromVersionedSeed() {
        repository.deleteAll();
        repository.flush();
        entityManager.clear();
    }

    // JPA-CAT-001
    @Test
    void save_shouldPreserveAllFields_whenProductIsReloaded() {
        var product = product("SKU-A", "Camiseta local", 19.5, 12);
        var id = repository.saveAndFlush(product).getId();
        entityManager.clear();

        assertThat(id).isNotNull();
        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded).isNotSameAs(product);
        assertThat(reloaded.getId()).isEqualTo(id);
        assertThat(reloaded.getSku()).isEqualTo("SKU-A");
        assertThat(reloaded.getName()).isEqualTo("Camiseta local");
        assertThat(reloaded.getCategory()).isEqualTo("Futbol");
        assertThat(reloaded.getPrice()).isEqualTo(19.5);
        assertThat(reloaded.getStock()).isEqualTo(12);
        assertThat(reloaded.getDescription()).isEqualTo("Temporada 2026");
    }

    // JPA-CAT-002
    @Test
    void findAll_shouldReturnOnlyStoredProducts_whenProductsExist() {
        repository.save(product("SKU-A", "Local", 19.5, 12));
        repository.save(product("SKU-B", "Visita", 25.0, 7));
        repository.flush();
        entityManager.clear();

        assertThat(repository.findAll())
                .extracting(Product::getSku, Product::getName, Product::getPrice, Product::getStock)
                .containsExactlyInAnyOrder(
                        tuple("SKU-A", "Local", 19.5, 12),
                        tuple("SKU-B", "Visita", 25.0, 7));
    }

    // JPA-CAT-002
    @Test
    void findAll_shouldReturnEmptyList_whenDatabaseIsEmpty() {
        assertThat(repository.findAll()).isEmpty();
    }

    private static Product product(String sku, String name, double price, int stock) {
        var product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setCategory("Futbol");
        product.setPrice(price);
        product.setStock(stock);
        product.setDescription("Temporada 2026");
        return product;
    }
}
```

SHA-256 de los bytes del archivo: `e33b51e9ac5213fdff26bc0c8616b067f583dc3e626dd847591f02073b0c292f`


### `catalog/src/test/java/com/camisetas360/catalog/support/PostgresTestSupport.java`

1. **Ruta:** [catalog/src/test/java/com/camisetas360/catalog/support/PostgresTestSupport.java](../../catalog/src/test/java/com/camisetas360/catalog/support/PostgresTestSupport.java)

2. **Problema previo:** Archivo nuevo necesario: las pruebas antes dependían de una base embebida explícita o elegida por Spring.

3. **Cambio realizado:** Inicia PostgreSQLContainer postgres:17 y registra URL/usuario/password dinámicos. Cierra el contenedor y descarta el contexto al terminar cada clase.

4. **Por qué:** Cada clase recibe una base temporal sin reuse ni dependencia de una DB CI permanente. No se omiten fallos de Docker.

5. **Prueba/validación:** Tests de repositorio y smoke de catalog; orders incluye también persistencia del servicio y 15 casos de esquema.

6. **Código completo final:**

```java
package com.camisetas360.catalog.support;

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
                    .withDatabaseName("catalog_test");
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

SHA-256 de los bytes del archivo: `15549420f6037a3f3ed6ecf261a175d09c2a0ba79b20a3431c531a75ccfdb07b`


### `deploy/eks/README.md`

1. **Ruta:** [deploy/eks/README.md](../../deploy/eks/README.md)

2. **Problema previo:** Archivo nuevo: no existía procedimiento EKS ni gestión de secretos/almacenamiento para PostgreSQL.

3. **Cambio realizado:** Describe requisitos CSI/CNI, Secrets creados externamente, imágenes reales, namespaces separados, probes, backups, límites académicos y evaluación RDS/Aurora.

4. **Por qué:** Los manifests necesitan valores reales de despliegue sin incluir secretos en Git.

5. **Prueba/validación:** Kustomize staging/production renderizados. No se ejecutó apply ni AWS.

6. **Código completo final:**

````markdown
# PostgreSQL académico en EKS

Los manifests preparan `orders` y su PostgreSQL privado. No despliegan el cluster EKS,
RabbitMQ, el proveedor JWT ni los demás servicios. El workflow existente continúa
desplegando a EC2; no se inventaron nombres de cluster, cuentas AWS ni repositorios ECR.

## Requisitos

- EKS con nodos EC2 y Amazon EBS CSI add-on autorizado mediante IAM/Pod Identity.
- CNI que aplique NetworkPolicy; habilitarlo explícitamente en Amazon VPC CNI.
- Acceso de los nodos a la imagen ECR elegida y a las imágenes PostgreSQL.
- RabbitMQ real accesible desde el namespace y configuración JWT real.

`storage-class.yaml` usa el driver estándar `ebs.csi.aws.com`, gp3 cifrado,
`WaitForFirstConsumer` y política `Retain`. EKS Auto Mode requiere el provisioner
`ebs.csi.eks.amazonaws.com`; no son intercambiables para volúmenes ya creados.
[Documentación EBS CSI](https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html).

## Secrets externos

Crear fuera de Git dos archivos locales de entorno, uno por Secret. Pueden llamarse
`.env.postgres-staging` y `.env.orders-staging`; `.env.*` está ignorado. No publicar
su contenido ni usar `--dry-run -o yaml` para guardar credenciales en el repositorio.

El primero contiene `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.
El segundo contiene `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`,
`RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`, `ENTRA_ISSUER_URI`, `ENTRA_AUDIENCE`.

```powershell
kubectl apply -f deploy/eks/staging/namespace.yaml
kubectl -n camisetas360-staging create secret generic postgres-credentials --from-env-file=.env.postgres-staging
kubectl -n camisetas360-staging create secret generic orders-runtime --from-env-file=.env.orders-staging
kubectl apply -f deploy/eks/storage-class.yaml
```

Estos comandos crean Secrets `Opaque`; los manifests solo contienen `secretKeyRef`.
Para rotar valores, usar el gestor externo elegido y reiniciar los consumidores.
Cambiar `POSTGRES_PASSWORD` en el Secret no cambia la contraseña de un cluster ya
inicializado: rotar también el rol PostgreSQL mediante un canal administrativo seguro.
Restringir RBAC sobre Secrets y configurar su protección conforme al cluster.

Para producción crear archivos y Secrets distintos en `camisetas360-production`.
Cada namespace genera su propio StatefulSet/PVC y datos; nunca reutilizar el PVC de staging.

## Imagen y despliegue

La referencia `camisetas360-orders:verified` es un marcador, no una imagen publicada.
En una copia de despliegue del overlay, añadir la imagen ECR verificada e inmutable:

```yaml
images:
  - name: camisetas360-orders
    newName: <URI-ECR-real>/camisetas360-orders
    newTag: <tag-inmutable-verificado>
```

Los valores entre ángulos deben sustituirse antes de desplegar. Luego:

```powershell
kubectl kustomize deploy/eks/staging
kubectl apply -k deploy/eks/staging
kubectl -n camisetas360-staging rollout status statefulset/postgres --timeout=300s
kubectl -n camisetas360-staging rollout status deployment/orders --timeout=300s
kubectl -n camisetas360-staging get pvc
kubectl -n camisetas360-staging get service postgres
kubectl -n camisetas360-staging logs deployment/orders
```

`orders` recibe `jdbc:postgresql://postgres:5432/$(POSTGRES_DB)` y credenciales por
Secret. Flyway aplica V1–V3 antes de que Hibernate valide; una migración o validación
fallida impide el arranque. El Service PostgreSQL es ClusterIP: no hay LoadBalancer,
NodePort, Ingress ni hostPort. NetworkPolicy admite 5432 solo desde pods `app: orders`
del mismo namespace, si el CNI aplica políticas.

El StatefulSet crea `data-postgres-0` mediante `volumeClaimTemplates`; el PVC conserva
datos ante recreaciones del pod. Una instancia no proporciona alta disponibilidad.
El volumen EBS está ligado a una zona; planificar recuperación y backups restaurables.
No eliminar el PVC para resolver un error de arranque. La retención no sustituye backups.
[StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/).

Los probes de `orders` consultan Actuator real. Readiness incluye conexión DB;
liveness solo verifica el estado de la aplicación, para evitar reinicios por una caída DB.
La API de órdenes conserva JWT, rol CUSTOMER y scope Orders.Read. Los probes exponen
solo UP/DOWN, sin datos internos. PostgreSQL usa `pg_isready`, con startupProbe para
permitir su inicialización y recuperación.

## Límite académico y operación

Este despliegue usa el usuario bootstrap de PostgreSQL también para Flyway/JPA por
simplicidad académica. Para operación comercial separar roles de migración y aplicación,
revisar TLS, mínimo privilegio, backups/PITR, alertas y recuperación probada. Evaluar
Amazon RDS/Aurora PostgreSQL como alternativa administrada; esta propuesta mantiene
PostgreSQL dentro de EKS por la restricción académica.

Observar logs de Flyway, errores JDBC/Hikari y consumidores RabbitMQ, latencia HTTP,
readiness, reinicios, capacidad del PVC y backups. Integrar las métricas y alertas con
la plataforma elegida; no se afirma que exista aún una instalación Prometheus/CloudWatch.
````

SHA-256 de los bytes del archivo: `1f41e6434e9eda9ea56b5bd25915c3ebfa1fee765481231c9c541b5c41789023`


### `deploy/eks/base/kustomization.yaml`

1. **Ruta:** [deploy/eks/base/kustomization.yaml](../../deploy/eks/base/kustomization.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** Base Kustomize con PostgreSQL, orders y NetworkPolicy.

4. **Por qué:** Evita divergencia de configuración entre overlays.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - postgres.yaml
  - orders.yaml
  - postgres-network-policy.yaml
```

SHA-256 de los bytes del archivo: `18f30bda6ed5a7940fb05cb15ce53a97f79c4cb43603975f4be63a79f3ef58ea`


### `deploy/eks/base/orders.yaml`

1. **Ruta:** [deploy/eks/base/orders.yaml](../../deploy/eks/base/orders.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** Deployment/Service orders con variables desde Secrets, URL postgres interna, probes reales y usuario no root.

4. **Por qué:** Usa Flyway/validate del JAR verificado; la imagen marker se sustituye antes de apply.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: orders
spec:
  type: ClusterIP
  selector:
    app: orders
  ports:
    - name: http
      port: 8084
      targetPort: http
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders
spec:
  replicas: 1
  selector:
    matchLabels:
      app: orders
  template:
    metadata:
      labels:
        app: orders
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: orders
          # Set an immutable ECR image using the overlay before applying.
          image: camisetas360-orders:verified
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: [ALL]
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - secretRef:
                name: orders-runtime
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: SPRING_PROFILES_ACTIVE
              value: prod
            - name: POSTGRES_DB
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_DB
            - name: SPRING_DATASOURCE_URL
              value: jdbc:postgresql://postgres:5432/$(POSTGRES_DB)
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_USER
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_PASSWORD
          resources:
            requests:
              cpu: 250m
              memory: 384Mi
            limits:
              cpu: "1"
              memory: 768Mi
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 5
            failureThreshold: 60
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 10
            timeoutSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 3
```

SHA-256 de los bytes del archivo: `ef7e694cfebd7c978b682efaf3c00946630ffba515a31397b405f008f5827a93`


### `deploy/eks/base/postgres-network-policy.yaml`

1. **Ruta:** [deploy/eks/base/postgres-network-policy.yaml](../../deploy/eks/base/postgres-network-policy.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** NetworkPolicy que permite 5432 únicamente desde pods orders en el mismo namespace.

4. **Por qué:** Restringe acceso DB cuando el CNI aplica NetworkPolicy; no abre tráfico público.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: postgres-ingress
spec:
  podSelector:
    matchLabels:
      app: postgres
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: orders
      ports:
        - protocol: TCP
          port: 5432
```

SHA-256 de los bytes del archivo: `2078cef158dc51e65842a5dc40cdb131b109e3e180e72002c1a0c655f7903c8b`


### `deploy/eks/base/postgres.yaml`

1. **Ruta:** [deploy/eks/base/postgres.yaml](../../deploy/eks/base/postgres.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** StatefulSet de PostgreSQL 17, PVC por volumeClaimTemplates, Services ClusterIP/headless, secretKeyRef y probes.

4. **Por qué:** Almacena datos al recrear pods y permite conexión solo por servicio interno.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres-headless
spec:
  clusterIP: None
  selector:
    app: postgres
  ports:
    - name: postgres
      port: 5432
      targetPort: postgres
---
apiVersion: v1
kind: Service
metadata:
  name: postgres
spec:
  type: ClusterIP
  selector:
    app: postgres
  ports:
    - name: postgres
      port: 5432
      targetPort: postgres
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
spec:
  serviceName: postgres-headless
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      terminationGracePeriodSeconds: 60
      containers:
        - name: postgres
          image: postgres:17
          ports:
            - name: postgres
              containerPort: 5432
          env:
            - name: POSTGRES_DB
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_DB
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_USER
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_PASSWORD
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          resources:
            requests:
              cpu: 250m
              memory: 256Mi
            limits:
              cpu: "1"
              memory: 1Gi
          startupProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\""]
            periodSeconds: 5
            failureThreshold: 60
          readinessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\""]
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 3
          livenessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\""]
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 6
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: [ReadWriteOnce]
        storageClassName: camisetas360-gp3
        resources:
          requests:
            storage: 10Gi
```

SHA-256 de los bytes del archivo: `0831dbfb519af02dd61068da2cd5d3d336213a75e13209338183eae781ef9aa5`


### `deploy/eks/production/kustomization.yaml`

1. **Ruta:** [deploy/eks/production/kustomization.yaml](../../deploy/eks/production/kustomization.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** Overlay producción sobre base en namespace distinto y perfil prod.

4. **Por qué:** Mantiene conexión, schema y probes coherentes con las pruebas.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: camisetas360-production
resources:
  - namespace.yaml
  - ../base
```

SHA-256 de los bytes del archivo: `74a17dbb8891eb4690c9b080033ab5c1549cc8bb2040a7838a9fe0f322647528`


### `deploy/eks/production/namespace.yaml`

1. **Ruta:** [deploy/eks/production/namespace.yaml](../../deploy/eks/production/namespace.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** Namespace camisetas360-production.

4. **Por qué:** No comparte almacenamiento ni secretos con staging.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: camisetas360-production
```

SHA-256 de los bytes del archivo: `644d7f3bdfa12fc4b82cad2a53d60ddd02a53cbb3e60d731d07a32ae31f41e35`


### `deploy/eks/staging/kustomization.yaml`

1. **Ruta:** [deploy/eks/staging/kustomization.yaml](../../deploy/eks/staging/kustomization.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** Overlay staging sobre base, namespace y perfil staging.

4. **Por qué:** Crea una DB/PVC propios; Flyway/validate proceden de la configuración común.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: camisetas360-staging
resources:
  - namespace.yaml
  - ../base
patches:
  - target:
      kind: Deployment
      name: orders
    patch: |-
      - op: replace
        path: /spec/template/spec/containers/0/env/1/value
        value: staging
```

SHA-256 de los bytes del archivo: `302fed43cb4cef7372a1cda003b41dc3798bd2faf04ec0815d8eb564346095d8`


### `deploy/eks/staging/namespace.yaml`

1. **Ruta:** [deploy/eks/staging/namespace.yaml](../../deploy/eks/staging/namespace.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** Namespace camisetas360-staging.

4. **Por qué:** Separa Secrets, pods y PVC de producción.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: camisetas360-staging
```

SHA-256 de los bytes del archivo: `045cbb25203b98b563de2d124021eb2977f11ac1934bf3c4c6144d44a0ff4d8e`


### `deploy/eks/storage-class.yaml`

1. **Ruta:** [deploy/eks/storage-class.yaml](../../deploy/eks/storage-class.yaml)

2. **Problema previo:** Archivo nuevo solicitado: no existían manifests Kubernetes/EKS para esta base.

3. **Cambio realizado:** StorageClass gp3 cifrado con Retain y WaitForFirstConsumer para EBS CSI estándar.

4. **Por qué:** Persistencia académica con almacenamiento EBS; documenta la variante EKS Auto Mode.

5. **Prueba/validación:** kubectl kustomize deploy/eks/staging y deploy/eks/production: render exitoso. storage-class revisada; apply/cluster remoto pendiente.

6. **Código completo final:**

```yaml
# Standard EKS with the Amazon EBS CSI add-on; see README for EKS Auto Mode.
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: camisetas360-gp3
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  encrypted: "true"
reclaimPolicy: Retain
allowVolumeExpansion: true
volumeBindingMode: WaitForFirstConsumer
```

SHA-256 de los bytes del archivo: `e2104110b2087e038f4cab88a4fd8c6e4dfea8bf1a262fea318e0ef54cec2ab5`


### `docker-compose.yml`

1. **Ruta:** [docker-compose.yml](../../docker-compose.yml)

2. **Problema previo:** No había base persistente para orders/catalog ni dependencias DB saludables.

3. **Cambio realizado:** Compose único de desarrollo local, PostgreSQL orders/catalog en loopback 5432/5433 para DBeaver, volúmenes separados, RabbitMQ y SMTP local Mailpit.

4. **Por qué:** orders conecta a postgres:5432 y catalog a su propia instancia. El Compose principal no publica puertos DB ni contiene contraseñas PostgreSQL.

5. **Prueba/validación:** Compose config válido; ambas DB healthy; SQL ejecutado y fila conservada tras restart en proyecto aislado. Se retiraron solo recursos temporales de verificación.

6. **Código completo final:**

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

SHA-256 de los bytes del archivo: `b6d1cc61588fac73de12ab4dee0c2426519ddf3af3e9cb5fdbedffb26e87ddeb`


### `docs/postgresql/OPERACION.md`

1. **Ruta:** [docs/postgresql/OPERACION.md](../../docs/postgresql/OPERACION.md)

2. **Problema previo:** Archivo nuevo: faltaban comandos, lifecycle, riesgos y operación de la estrategia PostgreSQL.

3. **Cambio realizado:** Documenta local, CI, integración/E2E, Flyway, aislamiento, comandos Linux/Windows, Double, schema existente y límites de entrega AMQP.

4. **Por qué:** Explica lo implementado y lo pendiente sin afirmar exactly-once, CI remoto o infraestructura observabilidad inexistente.

5. **Prueba/validación:** Comandos principales ejecutados con éxito y resultados contrastados con JUnit/logs.

6. **Código completo final:**

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

SHA-256 de los bytes del archivo: `33d4ac8f2c3211b9c85368b87abb03b082a52d6bacc072c3c518cd4f21c498da`


### `notifications/.dockerignore`

1. **Ruta:** [notifications/.dockerignore](../../notifications/.dockerignore)

2. **Problema previo:** target estaba excluido, por lo que el Dockerfile no podía recibir el JAR generado por verify.

3. **Cambio realizado:** Permite únicamente target/*-SNAPSHOT.jar dentro del contexto Docker.

4. **Por qué:** Incluye el artefacto necesario y excluye fuentes, cachés y archivos de secretos. El patrón corresponde a las versiones SNAPSHOT actuales.

5. **Prueba/validación:** Construcción Docker local del servicio notifications aprobada.

6. **Código completo final:**

```text
**
!target/
!target/*-SNAPSHOT.jar
```

SHA-256 de los bytes del archivo: `d44b5a313dec8f4cb4dd5a8038a1fb85378627c418b1ed10895913322d16d485`


### `notifications/Dockerfile`

1. **Ruta:** [notifications/Dockerfile](../../notifications/Dockerfile)

2. **Problema previo:** El build Docker ejecutaba Maven con -DskipTests y podía producir un binario distinto del verificado por CI.

3. **Cambio realizado:** Imagen Java 21 de runtime que copia el JAR verificado, ejecuta con UID/GID 10001 y no incluye credenciales.

4. **Por qué:** La compilación y las pruebas se completan con Maven Wrapper antes del empaquetado. Un JAR ausente hace fallar Docker.

5. **Prueba/validación:** clean verify del servicio y docker build local exitoso de camisetas360-notifications:postgres-verification.

6. **Código completo final:**

```text
# Build and verify with the Maven Wrapper before docker build.
# CI supplies the exact JAR that passed Surefire, Failsafe and E2E.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --chown=10001:10001 target/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

SHA-256 de los bytes del archivo: `69d91777c239a5bf2564da5277e0cf1aa17029acf71e64e5765f7bd4991c406c`


### `orders/.dockerignore`

1. **Ruta:** [orders/.dockerignore](../../orders/.dockerignore)

2. **Problema previo:** target estaba excluido, por lo que el Dockerfile no podía recibir el JAR generado por verify.

3. **Cambio realizado:** Permite únicamente target/*-SNAPSHOT.jar dentro del contexto Docker.

4. **Por qué:** Incluye el artefacto necesario y excluye fuentes, cachés y archivos de secretos. El patrón corresponde a las versiones SNAPSHOT actuales.

5. **Prueba/validación:** Construcción Docker local del servicio orders aprobada.

6. **Código completo final:**

```text
**
!target/
!target/*-SNAPSHOT.jar
```

SHA-256 de los bytes del archivo: `d44b5a313dec8f4cb4dd5a8038a1fb85378627c418b1ed10895913322d16d485`


### `orders/Dockerfile`

1. **Ruta:** [orders/Dockerfile](../../orders/Dockerfile)

2. **Problema previo:** El build Docker ejecutaba Maven con -DskipTests y podía producir un binario distinto del verificado por CI.

3. **Cambio realizado:** Imagen Java 21 de runtime que copia el JAR verificado, ejecuta con UID/GID 10001 y no incluye credenciales.

4. **Por qué:** La compilación y las pruebas se completan con Maven Wrapper antes del empaquetado. Un JAR ausente hace fallar Docker.

5. **Prueba/validación:** clean verify del servicio y docker build local exitoso de camisetas360-orders:postgres-verification.

6. **Código completo final:**

```text
# Build and verify with the Maven Wrapper before docker build.
# CI supplies the exact JAR that passed Surefire, Failsafe and E2E.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 spring && useradd --uid 10001 --gid spring --no-create-home spring
COPY --chown=10001:10001 target/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

SHA-256 de los bytes del archivo: `69d91777c239a5bf2564da5277e0cf1aa17029acf71e64e5765f7bd4991c406c`


### `orders/pom.xml`

1. **Ruta:** [orders/pom.xml](../../orders/pom.xml)

2. **Problema previo:** Dependencias de H2 y consola H2; sin driver PostgreSQL, Flyway ni contenedores PostgreSQL.

3. **Cambio realizado:** Elimina H2/consola; añade PostgreSQL JDBC, starter Flyway, soporte PostgreSQL de Flyway, Testcontainers y Actuator.

4. **Por qué:** Mantiene las versiones administradas por Spring Boot 4.1.1 y los plugins Surefire/Failsafe/JaCoCo existentes. Testcontainers 2 usa testcontainers-postgresql.

5. **Prueba/validación:** clean verify de orders: BUILD SUCCESS. dependency:tree filtrado no encontró H2 ni transitivamente.

6. **Código completo final:**

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
	<artifactId>orders</artifactId>
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
			<artifactId>spring-boot-starter-amqp</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
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
			<artifactId>spring-boot-devtools</artifactId>
			<scope>runtime</scope>
			<optional>true</optional>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-amqp-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
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
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
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

SHA-256 de los bytes del archivo: `c12b52cd81dc441e9b576fccac389c2d380ae10b2c472b6dbcb0f1e71d74b1a4`


### `orders/src/main/java/com/camisetas360/orders/model/Order.java`

1. **Ruta:** [orders/src/main/java/com/camisetas360/orders/model/Order.java](../../orders/src/main/java/com/camisetas360/orders/model/Order.java)

2. **Problema previo:** Columnas implícitas y campos requeridos del agregado sin NOT NULL.

3. **Cambio realizado:** Declara user_email, total_amount y created_at explícitamente; agrega nullable=false a esos campos y status. Conserva Double, Instant, identidad y relación existentes.

4. **Por qué:** Alinea JPA con Flyway y con contextos Hibernate manuales sin naming strategy Spring. No agrega columnas.

5. **Prueba/validación:** OrderRepositoryTest, OrderServicePersistenceIT, PostgresSchemaIT y MessagingRabbitIT aprobados.

6. **Código completo final:**

```java
package com.camisetas360.orders.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "total_amount", nullable = false)
    private Double totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<OrderItem> items = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public Double getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Double totalAmount) {
        this.totalAmount = totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items.clear();

        if (items != null) {
            items.forEach(this::addItem);
        }
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
```

SHA-256 de los bytes del archivo: `8cacbe33491e6abeb6ef6a0e3fefe5c83c06152c25d3944943323628d07c02c4`


### `orders/src/main/java/com/camisetas360/orders/model/OrderItem.java`

1. **Ruta:** [orders/src/main/java/com/camisetas360/orders/model/OrderItem.java](../../orders/src/main/java/com/camisetas360/orders/model/OrderItem.java)

2. **Problema previo:** unitPrice dependía del naming strategy; la relación permitía una FK nula y otros campos obligatorios eran nullable.

3. **Cambio realizado:** Declara unit_price; campos obligatorios NOT NULL y ManyToOne optional=false con order_id no nullable.

4. **Por qué:** Una línea de orden requiere su padre y datos completos; mantiene cascade/orphanRemoval en el agregado y el contrato Double.

5. **Prueba/validación:** Persistencia/cascada/orphan removal, NOT NULL, FK y flujo RabbitMQ sobre PostgreSQL real.

6. **Código completo final:**

```java
package com.camisetas360.orders.model;

import jakarta.persistence.*;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private Double unitPrice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Double getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(Double unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }
}
```

SHA-256 de los bytes del archivo: `58449c98e10aacb57391c46dc2d47bf4593cb21e2e674be91b77f2e1ba6ff36f`


### `orders/src/main/java/com/camisetas360/orders/security/SecurityConfig.java`

1. **Ruta:** [orders/src/main/java/com/camisetas360/orders/security/SecurityConfig.java](../../orders/src/main/java/com/camisetas360/orders/security/SecurityConfig.java)

2. **Problema previo:** La regla denyAll bloqueaba también los probes HTTP que necesita Kubernetes.

3. **Cambio realizado:** Permite únicamente GET de liveness/readiness; conserva JWT, rol CUSTOMER y scope Orders.Read para órdenes.

4. **Por qué:** Kubernetes puede evaluar salud sin desactivar seguridad ni exponer el resto de Actuator.

5. **Prueba/validación:** OrdersApplicationTests: probes UP, /api/v1/orders y /actuator/info sin token responden 401. JwtSecurityIT y E2E de ownership aprobados.

6. **Código completo final:**

```java
package com.camisetas360.orders.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

@Configuration
public class SecurityConfig {

        @Bean
        public JwtAuthenticationConverter jwtAuthenticationConverter() {

                JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

                converter.setJwtGrantedAuthoritiesConverter(
                                new JwtAuthoritiesConverter());

                return converter;
        }

        @Bean
        public SecurityFilterChain filterChain(
                        HttpSecurity http,
                        JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

                http
                                .csrf(csrf -> csrf.disable())

                                .cors(cors -> cors.disable())

                                .sessionManagement(session -> session.sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS))

                                .authorizeHttpRequests(auth -> auth

                                                .requestMatchers(HttpMethod.GET,
                                                                "/actuator/health/liveness",
                                                                "/actuator/health/readiness")
                                                .permitAll()

                                                .requestMatchers(
                                                                HttpMethod.OPTIONS,
                                                                "/**")
                                                .permitAll()

                                                .requestMatchers(
                                                                HttpMethod.GET,
                                                                "/api/v1/orders/**")
                                                .access(
                                                                new WebExpressionAuthorizationManager(
                                                                                "hasRole('CUSTOMER') " +
                                                                                                "and hasAuthority('SCOPE_Orders.Read')"))

                                                .anyRequest()
                                                .denyAll())

                                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                                                jwtAuthenticationConverter)));

                return http.build();
        }
}
```

SHA-256 de los bytes del archivo: `8d516a8a10efc16fd7fc16b7b8d495ad235dbe9ba861e4c1876c909473b03e30`


### `orders/src/main/resources/application.yaml`

1. **Ruta:** [orders/src/main/resources/application.yaml](../../orders/src/main/resources/application.yaml)

2. **Problema previo:** jdbc:h2 en memoria, driver H2, consola web y Hibernate ddl-auto=update.

3. **Cambio realizado:** Conexión por SPRING_DATASOURCE_*; driver PostgreSQL, Flyway habilitado, clean deshabilitado y ddl-auto=validate. Actuator publica health/info bajo la seguridad del servicio.

4. **Por qué:** La aplicación prueba y usa un esquema versionado; no genera el esquema con Hibernate. En orders, readiness incluye DB y liveness evita depender de DB.

5. **Prueba/validación:** Arranque real de orders en clean verify con PostgreSQL/Flyway; orders también arrancó en ambos E2E.

6. **Código completo final:**

```yaml
server:
  port: 8084

spring:
  application:
    name: orders-service

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
    show-sql: false
    open-in-view: false

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

logging:
  level:
    com.camisetas360.orders: INFO

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
      group:
        readiness:
          include: readinessState,db
  health:
    readinessstate:
      enabled: true
    livenessstate:
      enabled: true
```

SHA-256 de los bytes del archivo: `c6e138b44111c3fe9fe281cdb0182b582ec51e17a82cff10b7617efccfeadccd`


### `orders/src/main/resources/db/migration/V1__create_orders.sql`

1. **Ruta:** [orders/src/main/resources/db/migration/V1__create_orders.sql](../../orders/src/main/resources/db/migration/V1__create_orders.sql)

2. **Problema previo:** Archivo nuevo: Hibernate creaba/actualizaba orders sin historial de migración.

3. **Cambio realizado:** Crea solo columnas de Order, PK identity, NOT NULL y CHECK con los cinco estados del enum existente.

4. **Por qué:** Instant se almacena como timestamptz(6); Double conserva DOUBLE PRECISION para no alterar el contrato de dinero.

5. **Prueba/validación:** Flyway V1 aplicado; validate, PostgresSchemaIT y persistencia E2E aprobados.

6. **Código completo final:**

```sql
CREATE TABLE orders (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_email VARCHAR(255) NOT NULL,
    total_amount DOUBLE PRECISION NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'CREATED', 'CONFIRMED', 'CANCELLED', 'REJECTED'))
);
```

SHA-256 de los bytes del archivo: `b301920790845a93b917f7418f189b70daf2a91107fbfb3254aac46814c9a1f3`


### `orders/src/main/resources/db/migration/V2__create_order_items.sql`

1. **Ruta:** [orders/src/main/resources/db/migration/V2__create_order_items.sql](../../orders/src/main/resources/db/migration/V2__create_order_items.sql)

2. **Problema previo:** Archivo nuevo: order_items dependía de generación Hibernate.

3. **Cambio realizado:** Crea solo columnas de OrderItem, PK identity, NOT NULL y FK hacia orders.

4. **Por qué:** PostgreSQL rechaza ítems incompletos u huérfanos sin deshabilitar constraints.

5. **Prueba/validación:** Flyway V2; tests NOT NULL/PK/FK, relaciones y rollback aprobados.

6. **Código completo final:**

```sql
CREATE TABLE order_items (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sku VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price DOUBLE PRECISION NOT NULL,
    order_id BIGINT NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
```

SHA-256 de los bytes del archivo: `a698cf1f266e95abf69dd0e83f57a88f63b1f82d8804a85537894a6019c23a52`


### `orders/src/main/resources/db/migration/V3__add_indexes.sql`

1. **Ruta:** [orders/src/main/resources/db/migration/V3__add_indexes.sql](../../orders/src/main/resources/db/migration/V3__add_indexes.sql)

2. **Problema previo:** No había índices explícitos para consulta de propietario/fecha ni FK de ítems.

3. **Cambio realizado:** Índice orders(user_email, created_at DESC) e índice order_items(order_id).

4. **Por qué:** Cubren la consulta repository existente y las búsquedas/cargas de relación; no agregan datos ni unicidad inventada.

5. **Prueba/validación:** Flyway V3 y migrations_shouldIndexOwnerHistoryAndItemForeignKey aprobados.

6. **Código completo final:**

```sql
CREATE INDEX idx_orders_user_email_created_at ON orders (user_email, created_at DESC);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);
```

SHA-256 de los bytes del archivo: `d0d2f68e118ae58dc8cf0edf9225bb5fddef4006dd66328b5d9901ee48bff15e`


### `orders/src/test/java/com/camisetas360/orders/OrdersApplicationTests.java`

1. **Ruta:** [orders/src/test/java/com/camisetas360/orders/OrdersApplicationTests.java](../../orders/src/test/java/com/camisetas360/orders/OrdersApplicationTests.java)

2. **Problema previo:** Smoke con URL H2/create-drop y assertion de contexto solamente.

3. **Cambio realizado:** Usa PostgreSQL/Flyway/validate y comprueba V1-V3, probes reales y protección de API/Actuator.

4. **Por qué:** Valida la configuración observable de arranque y seguridad. Los mocks JWT/Rabbit siguen limitados al smoke, nunca al E2E.

5. **Prueba/validación:** Surefire: smoke exitoso dentro de las 84 pruebas aprobadas de orders.

6. **Código completo final:**

```java
package com.camisetas360.orders;

import com.camisetas360.orders.support.PostgresTestSupport;

import com.camisetas360.orders.controller.OrderController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
class OrdersApplicationTests extends PostgresTestSupport {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    // IT-CFG-001
    @Test
    void contextLoads() throws Exception {
        assertThat(context.getBean(OrderController.class)).isNotNull();
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3");
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }
}
```

SHA-256 de los bytes del archivo: `ac6fbf86ee87bf6cde74c521538f5095f7ba2b572ceb82da99d837f6c4e38f8c`


### `orders/src/test/java/com/camisetas360/orders/integration/OrderServicePersistenceIT.java`

1. **Ruta:** [orders/src/test/java/com/camisetas360/orders/integration/OrderServicePersistenceIT.java](../../orders/src/test/java/com/camisetas360/orders/integration/OrderServicePersistenceIT.java)

2. **Problema previo:** Slice JPA basado en base embebida y create-drop.

3. **Cambio realizado:** PostgreSQLContainer con Replace.NONE y validate; conserva las dos pruebas de DTO fuera de la transacción de setup.

4. **Por qué:** Detecta problemas reales de límites de transacción y carga lazy con open-in-view=false; el servicio bajo prueba es real.

5. **Prueba/validación:** Failsafe: 2 pruebas, 0 fallos/errores. Publisher es un colaborador mock solo en esta prueba focalizada; E2E usa AMQP real.

6. **Código completo final:**

```java
package com.camisetas360.orders.integration;

import com.camisetas360.orders.dto.OrderItemResponseDTO;
import com.camisetas360.orders.dto.OrderResponseDTO;
import com.camisetas360.orders.messaging.OrderEventPublisher;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import com.camisetas360.orders.service.OrderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import com.camisetas360.orders.support.PostgresTestSupport;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@Import(OrderService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderServicePersistenceIT extends PostgresTestSupport {

    private static final String EMAIL = "buyer@example.test";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private OrderRepository repository;

    @Autowired
    private OrderService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private OrderEventPublisher publisher;

    @BeforeEach
    void cleanDatabaseBeforeTest() {
        cleanDatabase();
    }

    @AfterEach
    void cleanDatabaseAndVerifyNoPublication() {
        try {
            verifyNoInteractions(publisher);
        } finally {
            cleanDatabase();
        }
    }

    // IT-ORD-001: assert the functional contract, not LazyInitializationException.
    @Test
    void findById_shouldReturnCompleteDto_whenSetupTransactionHasClosed() {
        var id = persistOrder(EMAIL, CREATED_AT);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        assertOrder(service.findById(id, EMAIL), id, CREATED_AT);
    }

    // IT-ORD-002
    @Test
    void findByUserEmail_shouldReturnCompleteDtos_whenSetupTransactionsHaveClosed() {
        var olderId = persistOrder(EMAIL, CREATED_AT);
        var later = Instant.parse("2026-01-02T12:00:00Z");
        var newerId = persistOrder(EMAIL, later);
        persistOrder("other@example.test", Instant.parse("2026-01-03T12:00:00Z"));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        var result = service.findByUserEmail(EMAIL);

        assertThat(result).hasSize(2);
        assertOrder(result.get(0), newerId, later);
        assertOrder(result.get(1), olderId, CREATED_AT);
    }

    private Long persistOrder(String email, Instant createdAt) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var order = new Order();
            order.setUserEmail(email);
            order.setTotalAmount(69.0);
            order.setStatus(OrderStatus.CREATED);
            order.setCreatedAt(createdAt);
            order.setItems(List.of(item("SKU-A", 2, 19.5), item("SKU-B", 3, 10.0)));
            return repository.saveAndFlush(order).getId();
        });
    }

    private void cleanDatabase() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from OrderItem").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
        });
    }

    private static void assertOrder(OrderResponseDTO response, Long id, Instant createdAt) {
        assertThat(response.orderId()).isEqualTo(id);
        assertThat(response.userEmail()).isEqualTo(EMAIL);
        assertThat(response.totalAmount()).isEqualTo(69.0);
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.items()).containsExactlyInAnyOrder(
                new OrderItemResponseDTO("SKU-A", 2, 19.5),
                new OrderItemResponseDTO("SKU-B", 3, 10.0));
    }

    private static OrderItem item(String sku, int quantity, double price) {
        var item = new OrderItem();
        item.setSku(sku);
        item.setQuantity(quantity);
        item.setUnitPrice(price);
        return item;
    }
}
```

SHA-256 de los bytes del archivo: `949729ea1b37b6f505b8b62d71f904859d2eca6ccaf0d5e2a00e61293794cf3e`


### `orders/src/test/java/com/camisetas360/orders/integration/PostgresSchemaIT.java`

1. **Ruta:** [orders/src/test/java/com/camisetas360/orders/integration/PostgresSchemaIT.java](../../orders/src/test/java/com/camisetas360/orders/integration/PostgresSchemaIT.java)

2. **Problema previo:** Archivo nuevo necesario: no existía cobertura del contrato SQL PostgreSQL/Flyway.

3. **Cambio realizado:** Agrega 15 casos: motor/version, historial Flyway/checksums, tipos, precisión timestamp, todos los NOT NULL, PK, FK, enum, dato no numérico, rollback e índices.

4. **Por qué:** Ejecuta SQL independiente y JPA real. Cada prueba detecta una mutación concreta de esquema o de comportamiento transaccional.

5. **Prueba/validación:** Failsafe: 15 pruebas, 0 fallos, 0 errores, 0 omitidas.

6. **Código completo final:**

```java
package com.camisetas360.orders.integration;

import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import com.camisetas360.orders.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresSchemaIT extends PostgresTestSupport {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void flyway_shouldOwnTheValidatedPostgresSchema() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE checksum IS NOT NULL AND success", Integer.class))
                .isEqualTo(3);
        assertThat(columnType("orders", "total_amount")).isEqualTo("double precision");
        assertThat(columnType("order_items", "unit_price")).isEqualTo("double precision");
        assertThat(columnType("orders", "created_at")).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='orders' AND column_name='created_at'", Integer.class))
                .isEqualTo(6);
    }

    static Stream<String> missingRequiredColumns() {
        var orderColumns = List.of("user_email", "total_amount", "status", "created_at");
        var itemColumns = List.of("sku", "quantity", "unit_price", "order_id");
        return Stream.concat(
                orderColumns.stream().map(column -> "INSERT INTO orders (id,user_email,total_amount,status,created_at) "
                        + "VALUES (100," + (column.equals("user_email") ? "NULL" : "'buyer@example.test'")
                        + "," + (column.equals("total_amount") ? "NULL" : "69.0")
                        + "," + (column.equals("status") ? "NULL" : "'CREATED'")
                        + "," + (column.equals("created_at") ? "NULL" : "CURRENT_TIMESTAMP") + ")"),
                itemColumns.stream().map(column -> "INSERT INTO order_items (sku,quantity,unit_price,order_id) VALUES ("
                        + (column.equals("sku") ? "NULL" : "'SKU-A'")
                        + "," + (column.equals("quantity") ? "NULL" : "2")
                        + "," + (column.equals("unit_price") ? "NULL" : "19.5")
                        + "," + (column.equals("order_id") ? "NULL" : "100") + ")"));
    }

    @ParameterizedTest
    @MethodSource("missingRequiredColumns")
    void database_shouldRejectNullRequiredFields(String sql) throws Exception {
        insertOrder(100);
        // Delete the parent for order INSERT cases so a duplicate PK cannot mask NOT NULL.
        if (sql.startsWith("INSERT INTO orders ")) jdbc.update("DELETE FROM orders WHERE id=100");
        assertSqlState(sql, "23502");
    }

    @Test
    void database_shouldRejectDuplicatePrimaryKeysAndOrphanItems() throws Exception {
        insertOrder(100);
        assertSqlState("INSERT INTO orders (id,user_email,total_amount,status,created_at) "
                + "VALUES (100,'buyer@example.test',69,'CREATED',CURRENT_TIMESTAMP)", "23505");
        jdbc.update("INSERT INTO order_items (id,sku,quantity,unit_price,order_id) VALUES (200,'SKU-A',2,19.5,100)");
        assertSqlState("INSERT INTO order_items (id,sku,quantity,unit_price,order_id) "
                + "VALUES (200,'SKU-B',1,10,100)", "23505");
        assertSqlState("INSERT INTO order_items (sku,quantity,unit_price,order_id) VALUES ('ORPHAN',1,10,999999)", "23503");
        assertSqlState("DELETE FROM orders WHERE id=100", "23503");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void database_shouldRejectInvalidStatusAndNonNumericPrice() throws Exception {
        assertSqlState("INSERT INTO orders (user_email,total_amount,status,created_at) "
                + "VALUES ('buyer@example.test',69,'UNKNOWN',CURRENT_TIMESTAMP)", "23514");
        assertSqlState("INSERT INTO orders (user_email,total_amount,status,created_at) "
                + "VALUES ('buyer@example.test','not-money','CREATED',CURRENT_TIMESTAMP)", "22P02");
        assertThat(repository.count()).isZero();
    }

    @Test
    void timestamp_shouldPreserveTheInstantFromAnotherTimezone() {
        var instant = OffsetDateTime.parse("2026-04-05T01:30:15.123456-03:00").toInstant();
        var order = aggregate(instant);
        Long id = new TransactionTemplate(transactionManager).execute(status -> repository.saveAndFlush(order).getId());
        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-04-05T04:30:15.123456Z"));
        assertThat(jdbc.queryForObject("SELECT created_at FROM orders WHERE id=?", OffsetDateTime.class, id)
                .toInstant()).isEqualTo(instant);
    }

    @Test
    void transaction_shouldRollbackTheWholeAggregateAfterFlushWhenWorkFails() {
        var order = aggregate(Instant.parse("2026-01-01T12:00:00Z"));
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.saveAndFlush(order);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isEqualTo(1);
            throw new IllegalStateException("Simulated failure after the database writes");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated failure after the database writes");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    @Test
    void failedItemInsert_shouldRollbackAnAlreadyInsertedParent() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertOrder(100);
            jdbc.update("INSERT INTO order_items (sku,quantity,unit_price,order_id) VALUES ('SKU-A',NULL,19.5,100)");
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findById(100L)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    @Test
    void migrations_shouldIndexOwnerHistoryAndItemForeignKey() {
        var indexes = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname='public' "
                + "AND indexname IN ('idx_orders_user_email_created_at','idx_order_items_order_id')", String.class);
        assertThat(indexes).hasSize(2);
        assertThat(indexes).anySatisfy(index -> assertThat(index).contains("(user_email, created_at DESC)"));
        assertThat(indexes).anySatisfy(index -> assertThat(index).contains("(order_id)"));
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=? AND column_name=?", String.class, table, column);
    }

    private void assertSqlState(String sql, String expected) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(expected));
        }
    }

    private void insertOrder(long id) {
        jdbc.update("INSERT INTO orders (id,user_email,total_amount,status,created_at) VALUES (?,?,69,'CREATED',CURRENT_TIMESTAMP)",
                id, "buyer@example.test");
    }

    private static Order aggregate(Instant createdAt) {
        var order = new Order();
        order.setUserEmail("buyer@example.test");
        order.setTotalAmount(39.0);
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(createdAt);
        var item = new OrderItem();
        item.setSku("SKU-A");
        item.setQuantity(2);
        item.setUnitPrice(19.5);
        order.addItem(item);
        return order;
    }
}
```

SHA-256 de los bytes del archivo: `a23595cfb58ddcc0a18fe6273a68e9ea975341bd6b3ad3fbca1692532d4851e0`


### `orders/src/test/java/com/camisetas360/orders/repository/OrderRepositoryTest.java`

1. **Ruta:** [orders/src/test/java/com/camisetas360/orders/repository/OrderRepositoryTest.java](../../orders/src/test/java/com/camisetas360/orders/repository/OrderRepositoryTest.java)

2. **Problema previo:** @DataJpaTest elegía la H2 del classpath y recreaba el esquema.

3. **Cambio realizado:** Usa soporte PostgreSQL, Replace.NONE y validate, conservando fixtures y assertions funcionales.

4. **Por qué:** Cascada, orphan removal, borrado, filtro por propietario y orden temporal se prueban en PostgreSQL/Flyway.

5. **Prueba/validación:** Las seis pruebas existentes del repositorio pasaron en Surefire.

6. **Código completo final:**

```java
package com.camisetas360.orders.repository;

import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import com.camisetas360.orders.support.PostgresTestSupport;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest extends PostgresTestSupport {

    private static final String EMAIL = "buyer@example.test";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private OrderRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    // JPA-ORD-001
    @Test
    void save_shouldPersistOrderAndItems_whenAggregateIsReloaded() {
        var order = order(EMAIL, CREATED_AT);
        var id = repository.saveAndFlush(order).getId();
        var itemIds = order.getItems().stream().map(OrderItem::getId).toList();
        entityManager.clear();

        assertThat(id).isNotNull();
        assertThat(itemIds).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded).isNotSameAs(order);
        assertThat(reloaded.getId()).isEqualTo(id);
        assertThat(reloaded.getUserEmail()).isEqualTo(EMAIL);
        assertThat(reloaded.getTotalAmount()).isEqualTo(69.0);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getItems()).extracting(OrderItem::getId)
                .containsExactlyInAnyOrderElementsOf(itemIds);
        assertThat(reloaded.getItems())
                .extracting(OrderItem::getSku, OrderItem::getQuantity, OrderItem::getUnitPrice)
                .containsExactlyInAnyOrder(tuple("SKU-A", 2, 19.5), tuple("SKU-B", 3, 10.0));
        assertThat(reloaded.getItems()).allSatisfy(item ->
                assertThat(item.getOrder().getId()).isEqualTo(id));
        assertThat(entityManager.createNativeQuery("select status from orders where id = :id", String.class)
                .setParameter("id", id).getSingleResult()).isEqualTo("CREATED");
    }

    // JPA-ORD-002
    @Test
    void findByUserEmailOrderByCreatedAtDesc_shouldFilterAndSort_whenUsersHaveOrders() {
        var oldest = repository.save(order(EMAIL, CREATED_AT));
        var newest = repository.save(order(EMAIL, Instant.parse("2026-01-03T12:00:00Z")));
        repository.save(order("other@example.test", Instant.parse("2026-01-04T12:00:00Z")));
        var middle = repository.save(order(EMAIL, Instant.parse("2026-01-02T12:00:00Z")));
        repository.flush();
        entityManager.clear();

        var result = repository.findByUserEmailOrderByCreatedAtDesc(EMAIL);

        assertThat(result).extracting(Order::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(result).extracting(Order::getUserEmail).containsOnly(EMAIL);
    }

    // JPA-ORD-003
    @Test
    void findByUserEmailOrderByCreatedAtDesc_shouldReturnEmpty_whenOnlyAnotherUserHasOrders() {
        repository.saveAndFlush(order("other@example.test", CREATED_AT));
        entityManager.clear();

        assertThat(repository.findByUserEmailOrderByCreatedAtDesc(EMAIL)).isEmpty();
    }

    // JPA-ORD-003
    @Test
    void findById_shouldReturnEmpty_whenOrderDoesNotExist() {
        assertThat(repository.findById(-1L)).isEmpty();
    }

    // JPA-ORD-004
    @Test
    void setItems_shouldDeleteOrphans_whenItemsAreReplaced() {
        var order = repository.saveAndFlush(order(EMAIL, CREATED_AT));
        var id = order.getId();
        var oldItemIds = order.getItems().stream().map(OrderItem::getId).toList();
        entityManager.clear();
        var managed = repository.findById(id).orElseThrow();
        managed.setItems(List.of(item("SKU-NEW", 1, 25.0)));
        repository.saveAndFlush(managed);
        entityManager.clear();

        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isNotNull().isNotIn(oldItemIds);
            assertThat(item.getSku()).isEqualTo("SKU-NEW");
            assertThat(item.getQuantity()).isEqualTo(1);
            assertThat(item.getUnitPrice()).isEqualTo(25.0);
            assertThat(item.getOrder().getId()).isEqualTo(id);
        });
        oldItemIds.forEach(itemId -> assertThat(entityManager.find(OrderItem.class, itemId)).isNull());
    }

    // JPA-ORD-005
    @Test
    void delete_shouldRemoveAssociatedItems_whenOrderIsDeleted() {
        var order = repository.saveAndFlush(order(EMAIL, CREATED_AT));
        var id = order.getId();
        var itemIds = order.getItems().stream().map(OrderItem::getId).toList();
        entityManager.clear();
        repository.delete(repository.findById(id).orElseThrow());
        repository.flush();
        entityManager.clear();

        assertThat(repository.findById(id)).isEmpty();
        itemIds.forEach(itemId -> assertThat(entityManager.find(OrderItem.class, itemId)).isNull());
    }

    private static Order order(String email, Instant createdAt) {
        var order = new Order();
        order.setUserEmail(email);
        order.setTotalAmount(69.0);
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(createdAt);
        order.setItems(List.of(item("SKU-A", 2, 19.5), item("SKU-B", 3, 10.0)));
        return order;
    }

    private static OrderItem item(String sku, int quantity, double price) {
        var item = new OrderItem();
        item.setSku(sku);
        item.setQuantity(quantity);
        item.setUnitPrice(price);
        return item;
    }
}
```

SHA-256 de los bytes del archivo: `4f93fd1c017268d2e512b9c4520f2a2c9e9a78d5e509b4ebea831a0aee4e5f81`


### `orders/src/test/java/com/camisetas360/orders/support/PostgresTestSupport.java`

1. **Ruta:** [orders/src/test/java/com/camisetas360/orders/support/PostgresTestSupport.java](../../orders/src/test/java/com/camisetas360/orders/support/PostgresTestSupport.java)

2. **Problema previo:** Archivo nuevo necesario: las pruebas antes dependían de una base embebida explícita o elegida por Spring.

3. **Cambio realizado:** Inicia PostgreSQLContainer postgres:17 y registra URL/usuario/password dinámicos. Cierra el contenedor y descarta el contexto al terminar cada clase.

4. **Por qué:** Cada clase recibe una base temporal sin reuse ni dependencia de una DB CI permanente. No se omiten fallos de Docker.

5. **Prueba/validación:** Tests de repositorio y smoke de orders; orders incluye también persistencia del servicio y 15 casos de esquema.

6. **Código completo final:**

```java
package com.camisetas360.orders.support;

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
                    .withDatabaseName("orders_test");
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

SHA-256 de los bytes del archivo: `8b0b496957c6092db09c624ee81b48e6cd128ae78ac2db8abe3b30257afd31ca`


### `tests/e2e/pom.xml`

1. **Ruta:** [tests/e2e/pom.xml](../../tests/e2e/pom.xml)

2. **Problema previo:** La prueba externa no tenía módulo Testcontainers PostgreSQL ni JDBC para comprobar filas directamente.

3. **Cambio realizado:** Añade testcontainers-postgresql y el driver oficial con scope test, administrados por la BOM existente.

4. **Por qué:** El proceso orders incluye Flyway en su JAR; el harness no genera esquema alternativo.

5. **Prueba/validación:** -Pe2e clean verify: BUILD SUCCESS, 2 pruebas aprobadas.

6. **Código completo final:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>
    <groupId>com.camisetas360.testing</groupId>
    <artifactId>e2e-tests</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <properties><java.version>21</java.version></properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-oauth2-jose</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>tools.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <profiles>
        <profile>
            <id>e2e</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <executions>
                            <execution>
                                <goals><goal>integration-test</goal><goal>verify</goal></goals>
                            </execution>
                        </executions>
                        <configuration>
                            <includes><include>**/*E2EIT.java</include></includes>
                            <systemPropertyVariables>
                                <repo.root>${project.basedir}/../..</repo.root>
                                <e2e.logs>${project.build.directory}/e2e-logs</e2e.logs>
                            </systemPropertyVariables>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

SHA-256 de los bytes del archivo: `fa78349927911a41e83e60e8bc15848fb2b6a61c76db9b1a23b56da9b119e8f0`


### `tests/e2e/src/test/java/com/camisetas360/testing/e2e/CheckoutFlowE2EIT.java`

1. **Ruta:** [tests/e2e/src/test/java/com/camisetas360/testing/e2e/CheckoutFlowE2EIT.java](../../tests/e2e/src/test/java/com/camisetas360/testing/e2e/CheckoutFlowE2EIT.java)

2. **Problema previo:** El proceso orders recibía jdbc:h2:mem y create-drop; persistencia solo observable por HTTP.

3. **Cambio realizado:** PostgreSQLContainer por método; URL/usuario/password reales y validate. Verifica filas comprometidas/ítems/Flyway por JDBC y evento AMQP con cola durable independiente. Conserva SMTP, HTTP, JWT y ownership.

4. **Por qué:** El camino completo debe funcionar con infraestructura real. La cola de captura no consume ni sustituye notifications; RabbitMQ 4 no requiere habilitar funciones obsoletas.

5. **Prueba/validación:** 2 E2E aprobados; artefactos postgres-persistence.txt, order-created-event.json, delivered-email.json y foreign-order-response.json.

6. **Código completo final:**

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

SHA-256 de los bytes del archivo: `45240b33b95bd278895d891e56d8f2c23b7126e9eb8fbfdffa81c7e8f2a1d112`


### `tests/messaging/pom.xml`

1. **Ruta:** [tests/messaging/pom.xml](../../tests/messaging/pom.xml)

2. **Problema previo:** Dependencia H2 para el agregado en la integración RabbitMQ.

3. **Cambio realizado:** Reemplaza H2 por PostgreSQL JDBC/Testcontainers y Flyway; incorpora las migraciones reales de orders como test resources.

4. **Por qué:** Evita un esquema de test divergente del usado por el servicio y producción.

5. **Prueba/validación:** -Prabbit clean verify: 4 contratos y 4 integraciones aprobados; árbol sin H2.

6. **Código completo final:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>
    <groupId>com.camisetas360.testing</groupId>
    <artifactId>messaging-tests</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>Camisetas360 messaging tests</name>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <testResources>
            <testResource>
                <directory>${project.basedir}/../../orders/src/main/resources</directory>
                <includes><include>db/migration/**</include></includes>
            </testResource>
        </testResources>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>real-service-test-sources</id>
                        <phase>generate-test-sources</phase>
                        <goals><goal>add-test-source</goal></goals>
                        <configuration>
                            <sources>
                                <source>${project.basedir}/../../carrito/src/main/java</source>
                                <source>${project.basedir}/../../orders/src/main/java</source>
                                <source>${project.basedir}/../../notifications/src/main/java</source>
                            </sources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
    <profiles>
        <profile>
            <id>rabbit</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <executions>
                            <execution>
                                <goals><goal>integration-test</goal><goal>verify</goal></goals>
                            </execution>
                        </executions>
                        <configuration>
                            <includes><include>**/*RabbitIT.java</include></includes>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

SHA-256 de los bytes del archivo: `cb8ff529f8a6e4777434fdc7a3d441bcb366e063c054cb49115ca35b17c88def`


### `tests/messaging/src/test/java/com/camisetas360/testing/messaging/MessagingRabbitIT.java`

1. **Ruta:** [tests/messaging/src/test/java/com/camisetas360/testing/messaging/MessagingRabbitIT.java](../../tests/messaging/src/test/java/com/camisetas360/testing/messaging/MessagingRabbitIT.java)

2. **Problema previo:** EmbeddedDatabaseBuilder H2 y create-drop en el contexto OrderProcessing.

3. **Cambio realizado:** PostgreSQLContainer con DataSource real; Flyway migra antes de entityManagerFactory; Hibernate valida.

4. **Por qué:** Conserva las pruebas focalizadas de listeners y el agregado real con publicación AMQP. Ninguna assertion se cambia para acomodar resultados.

5. **Prueba/validación:** 4 integraciones RabbitMQ aprobadas después de declarar nombres JPA iguales a las columnas reales.

6. **Código completo final:**

```java
package com.camisetas360.testing.messaging;

import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.notifications.listener.OrderCreatedListener;
import com.camisetas360.notifications.service.EmailService;
import com.camisetas360.orders.messaging.OrderEventPublisher;
import com.camisetas360.orders.messaging.listener.CheckoutRequestedListener;
import com.camisetas360.orders.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import com.camisetas360.orders.service.OrderService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ResourceLock(Resources.LOCALE)
class MessagingRabbitIT {

    private static final String EXCHANGE = "camisetas360.orders";
    private static final String CHECKOUT_QUEUE = "orders.checkout-requested.q";
    private static final String NOTIFICATION_QUEUE = "notifications.order-created.q";
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            System.getProperty("rabbitmq.image", "rabbitmq:4-management"));
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private CachingConnectionFactory connection;
    private RabbitAdmin admin;

    @BeforeAll
    static void startBroker() {
        // No disabledWithoutDocker: infrastructure failures must fail this explicit profile.
        RABBIT.start();
        POSTGRES.start();
    }

    @AfterAll
    static void stopBroker() {
        POSTGRES.stop();
        RABBIT.stop();
    }

    @BeforeEach
    void connectAndDeclareTopology() {
        connection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connection.setUsername(RABBIT.getAdminUsername());
        connection.setPassword(RABBIT.getAdminPassword());
        admin = new RabbitAdmin(connection);
        var cart = new com.camisetas360.carrito.config.RabbitMQConfig();
        var orders = new com.camisetas360.orders.config.RabbitMQConfig();
        var notifications = new com.camisetas360.notifications.config.RabbitMQConfig();
        admin.declareExchange(cart.ordersExchange());
        admin.declareExchange(orders.ordersExchange());
        admin.declareExchange(notifications.ordersExchange());
        admin.declareQueue(orders.checkoutRequestedQueue());
        admin.declareQueue(notifications.notificationsOrderCreatedQueue());
        admin.declareBinding(orders.checkoutRequestedBinding(orders.checkoutRequestedQueue(), orders.ordersExchange()));
        admin.declareBinding(notifications.notificationsOrderCreatedBinding(
                notifications.notificationsOrderCreatedQueue(), notifications.ordersExchange()));
        admin.purgeQueue(CHECKOUT_QUEUE);
        admin.purgeQueue(NOTIFICATION_QUEUE);
    }

    @AfterEach
    void disconnect() {
        if (connection != null) {
            connection.destroy();
        }
    }

    // IT-AMQP-001
    @Test
    void topology_shouldMatchProductionDeclarations_onRealBroker() throws Exception {
        var exchange = managementMap("/api/exchanges/%2F/" + EXCHANGE);
        assertThat(exchange).containsEntry("type", "direct")
                .containsEntry("durable", true).containsEntry("auto_delete", false);
        for (String queue : List.of(CHECKOUT_QUEUE, NOTIFICATION_QUEUE)) {
            assertThat(managementMap("/api/queues/%2F/" + queue))
                    .containsEntry("durable", true).containsEntry("auto_delete", false);
        }
        assertBinding(CHECKOUT_QUEUE, "checkout.requested");
        assertBinding(NOTIFICATION_QUEUE, "order.created");
    }

    // IT-AMQP-002
    @Test
    void checkout_shouldReachOrdersListener_asConsumerRecord() {
        var service = mock(OrderService.class);
        when(service.createOrder(any())).thenReturn(new Order());
        try (var context = consumer(OrdersConsumer.class, service, null)) {
            awaitConsumer(context);
            var template = template(new com.camisetas360.carrito.config.RabbitMQConfig().jsonMessageConverter());
            new CheckoutEventPublisher(template).publishCheckoutRequested(EventFixtures.checkout());

            var captor = ArgumentCaptor.forClass(CheckoutRequestedEvent.class);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    verify(service).createOrder(captor.capture()));
            assertThat(captor.getValue()).isEqualTo(new CheckoutRequestedEvent(
                    EventFixtures.EVENT_ID, "buyer@example.test", List.of(
                    new com.camisetas360.orders.messaging.event.CheckoutItemEvent("CAM-Ñ-東京", 2, 19.5),
                    new com.camisetas360.orders.messaging.event.CheckoutItemEvent("SKU-B", 3, 10.0)),
                    EventFixtures.OCCURRED_AT));
        }
    }

    // IT-AMQP-003
    @Test
    void orderCreated_shouldReachNotificationsListener_andRequestExpectedEmail() {
        var email = mock(EmailService.class);
        var previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        try (var context = consumer(NotificationsConsumer.class, null, email)) {
            awaitConsumer(context);
            var template = template(new com.camisetas360.orders.config.RabbitMQConfig().jsonMessageConverter());
            new OrderEventPublisher(template).publishOrderCreated(EventFixtures.orderCreated());

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> verify(email).sendEmail(
                    "buyer@example.test", "Orden creada #42", """
                    Hola,

                    Tu orden fue creada correctamente.

                    Número de orden: 42
                    Total: $69.00
                    Estado: CREATED

                    Gracias por comprar en Camisetas360.
                    """));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    // IT-AMQP-004
    @Test
    void checkout_shouldPersistAggregateAndPublishMatchingOrderCreated_whenConsumed() {
        var capture = QueueBuilder.nonDurable("test.capture." + UUID.randomUUID()).exclusive().autoDelete().build();
        admin.declareQueue(capture);
        admin.declareBinding(BindingBuilder.bind(capture).to(new DirectExchange(EXCHANGE)).with("order.created"));
        try (var context = consumer(OrderProcessing.class, null, null)) {
            awaitConsumer(context);
            var publisherTemplate = template(new com.camisetas360.carrito.config.RabbitMQConfig().jsonMessageConverter());
            new CheckoutEventPublisher(publisherTemplate).publishCheckoutRequested(EventFixtures.checkout());

            // Blocking receive has a finite deadline and wakes when the message arrives.
            var message = publisherTemplate.receive(capture.getName(), 15000);
            assertThat(message).as("order.created captured within 15 seconds").isNotNull();
            message.getMessageProperties().setInferredArgumentType(
                    com.camisetas360.notifications.event.OrderCreatedEvent.class);
            var event = (com.camisetas360.notifications.event.OrderCreatedEvent)
                    new com.camisetas360.notifications.config.RabbitMQConfig().jsonMessageConverter().fromMessage(message);
            assertThat(event.eventId()).isNotNull();
            assertThat(event.occurredAt()).isNotNull();
            assertThat(event.orderId()).isNotNull();
            assertThat(event.userEmail()).isEqualTo("buyer@example.test");
            assertThat(event.totalAmount()).isEqualTo(69.0);
            assertThat(event.items()).containsExactlyInAnyOrder(
                    new com.camisetas360.notifications.event.OrderItemEvent("CAM-Ñ-東京", 2, 19.5),
                    new com.camisetas360.notifications.event.OrderItemEvent("SKU-B", 3, 10.0));

            var repository = context.getBean(OrderRepository.class);
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                assertThat(repository.count()).isEqualTo(1);
                var order = repository.findById(event.orderId()).orElseThrow();
                assertThat(order.getUserEmail()).isEqualTo(event.userEmail());
                assertThat(order.getTotalAmount()).isEqualTo(event.totalAmount());
                assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
                assertThat(order.getCreatedAt()).isNotNull();
                assertThat(order.getItems()).extracting(OrderItem::getSku, OrderItem::getQuantity, OrderItem::getUnitPrice)
                        .containsExactlyInAnyOrder(tuple("CAM-Ñ-東京", 2, 19.5), tuple("SKU-B", 3, 10.0));
                assertThat(order.getItems()).allSatisfy(item -> {
                    assertThat(item.getId()).isNotNull();
                    assertThat(item.getOrder().getId()).isEqualTo(event.orderId());
                });
            });
        }
    }

    private AnnotationConfigApplicationContext consumer(Class<?> config, OrderService service, EmailService email) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ConnectionFactory.class, () -> connection);
        if (service != null) {
            context.registerBean(OrderService.class, () -> service);
        }
        if (email != null) {
            context.registerBean(EmailService.class, () -> email);
        }
        context.register(ListenerInfrastructure.class, config);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    private static void awaitConsumer(AnnotationConfigApplicationContext context) {
        var registry = context.getBean(RabbitListenerEndpointRegistry.class);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(registry.getListenerContainers()).hasSize(1).allSatisfy(container ->
                        assertThat(((SimpleMessageListenerContainer) container).getActiveConsumerCount()).isEqualTo(1)));
    }

    private RabbitTemplate template(MessageConverter converter) {
        var template = new RabbitTemplate(connection);
        template.setMessageConverter(converter);
        return template;
    }

    private String management(String path) throws Exception {
        var credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8))).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> managementMap(String path) throws Exception {
        return JsonMapper.builder().build().readValue(management(path), Map.class);
    }

    @SuppressWarnings("unchecked")
    private void assertBinding(String queue, String routing) throws Exception {
        List<Map<String, Object>> bindings = JsonMapper.builder().build().readValue(
                management("/api/bindings/%2F/e/" + EXCHANGE + "/q/" + queue), List.class);
        assertThat(bindings).anySatisfy(binding ->
                assertThat(binding).containsEntry("source", EXCHANGE).containsEntry("destination", queue)
                        .containsEntry("routing_key", routing).containsEntry("destination_type", "queue"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    static class ListenerInfrastructure {
        @Bean
        SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
                ConnectionFactory connection, MessageConverter converter) {
            var factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connection);
            factory.setMessageConverter(converter);
            factory.setDefaultRequeueRejected(false);
            factory.setMissingQueuesFatal(true);
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({com.camisetas360.orders.config.RabbitMQConfig.class, CheckoutRequestedListener.class})
    static class OrdersConsumer {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({com.camisetas360.notifications.config.RabbitMQConfig.class, OrderCreatedListener.class})
    static class NotificationsConsumer {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @EnableTransactionManagement
    @Import({OrdersConsumer.class, OrderService.class, OrderEventPublisher.class})
    static class OrderProcessing {
        @Bean
        RabbitTemplate rabbitTemplate(ConnectionFactory connection, MessageConverter converter) {
            var template = new RabbitTemplate(connection);
            template.setMessageConverter(converter);
            return template;
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean(initMethod = "migrate")
        Flyway flyway(DataSource dataSource) {
            return Flyway.configure().dataSource(dataSource).load();
        }

        @Bean
        @DependsOn("flyway")
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Order.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
```

SHA-256 de los bytes del archivo: `007d74af8a729aeb64bb11563130a2d48dc406a4d1d2cac063e70ca8f6659a5b`
