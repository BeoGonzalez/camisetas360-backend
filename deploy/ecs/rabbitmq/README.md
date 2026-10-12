# RabbitMQ OSS privado en ECS sobre EC2

El exchange existente se conserva: `camisetas360.orders`, `direct`, durable.
`order.created` se entrega a dos queues independientes:
`notifications.order-created.q` y `recommendation.order-created.q`.
No se utiliza Amazon MQ, SQS, SNS ni EventBridge como sustituto del broker.

## Recursos y requisitos de infraestructura

`rabbitmq.cfn.json` prepara un servicio ECS **EC2**, una task `awsvpc`, Cloud Map,
logs CloudWatch y SG privado. Reutiliza el cluster, VPC, subnet privada, namespace
DNS privado, execution role y SG de clientes existentes, pasados por parámetros.
El repositorio no contiene sus IDs ni una definición exportada del entorno AWS:
hay que comprobarlos en la cuenta antes de crear el stack. No ejecutar este stack
en paralelo con otro broker sobre el mismo volumen.

AMQP 5672 admite únicamente los SG de orders, recommendation, notifications y
carrito (este último ya publica checkout). Management 15672 no tiene ingress,
IP pública, balanceador público ni reglas `0.0.0.0/0`. Los SG clientes deben
permitir egress TCP 5672 hacia el SG del broker. El namespace debe pertenecer a
esta VPC y tener DNS privado; habilitar DNS support/hostnames en la VPC.
La task tiene egress bloqueado; el agente ECS del host necesita acceso privado
mediante endpoints o NAT a ECS, ECR, Secrets Manager y CloudWatch Logs.

El execution role debe tener permisos sobre la imagen ECR, el log group y
`secretsmanager:GetSecretValue` para el secret indicado, más `kms:Decrypt` si
usa una CMK. Se requiere un agente ECS que soporte selección de claves JSON
de secrets. No añadir passwords a parámetros, archivos, Git o logs.

## Persistencia y recuperación

Antes del primer despliegue, preparar un EBS cifrado **independiente del disco
raíz** en el EC2 seleccionado. Montarlo en `/srv/rabbitmq/data`, conservarlo con
`DeleteOnTermination=false` y configurar su montaje por UUID en fstab. Crear
`.rabbitmq-volume` **dentro del volumen montado**. No formatear un volumen con
datos. ECS no crea ni monta este EBS: la preparación del host es un prerrequisito
operativo explícito. Confirmar `findmnt /srv/rabbitmq/data` antes de escalar.

El bind mount guarda Mnesia, mensajes y cookie Erlang. `rabbit@localhost` es la
identidad persistente del nodo y no el hostname de conexión de clientes. Mantener
esta identidad al recuperar datos. El marcador impide arrancar con un directorio
vacío cuando falta el volumen. La colocación se fija al **EC2 con ese EBS**.
El servicio ejecuta una sola task y reemplaza parando antes de arrancar
(`minimumHealthyPercent=0`, `maximumPercent=100`): nunca dos escritores en EBS.

Esta configuración es un broker de un nodo, con quorum queues de un miembro;
no ofrece HA ni tolera la pérdida definitiva del volumen. Hacer snapshots
consistentes con el broker detenido, probar restauración y definir RPO/RTO.
Para perder un host: detener/escalar el servicio a cero, comprobar que el antiguo
broker está apagado, adjuntar **el mismo EBS** a un EC2 ECS de la misma AZ,
montar y verificar datos/marcador, actualizar `BrokerInstanceId` y escalar a uno.
Una snapshot permite restauración en otra AZ con su RPO correspondiente.
No usar volúmenes EBS administrados por una task de servicio como sustitución
automática: su ciclo de vida no garantiza reutilizar los datos del broker.

Los health checks de ECS comprueban proceso/nodo con período inicial de 120 s;
las alarmas de disco/memoria se monitorizan y resuelven sin reiniciar el broker.

## Imagen y bootstrap

Construir desde este directorio con `RABBITMQ_BASE_IMAGE` apuntando a una imagen
oficial RabbitMQ OSS 4.x management **fijada por digest**, revisada y compatible
con at-least-once dead lettering de quorum queues. Publicar la imagen en ECR y
pasar su digest como `BrokerImage`; no usar tags flotantes en producción.

```sh
docker build --build-arg RABBITMQ_BASE_IMAGE="$RABBITMQ_BASE_IMAGE" -t "$BROKER_IMAGE_TAG" .
```

El secret JSON del broker contiene `username` y `password`. ECS lo inyecta como
`RABBITMQ_DEFAULT_USER/PASS` para inicializar un volumen nuevo. Cambiar ese secret
no rota usuarios ya existentes: cambiar el usuario en RabbitMQ, actualizar los
secrets de clientes y redeployar las tasks; verificar conexión antes de revocar
las credenciales antiguas. Preferir usuarios y permisos distintos por servicio.

Antes de permitir nuevas publicaciones, importar `topology.json` **después de
arrancar el nodo**, mediante `rabbitmqctl import_definitions` dentro del contenedor
o una sesión privada de operación. No configurar importación al primer boot:
RabbitMQ omitiría la creación del usuario inicial. Para otro vhost:

```sh
python render-topology.py --vhost "$RABBITMQ_VHOST" --output /tmp/topology.json
```

Copiar ese archivo sin credenciales al contenedor y ejecutar:

```sh
rabbitmqctl import_definitions /tmp/topology.json
rabbitmqctl list_bindings -p "$RABBITMQ_VHOST"
rabbitmqctl list_queues -p "$RABBITMQ_VHOST" name type durable arguments
```

La importación añade las nuevas queues sin cambiar el exchange ni las queues
existentes. Inspeccionar primero sus atributos reales; un conflicto de declaración
debe resolverse con una migración deliberada, sin borrar una queue con mensajes.
Provisionar la queue de recommendation **antes de activar el relay de orders**:
`mandatory` detecta ausencia total de rutas, no la ausencia de uno de los bindings.
En el primer despliegue mantener `APP_OUTBOX_ENABLED=false` hasta verificar ambas
rutas; después activar `true`. En despliegues posteriores la queue durable permanece.

## Configuración de clientes

En las task definitions de recommendation y orders:

| Variable | Origen |
| --- | --- |
| `RABBITMQ_HOST` | Output `RabbitMqHost`: `rabbitmq.<namespace-privado>` |
| `RABBITMQ_PORT` | `5672` |
| `RABBITMQ_USERNAME` | `secrets`, clave del secret del servicio |
| `RABBITMQ_PASSWORD` | `secrets`, clave del secret del servicio |
| `RABBITMQ_VHOST` | Output `RabbitMqVhost` |
| `SPRING_PROFILES_ACTIVE` | `production` |

No poner IP de task ni hostname efímero. Usar las mismas variables para notifications
y carrito. El perfil production de orders/notifications/carrito exige valores sin fallback
de credenciales de desarrollo. Recommendation los exige en todos los entornos.
Recommendation necesita su propia base PostgreSQL mediante
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`; no usa tablas de orders o notifications.
El starter Mistral existente necesita su API key en un secret si se activan sus
modelos. Para ejecutar únicamente la recepción inbox, configurar
`SPRING_PROFILES_ACTIVE=production,messaging`: este perfil desactiva las cuatro
autoconfiguraciones de modelos Mistral mientras no exista el worker de IA.

La factoría de conexiones Spring se reconecta y RabbitAdmin redeclara topology al
recuperar conexión. Recommendation usa `missing-queues-fatal=false`, heartbeat 30 s
y connection timeout 5 s. ECS/ALB debe comprobar `/actuator/health/liveness` (o
readiness según su propósito), **no** el health agregado que incluye RabbitMQ.
Liveness no depende del broker; readiness de recommendation depende de su DB.
Una caída breve del broker deja el proceso activo y esperando reconexión.

## Entrega, reintentos e idempotencia

`OrderService.createOrder` confirma en una transacción PostgreSQL la orden y su
outbox. El relay usa `FOR UPDATE SKIP LOCKED`, mensajes persistentes, `messageId`
igual a `eventId`, publisher confirms correlacionados y returns obligatorios.
Solo marca enviado después del confirm. Ante nack, return, timeout o desconexión
conserva el evento y aplica backoff 5–300 s. Después de 10 intentos queda aparcado
en PostgreSQL para intervención; una caída prolongada no pierde ni descarta eventos.

Recommendation recibe JSON sin resolver clases arbitrarias de headers del productor.
Guarda el payload en `recommendation_order_inbox` con PK `event_id`, de forma
atómica y segura frente a consumidores concurrentes. AUTO aquí significa ACK
**después** de terminar el listener y confirmar su transacción; no AMQP auto-ack.
Si cae tras commit y antes del ACK, la redelivery se deduplica en PostgreSQL.
El inbox queda pendiente (`processed_at IS NULL`) para el futuro procesamiento
de recomendaciones; este cambio no implementa un modelo ni resultados de IA.

Errores transitorios de procesamiento tienen 3 intentos con backoff local; agotados,
se rechazan sin requeue hacia `recommendation.order-created.dlq`. Los payloads
inválidos también se rechazan. Quorum limita redelivery a 5 y usa dead lettering
at-least-once con `reject-publish`; la DLQ no tiene TTL ni retorno automático.
Reprocesar solo tras corregir la causa, preservando `eventId` y esperando confirms
antes de ACK del mensaje DLQ. No generar IDs nuevos para el replay.

No hay exactly-once: un confirm perdido o un fallo entre confirm y commit del
outbox puede duplicar publicaciones. Notifications sigue recibiendo por su propia
queue y conserva su implementación existente; debe revisar su idempotencia y su
política de retries por separado. La deduplicación de checkout en orders tampoco
se añade en este cambio: una solicitud checkout redelivered puede crear otra orden.

## Observabilidad y aceptación

CloudWatch guarda stdout del broker durante 30 días. Configurar alertas de disco,
memoria, ausencia de task/consumer, conexiones, messages_ready/unacknowledged,
edad del backlog y DLQ > 0. Usar management/Prometheus solo desde red de operación
privada con SG dedicado; el stack no abre management. Para administración puntual,
usar sesión SSM/operación privada y túnel local, nunca publicar 15672 en Internet.
Proteger los payloads del inbox y backups porque contienen datos de clientes.

Orders registra `eventId`, confirms, intentos y `parked=true`; recommendation
expone contadores Micrometer `recommendation.events` por resultado sin labels de
alta cardinalidad ni emails en logs. Integrar su exportador con la monitorización
existente; `/actuator/metrics` no se expone públicamente por esta configuración.
Definir retención/limpieza de filas outbox ya publicadas y de payloads inbox ya
procesados. Conservar las claves de deduplicación durante todo el horizonte de
redelivery/replay; no eliminar filas pendientes ni IDs que aún puedan reaparecer.

Consultar outbox pendiente/aparcado y antigüedad, alertar y reactivar tras reparar:

```sql
SELECT count(*), min(created_at) FROM order_event_outbox WHERE published_at IS NULL;
SELECT event_id, attempts FROM order_event_outbox WHERE published_at IS NULL AND attempts >= 10;
-- Reintento manual del evento investigado, conservando event_id/payload:
UPDATE order_event_outbox SET attempts = 0, next_attempt_at = CURRENT_TIMESTAMP
WHERE event_id = :event_id AND published_at IS NULL;
```

Verificar en staging: ambos consumidores reciben el mismo evento; retry/invalid
llegan a DLQ; duplicados no multiplican inbox; broker inaccesible no reinicia
recommendation; reinicio de task conserva mensajes/usuarios/bindings; cambio de
IP mantiene DNS y reconnect; fallo del host recupera EBS; otros SG no acceden a
5672 y 15672 no es público. Ejecutar las suites Maven y Testcontainers con Docker.

```powershell
.\orders\mvnw.cmd -B -ntp -f recommendation/pom.xml verify
.\orders\mvnw.cmd -B -ntp -f orders/pom.xml verify
.\orders\mvnw.cmd -B -ntp -f tests/messaging/pom.xml -Prabbit verify
```

`RabbitDeliveryIT` verifica fanout independiente, duplicate/inbox y DLQ con
RabbitMQ/PostgreSQL reales. `OrderOutboxPersistenceIT` verifica commit sin broker
y rollback conjunto. Los checks de restart y recuperación EBS requieren staging
AWS y no se sustituyen por estas pruebas locales.

Referencias: [fiabilidad RabbitMQ](https://www.rabbitmq.com/docs/reliability),
[quorum y DLQ](https://www.rabbitmq.com/docs/quorum-queues),
[importación de definitions](https://www.rabbitmq.com/docs/definitions),
[Cloud Map en ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-discovery.html),
[ciclo de vida EBS en ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ebs-volumes.html).
