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
