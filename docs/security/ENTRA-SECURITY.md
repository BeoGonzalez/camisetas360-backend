# Seguridad Entra ID y contrato operativo

Java 21, Spring Boot 4.1.1 y OAuth2 Resource Server se mantienen. Esta guía describe el código y las pruebas locales; no confirma la configuración del portal Entra ni el estado de AWS.

## Autorización vigente

Se exige **scope y rol**, nunca OR. Los valores son sensibles a mayúsculas. ADMIN no obtiene permisos CUSTOMER automáticamente.

| Servicio | Método / endpoint | Scope | Roles permitidos |
|---|---|---|---|
| auth | GET /api/v1/auth/profile | Profile.Read | CUSTOMER, CATALOG_MANAGER, ADMIN |
| catalog | GET /api/v1/catalog/products | Catalog.Read | CUSTOMER, CATALOG_MANAGER, ADMIN |
| carrito | POST /api/v1/carrito/checkout | Checkout.Create | CUSTOMER |
| orders | GET /api/v1/orders | Orders.Read | CUSTOMER + ownership |
| orders | GET /api/v1/orders/{orderId} | Orders.Read | CUSTOMER + ownership |
| notifications | POST /api/v1/notifications/email | Notifications.Send | ADMIN |

Las reglas GET de catalog y orders cubren sus respectivos prefijos. Los demás métodos y rutas se deniegan salvo OPTIONS y los tres paths de health. Una ruta inexistente que coincide con un prefijo autorizado sigue sin crear una API nueva.

`JwtAuthoritiesConverter` conserva el delegado Spring para scopes: `scope` tiene precedencia sobre `scp`, incluso si scope está vacío. Ambos aceptan String o colección de strings. Los scopes producen `SCOPE_…`; roles String o lista producen `ROLE_…`. Se eliminan duplicados y authorities vacías; tipos inesperados se rechazan. No se convierte Customer en CUSTOMER ni se crean roles/scopes.

Los cinco Resource Servers validan firma RS256 mediante JWKS, issuer exacto, audience de la API y timestamps (exp obligatorio; nbf se valida si existe, con el margen de reloj de Spring). Carrito y orders exigen además tid/oid UUID válidos antes de ejecutar negocio. Los tests usan JWT firmados y JWKS local, además de MockMvc para reglas HTTP.

Discovery/JWKS se inicializa de forma lazy: una caída de Entra no bloquea el arranque ni health. Un request autenticado sin claves/discovery disponibles falla cerrado; no se omite ninguna validación. Issuer/audience ausentes sí impiden el arranque por ser una configuración insegura.

## Decisión de identidad y compatibilidad

**Ownership = par UUID tenantId/objectId, obtenido exclusivamente de tid/oid de un JWT validado.** Un objectId solo no es suficiente: debe pertenecer al mismo tenant. No hay fallback a email, preferred_username ni sub para reclamar órdenes.

Se eligió tid+oid porque Entra emite oid estable por objeto dentro del tenant, mientras sub es relativo a la aplicación. Esto permite propagar la misma identidad entre carrito y orders aunque cambie el nombre de usuario. Para invitados se usa el objeto del tenant emisor del access token. La combinación issuer+sub de auth se conserva como clave de perfil; sus datos oid/tid permiten correlación explícita sin cambiar esa persistencia. No se unen perfiles u órdenes por email.

`preferred_username` y `email` son contacto/presentación; pueden cambiar y no se consideran email verificado para autorización. Checkout usa email y después preferred_username como contacto. Si no existe contacto válido devuelve 422 y no publica ni persiste el checkout. La lectura de órdenes funciona sin preferred_username.

Flyway agrega tenant_id/object_id nullable con CHECK de ambos presentes o ambos ausentes e índice compuesto: carrito V2, orders V5 (V4 del outbox existente se conserva). Hibernate permanece ddl-auto=validate. Filas históricas conservan datos y owner NULL; la API no las devuelve a ningún usuario por coincidencia de email. Esto implica indisponibilidad de esas órdenes hasta completar un backfill verificado.

**Rollout obligatorio:**

1. Respaldar DB, colas y definición RabbitMQ; medir órdenes/checkouts sin owner.
2. Instalar y comprobar la política `orders-checkout-dlq` y su DLX/DLQ antes de actualizar orders. Importar las definitions renderizadas para el vhost real, sin borrar ni redeclarar la queue fuente con nuevos argumentos. Revisar políticas de mayor prioridad que puedan impedir DLQ.
3. Pausar publicaciones para drenar o archivar checkouts históricos. Enriquecer únicamente con un mapping verificado de la identidad original; conservar la evidencia. Nunca reconstruir ownership por email actual.
4. Aplicar migraciones y actualizar carrito/orders coordinadamente. El consumidor antiguo ignoraría los nuevos campos y no persistiría ownership: no mantener versiones mezcladas durante nuevos checkouts.
5. Backfill mediante operación separada, revisada y auditable, usando evidencia histórica autoritativa. No existe un backfill automático seguro a partir de los datos actuales del repositorio.
6. Verificar ownership, DLQ y métricas antes de reanudar tráfico. Reprocesar manualmente eventos enriquecidos evitando duplicados.

Los eventos checkout.requested y order.created conservan email de contacto y añaden tenantId/objectId. No contienen JWT, access token ni secretos. Los payloads antiguos se deserializan con IDs null; orders los rechaza hacia DLQ. La queue clásica existente tiene dead-lettering de mejor esfuerzo: respaldar/drenar eventos durante el rollout y vigilar DLQ, no asumir entrega exactamente una vez ni garantía quorum sin migración aparte. Los consumidores deben tolerar redelivery.

RabbitMQ OSS permanece privado en ECS sobre EC2. Se conserva `camisetas360.orders` y queues independientes de notifications/recommendation. No se sustituye por Amazon MQ/SQS/SNS/EventBridge. Recommendation sigue como worker privado sin Resource Server ni endpoint de negocio.

## Health y reinicios

Todos los servicios exponen sin JWT únicamente:

| Path | Uso | Dependencias |
|---|---|---|
| GET /actuator/health | ALB, 200 cuando proceso y DB están sanos | agregación; Rabbit/SMTP excluidos |
| GET /actuator/health/liveness | ECS container health | livenessState, sin DB/Rabbit/SMTP/Mistral |
| GET /actuator/health/readiness | disponibilidad operacional | readinessState + DB |

Health usa show-details=never y show-components=never. No se publican env/info/beans. Evitar enrutar Actuator por el gateway público. La caída breve de RabbitMQ, SMTP o Mistral no afecta a liveness ni health por esos indicadores. Recuperación AMQP usa reconnect del cliente; orders y recommendation tienen missing-queues-fatal=false y retries acotados. Un fallo real de DB sí afecta readiness y el agregado.

AWS ECS puede reemplazar tasks marcadas unhealthy por ALB; evaluar este comportamiento si se usa agregado/readiness durante una caída prolongada de DB. Configurar container health con liveness, grace period y circuit breaker, y evaluar un target-group path de liveness si se necesita evitar reemplazos por DB. No convertir checks de SMTP/Mistral/Rabbit en liveness. Supervisar esas dependencias por métricas, backlog/DLQ y alarmas privadas.

## CORS

En producción, **API Gateway backend es responsable de CORS** para la solicitud que realiza el navegador. Spring tiene app.cors.enabled=false por defecto, no genera headers CORS y permite OPTIONS sin abrir endpoints de negocio. Configurar OPTIONS sin authorizer JWT en Gateway y CORS consistente también en respuestas 401/403/errores. ALB interno no define una segunda política. El gateway frontend no sustituye CORS del origen que recibe realmente la petición API.

Configurar y comprobar CORS del gateway antes de desplegar este cambio. Si el gateway actual todavía depende de headers del backend, el navegador bloqueará llamadas hasta completar esa configuración externa; el repositorio no contiene evidencia suficiente para afirmar que ya está instalada.

Solo el perfil local habilita CORS Spring con CORS_ALLOWED_ORIGIN (default localhost para desarrollo). GET/POST/OPTIONS, headers Authorization/Content-Type/Accept, credentials=false. Nunca usar wildcard de origen con credentials=true. No activar local en ECS. Configurar el dominio real del frontend en Gateway; no inferirlo del repositorio.

## Validación externa de Entra — PENDIENTE DE VALIDACIÓN EN ENTRA

No se dispone de acceso validado al portal/manifiesto real. El repositorio contiene issuer/audience configurables; eso no prueba assignments ni tokens de producción.

- [ ] App Registration/API correcta, Application (client) ID y Application ID URI.
- [ ] Tenant e issuer v2.0 exactos; ENTRA_ISSUER_URI y ENTRA_AUDIENCE coinciden con el access token de esta API, no con el frontend ni otra API.
- [ ] appRoles[].value contiene exactamente CUSTOMER, ADMIN, CATALOG_MANAGER. Revisar value, no solo displayName (displayName Customer y value CUSTOMER es válido; value Customer no concede ROLE_CUSTOMER).
- [ ] allowedMemberTypes, roles habilitados y assignments de usuarios/grupos efectivos. No atribuir CUSTOMER a ADMIN automáticamente.
- [ ] oauth2PermissionScopes[].value: Profile.Read, Catalog.Read, Checkout.Create, Orders.Read, Notifications.Send, habilitados y consentidos al cliente correcto.
- [ ] Token real emitido para backend: iss, aud, exp, nbf si existe, scp, roles; para checkout/orders tid y oid UUID coherentes con tenant/usuario. Verificar mediante decodificación local segura sin guardar tokens en Git ni logs.
- [ ] El cliente solicita el recurso/scopes correcto y envía access token, no ID token. Verificar roles efectivos y sensibilidad a mayúsculas en pruebas positivas/negativas.
- [ ] Prueba token de otra API/tenant, expirado y usuario sin scope/rol: 401 o 403 esperado. JWT delegado necesita scope y rol; app-only sin scp no obtiene acceso por tener únicamente roles.

## Checklist AWS — PENDIENTE DE VALIDACIÓN EN AWS

- [ ] API Gateway → VPC Link → ALB interno → ECS EC2 y subnets privadas, sin public IP para workers/broker.
- [ ] Target Groups de auth/catalog/carrito/orders/notifications: GET /actuator/health, HTTP 200 sin Authorization; comprobar healthy y grace period.
- [ ] Recommendation: health privado en su task/target operativo si existe, sin ruta pública /api/v1/recommendations. No añadir un ALB público para el worker.
- [ ] Container health de los seis servicios: /actuator/health/liveness. Parar temporalmente Rabbit/SMTP/Mistral y confirmar que el proceso sano no entra en bucle de reemplazo; reconexión y backlog deben recuperarse.
- [ ] Actuator no publica detalles ni endpoints sensibles por Gateway; endpoints de negocio sin token 401, sin scope/rol 403.
- [ ] Gateway CORS: origen frontend real, preflight sin JWT, headers Authorization/Content-Type/Accept y errores consistentes; sin políticas Spring contradictorias.
- [ ] SG: AMQP5672 solo desde SG de productores/consumidores autorizados; management15672 solo operación privada, nunca 0.0.0.0/0. Credenciales desde Secrets Manager/configuración segura.
- [ ] DNS interno estable, volumen persistente y recuperación del broker ECS EC2; publisher confirms, acknowledgements, redelivery, DLQ, alarmas y pruebas de restart/reconnect.
- [ ] Política DLQ checkout instalada y binding efectivo; respaldar mensajes históricos, evitar versiones mixtas y completar backfill verificado antes de prometer acceso a órdenes antiguas.
- [ ] Validar issuer/audience y perfil production efectivo en tasks; no activar local ni colocar passwords/access tokens en Git.

## Ejecutar pruebas

Desde cada servicio: `.\mvnw.cmd clean verify`. Genera Surefire, Failsafe y JaCoCo HTML/XML en target/site/jacoco. Docker debe estar activo para PostgreSQL y RabbitMQ Testcontainers; no se deshabilitan tests.

Desde raíz: `.\orders\mvnw.cmd -f tests/messaging/pom.xml -Prabbit clean verify`, seguido de `.\orders\mvnw.cmd -f tests/e2e/pom.xml -Pe2e clean verify` usando los JARs verificados. `python tests/security/test_deployment_contract.py` valida la DLQ desplegable. Las suites compartidas están en tests/security y verifican authorities exactas, claims, health y CORS.

Los runners `python tests/compose/verify_postgres_compose.py` y `python tests/compose/verify_stack_compose.py` completan los jobs Compose de CI con proyectos, credenciales y volúmenes desechables propios. No operan sobre el stack local del desarrollador; el segundo utiliza los JARs previamente verificados y un issuer RS256 efímero. Evidencias sin tokens en tests/compose/target; limpiar exclusivamente recursos del proyecto de prueba.

Referencias: [claims y autorización Entra](https://learn.microsoft.com/en-us/entra/identity-platform/claims-validation), [Actuator y probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).
