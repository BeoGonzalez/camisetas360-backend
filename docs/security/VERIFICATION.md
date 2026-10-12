# Evidencia de seguridad y regresión

Verificación local del código final. No equivale a validación del tenant Entra real ni despliegue en AWS. Contrato y checklists: [ENTRA-SECURITY.md](ENTRA-SECURITY.md).

## Resultado final

| Módulo | Tests Surefire + Failsafe | clean verify | JaCoCo líneas | JaCoCo ramas |
|---|---:|---|---:|---:|
| auth | 62 | BUILD SUCCESS | 93.14% | 69.23% |
| catalog | 60 | BUILD SUCCESS | 97.26% | 82.61% |
| carrito | 94 | BUILD SUCCESS | 94.41% | 73.08% |
| orders | 133 | BUILD SUCCESS | 90.03% | 68.75% |
| notifications | 73 | BUILD SUCCESS | 95.86% | 68.42% |
| recommendation | 11 | BUILD SUCCESS | 95.35% | 83.33% |

Los seis módulos suman **433 tests**, 0 failures, 0 errors y 0 skipped. Cada módulo conserva su configuración JaCoCo y genera HTML/XML en target/site/jacoco/index.html y jacoco.xml. Los XML y logs definitivos no contienen advertencias de clases incompatibles con los datos de ejecución. No se dispone de una medición inicial comparable para afirmar un incremento de cobertura.

| Regresión adicional | Resultado |
|---|---|
| tests/messaging: -Prabbit clean verify | BUILD SUCCESS: 5 contratos + 5 pruebas PostgreSQL/RabbitMQ real |
| tests/e2e: -Pe2e clean verify | BUILD SUCCESS: 2 escenarios con JARs definitivos, JWT RS256, PostgreSQL, RabbitMQ y SMTP |
| Python broker deployment contract | PASS: DLQ durable y política sin cambiar argumentos de queue existente |
| Compose PostgreSQL | PASS: seis bases, credenciales incorrectas rechazadas, redes aisladas y persistencia tras recreación |
| Compose stack | PASS: seis health paths sin token/detalles; perfil, catálogo, checkout, ownership, RabbitMQ/SMTP y worker |
| Compose dependencias caídas | PASS: detener RabbitMQ/SMTP conserva agregado y liveness UP en seis procesos; reiniciar broker responde al diagnóstico |
| git diff --check | PASS |

Recommendation sigue sin Spring Security/Resource Server. Su integración RabbitMQ valida redelivery, inbox idempotente y DLQ sin bearer; su API de negocio inexistente responde 404. Compose comprueba recepción en recommendation_order_inbox y que no exista /api/v1/recommendations.

Los scripts Compose que CI referenciaba no estaban presentes. Se añadieron runners aislados que usan JARs verificados, proyectos/volúmenes/credenciales propios y limpieza de esos recursos. No se modifica el stack local existente. La política de DLQ se verifica también en CI.

## Slices y evidencia RED → GREEN → REFACTOR

| Slice | RED observado / evidencia | Implementación y archivos principales | GREEN final / riesgo pendiente |
|---|---|---|---|
| 1. Authorities | 17 escenarios en auth: cuatro fallos por duplicados, authorities vacías y tipos inesperados | Cinco JwtAuthoritiesConverter; adapters JwtAuthoritiesConverterTest; tests/security/AuthoritiesContract; poms para fuente de tests compartida | 17 escenarios por servicio, 85 en total; case sensitivity y precedencia scope/scp conservadas. appRoles reales pendientes externos |
| 2. Health | catalog retornaba 401 en dos checks sin token | SecurityConfig de cinco APIs; application.yaml de seis servicios; Actuator en carrito; HealthContractTest y WorkerHealthTest | Root health/probes sin token, sin detalles; dependencia DOWN no altera liveness. Target Groups AWS pendientes |
| 3. Identidad estable | Cinco escenarios orders fallaron al probar identidad ausente, inválida o contacto cambiante | EntraIdentity en carrito/orders; IdentityContract y EntraIdentityTest | tid+oid UUID, sin fallback a email. Confirmar claims reales Entra |
| 4. Persistencia | PostgreSQL migration test falló por ausencia de tenant_id/object_id | Order y CheckoutRequest; carrito V2__add_immutable_owner.sql, orders V5__add_immutable_owner.sql; OwnershipMigrationIT | Migración desde esquema anterior retiene registros con owner NULL y rechaza pares incompletos. Backfill verificado pendiente |
| 5. Eventos RabbitMQ | EventContractTest falló por campos tenantId/objectId ausentes | CheckoutRequestedEvent carrito/orders; OrderCreatedEvent orders/notifications; fixtures y tests de serialización | Contratos y RabbitMQ real pasan, sin tokens ni secretos. DLQ debe instalarse antes del rollout |
| 6. Ownership | OrderOwnershipTest falló cinco escenarios; CartServiceTest detectó contacto no controlado | OrderController, OrderService, OrderRepository; CartService y MissingContactEmailException; tests MVC/repositorio/persistencia | Otro oid/tid o mismo email no obtiene acceso; cambio/ausencia de preferred_username no altera lectura; contacto ausente 422 |
| 7. JWT firmado | JWT sin exp devolvía 200 | Cinco JwtValidationConfig, LocalJwtIssuer y JwtSecurityIT; DecoderStartupContract | issuer/audience/firma/exp/nbf/malformed/claims y roles/scopes pasan. Discovery offline tuvo RED por connection refused y GREEN con SupplierJwtDecoder |
| 8. CORS | Preflight de producción devolvía 403 | Cinco SecurityConfig, perfiles application-local.yaml, tests CORS locales y OPTIONS real por API | Producción sin headers backend; local permite origen explícito y credentials=false. Configurar Gateway ANTES de desplegar |
| 9. Maven/CI | Se conservaron los tests originales; detectaron fixtures/migration expectations obsoletos y aislamiento incorrecto del contexto de health | Tests existentes ajustados a identidad/schema reales; tests/compose runners; CI deployment contract | Seis clean verify y jobs equivalentes pasan. No tests deshabilitados |
| 10. Checklists | No acceso autenticado comprobable a Entra/AWS | docs/security/ENTRA-SECURITY.md; README | Checklists completos. PENDIENTE DE VALIDACIÓN EN ENTRA y AWS |

El refactor final conserva scopes y roles, AND, stateless, CSRF disabled y denyAll. Discovery/JWKS lazy permite startup/health offline; requests autenticados fallan cerrado cuando no pueden validar. Se repitieron clean verify y regresiones sobre los JARs finales.

## Pruebas nuevas y existentes

- Compartidas: authorities exactas (scope/scp strings/listas, roles, ausencia, vacíos, duplicados, tipos y casing); identidad UUID; root health/liveness; preflight de producción; startup offline y issuer/audience obligatorios.
- HTTP: 401 sin token; 403 sin scope/rol o ADMIN sin CUSTOMER; roles permitidos auth/catalog con JWT realmente firmado; contact missing 422 y email claim sin preferred_username.
- Decoder real: issuer/audience incorrectos, expiración, nbf futuro, exp ausente, firma con otra clave y mismo kid, JWT malformado, claims de identidad faltantes.
- Ownership: owner correcto, oid distinto, tenant distinto, mismo email con otra identidad, legacy no reclamable, query compuesta en PostgreSQL.
- E2E: renombrar/eliminar preferred_username con identidad constante conserva acceso; otra identidad con mismo email obtiene 404; evento conserva IDs y órdenes persisten UUIDs.
- Migraciones: datos históricos preservados y CHECK de pares de identidad; esquema PostgreSQL validado con Flyway, no ddl-auto=update.
- Regresión existente: perfiles, catálogo/JPA, checkout/JPA, creación de órdenes/items/transacciones/outbox, envío y registro de email, contratos, RabbitMQ y recommendation inbox siguen pasando.

## Pendientes antes de producción

1. **PENDIENTE DE VALIDACIÓN EN ENTRA:** appRoles[].value exactos, scopes consentidos, Application ID URI/client ID/audience, tenant/issuer, assignments y access-token claims reales.
2. **PENDIENTE DE VALIDACIÓN EN AWS:** healthy en Target Groups internos sin JWT, CORS Gateway real, SG/DNS/secretos/storage RabbitMQ y recuperación ECS.
3. Instalar DLQ/política y preparar rollout coordinado carrito/orders; evitar versiones mixtas.
4. Backfill histórico únicamente con evidencia autoritativa y auditable; no inferir ownership del email. Los datos antiguos siguen almacenados pero sin acceso por la API hasta completar ese mapping.
5. El checkout aún usa transacción DB + publicación confirmada, no una transacción distribuida/outbox de carrito. La creación de órdenes no deduplica checkout.eventId. Son límites previos del flujo: no afirmar exactly-once ni idempotencia completa de ese consumidor. Recommendation sí deduplica eventId y orders conserva su outbox existente.
6. La queue clásica checkout conserva su tipo; DLQ de mejor esfuerzo. No se promete semántica quorum at-least-once para esa queue sin una migración distinta. Respaldar/drenar durante rollout y vigilar DLQ.

No se desplegó infraestructura, no se modificaron credenciales externas y no se ejecutó backfill de producción.
