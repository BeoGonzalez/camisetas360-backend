# Amazon Cognito en el servicio `auth`

`auth` acepta access tokens de **dos issuers**: Microsoft Entra ID (siempre) y Amazon Cognito
(opcional, `COGNITO_ENABLED=true`). Cada token se enruta por su claim `iss` exacto a su propio
decoder/JWKS/validadores; un issuer desconocido recibe `401`.

> Alcance: todos los servicios con API (`auth`, `catalog`, `carrito`, `orders`, `notifications`,
> `recommendation`, `analytics` y `pagos`) confian en Cognito. Grupos por servicio:
> carrito/orders/recommendation `CUSTOMER` (rol por defecto CUSTOMER), notifications `ADMIN` (sin rol por
> defecto), analytics `CUSTOMER,CATALOG_MANAGER,ADMIN` (por defecto CUSTOMER), catalog `CATALOG_MANAGER,ADMIN`,
> pagos `CUSTOMER,ADMIN` (por defecto CUSTOMER; scopes implicitos `Checkout.Create,Orders.Read`).
> Los scopes de cada servicio se conceden implicitamente. Kafka UI (`deploy/aws/04-kafka-ui.sh`, UI for Apache Kafka) solo
> deja entrar al grupo `ADMIN`: `CUSTOMER`, `CATALOG_MANAGER` o una cuenta sin grupos reciben 403
> (filtro `deploy/ecs/kafka-ui/admin-gate`).
>
> **catalog** (administrar productos: stock, precio, fotos) acepta access tokens del mismo pool
> (`COGNITO_ISSUER_URI`) y app clients (`COGNITO_CLIENT_IDS`), con `token_use=access`, `sub` y `username`.
> El rol sale de `cognito:groups` filtrado por `CATALOG_COGNITO_ALLOWED_GROUPS` (por defecto
> `CATALOG_MANAGER,ADMIN`); sin uno de esos grupos el token no puede escribir. `SCOPE_Catalog.Read` se
> concede a todo token de Cognito valido, porque los tokens del login con usuario y contrasena (Lambda
> `camisetas360-cognito-login`) no pueden traer scopes personalizados; la autorizacion la da el grupo.
> Las lecturas del catalogo son publicas e ignoran el token de Cognito.
>
> **carrito** (clientes de la tienda) acepta los mismos tokens. Rol: `cognito:groups` filtrado por
> `CARRITO_COGNITO_ALLOWED_GROUPS` (por defecto `CUSTOMER`); sin grupo permitido se usa
> `CARRITO_COGNITO_DEFAULT_ROLE` (por defecto `CUSTOMER`, vacio = exigir grupo). `SCOPE_Checkout.Create` es
> implicito. El `sub` debe ser un UUID: el dueno del carrito es (UUID v3 de `cognito:<iss>`, `sub`).

## Que valida `auth` en un token de Cognito

| Comprobacion | Regla |
|---|---|
| Firma | JWKS `<issuer>/.well-known/jwks.json` (o `COGNITO_JWK_SET_URI`), cargado de forma perezosa |
| `iss` | Igual a `COGNITO_ISSUER_URI` (sin `/` final, sensible a mayusculas) |
| `exp` / `nbf` | Obligatorio `exp`; tokens expirados -> 401 |
| `token_use` | Debe ser `access` (los ID tokens se rechazan) |
| `client_id` | Debe estar en `COGNITO_CLIENT_IDS` |
| `username` | Obligatorio: los tokens M2M (`client_credentials`) se rechazan |
| `scope` | Solo `<resource-server-id>/X` se convierte en `SCOPE_X` |
| `cognito:groups` | Allowlist `COGNITO_ALLOWED_GROUPS` -> `ROLE_*`; sin grupo valido -> `ROLE_<COGNITO_DEFAULT_ROLE>` |

Los claims `roles` y `scp` de un token de Cognito se ignoran. `ADMIN` nunca se concede salvo que
se incluya explicitamente en `COGNITO_ALLOWED_GROUPS`, y no puede ser el rol por defecto.

`GET /api/v1/auth/profile` exige `ROLE_CUSTOMER|CATALOG_MANAGER|ADMIN` + `SCOPE_Profile.Read`.
El perfil se guarda por `(iss, sub)` con `userId = sub` y `tenantId = <userPoolId>`.

## Configuracion automatica (recomendada)

```bash
bash deploy/aws/00-cognito.sh      # user pool, resource server, grupos, dominio, app client y Lambda de login
bash deploy/aws/01-api-gateway.sh  # HTTP APIs, rutas, rutas de la Lambda y authorizer JWT de Cognito
bash deploy/aws/00-cognito.sh      # otra vez: agrega la URL del API del frontend a los callbacks
bash deploy/aws/05-lambdas-dynamodb.sh  # Lambdas de DynamoDB (mensajero/stream) del mismo jar + WebSocket
```

Ambos son idempotentes. `00-cognito.sh` crea el pool `camisetas360-users` (login con email, contrasena
min. 8 con mayuscula, minuscula y numero), el resource server `camisetas360-api`, los grupos `ADMIN`,
`CATALOG_MANAGER` y `CUSTOMER`, el dominio `camisetas360-<cuenta>`, el app client publico
`camisetas360-web` y la Lambda `camisetas360-cognito-login` (**Java 21**, se sube el jar de `telemetria/`,
handler `com.camisetas360.telemetria.CognitoLoginHandler::handleRequest`, rol `LabRole`; compila con `mvnw`
o usa `LAMBDA_JAR=<jar>`),
e imprime las variables de GitHub a definir (`COGNITO_ENABLED=true`, `COGNITO_ISSUER_URI`,
`COGNITO_CLIENT_IDS`, ...).

### Lambda de login (`/api/v1/auth/cognito/*`, publicas, en ambos API)

| Ruta | Body | Respuesta |
|---|---|---|
| `POST /login` | `{"email","password"}` | `200 {accessToken, idToken, refreshToken, expiresIn, tokenType}` / `401` |
| `POST /refresh` | `{"refreshToken"}` | `200` tokens nuevos |
| `POST /register` | `{"email","password","name"?}` | `201 {userSub, email, confirmed}` / `409` si existe / `400` politica |
| `POST /confirm` | `{"email","code"}` | `200` (solo si `AUTO_CONFIRM=false`) |

El registro deja al usuario en el grupo `CUSTOMER` y, con `AUTO_CONFIRM=true` (defecto, AWS Academy
limita los correos de Cognito), lo confirma sin codigo. El frontend envia el **accessToken** (no el
idToken) como `Authorization: Bearer`. Administradores: `aws cognito-idp admin-add-user-to-group
--user-pool-id <pool> --username <email> --group-name ADMIN` (o `CATALOG_MANAGER`); el grupo nuevo
aparece en el siguiente login.

### API Gateway: authorizer JWT

Ambos HTTP API tienen el authorizer `cognito-jwt` (issuer del pool, audiencia = app client web) en todas
las rutas privadas: sin token o con un token invalido/expirado el gateway responde `401` sin llegar al
servicio, que vuelve a validar el token y decide rol + scope (`403`). Publicas (`lib-seguridad-gateway.sh`):
`GET /api/v1/catalog/products[/{sku}[/imagen]]`, `GET /api/v1/pago/retorno`,
`GET|POST /api/v1/pago/webpay/retorno`, `/api/v1/auth/cognito/*`, la pagina (`GET /`, `GET /{proxy+}`) y
`/kafka-ui`. Si el frontend tambien inicia sesion con Entra ID, ejecuta los scripts con `GATEWAY_JWT=off`
(un authorizer JWT admite un solo issuer); los servicios siguen validando ambos.

## Configuracion manual (consola de Cognito, us-east-1)

1. **User pool**: crea uno (o usa el existente). Anota el *User pool ID* (`us-east-1_XXXXXXXXX`).
2. **Resource server** (*App integration -> Resource servers*):
   - Identifier: `camisetas360-api`
   - Custom scope: `Profile.Read`
3. **App client** (*App integration -> App clients*):
   - Tipo *Public client* (SPA), sin client secret.
   - OAuth grant: *Authorization code* (PKCE).
   - Scopes: `openid`, `email`, `profile`, `camisetas360-api/Profile.Read`.
   - Callback/sign-out URLs del frontend.
   - Anota el *Client ID*.
4. **Dominio** (*Branding -> Domain*): necesario para Hosted UI/Managed login y para `userInfo`.
5. **Grupos** (*User management -> Groups*): `CUSTOMER` (y `CATALOG_MANAGER` si aplica). Un usuario
   sin grupo recibe el rol por defecto (`CUSTOMER`).
6. **Email y nombre** — el access token de Cognito no los incluye. Elige una opcion (o ambas):
   - *Lambda Pre Token Generation* (evento **V2_0**, requiere plan Essentials o Plus) que agregue
     `email` y `name` a `claimsToAddOrOverride` del **access token**.
   - *Fallback userInfo*: define `COGNITO_USER_INFO_URI=https://<dominio>.auth.us-east-1.amazoncognito.com/oauth2/userInfo`.
     Requiere los scopes `openid email profile` en el token. Es best-effort (timeout corto); si falla,
     `/profile` responde `200` y conserva el email/nombre ya guardado.

## Variables de entorno

| Variable | Obligatoria con `enabled=true` | Ejemplo / defecto |
|---|---|---|
| `COGNITO_ENABLED` | — | `false` |
| `COGNITO_ISSUER_URI` | si | `https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX` |
| `COGNITO_CLIENT_IDS` | si | `1a2b3c...` (varios separados por coma) |
| `COGNITO_RESOURCE_SERVER_ID` | si | `camisetas360-api` |
| `COGNITO_JWK_SET_URI` | no | `<issuer>/.well-known/jwks.json` |
| `COGNITO_ALLOWED_GROUPS` | no | `CUSTOMER` (p. ej. `CUSTOMER,CATALOG_MANAGER`) |
| `COGNITO_DEFAULT_ROLE` | no | `CUSTOMER` (debe estar en la allowlist, nunca `ADMIN`) |
| `COGNITO_USER_INFO_URI` | no | vacio = sin fallback |
| `COGNITO_USER_INFO_TIMEOUT` | no | `2s` (maximo `10s`) |

Configuracion incompleta o insegura (http fuera de loopback, `/` final, etc.) hace **fallar el
arranque** con un mensaje `app.security.cognito.*` explicito.

- **Local**: define las variables en `.env`; `docker-compose.yml` las pasa al contenedor `auth`.
- **Produccion (ECS)**: crea las *Repository variables* de GitHub con los mismos nombres. El job
  `preflight` de `deploy-backend.yml` exige `COGNITO_ISSUER_URI`, `COGNITO_CLIENT_IDS` y
  `COGNITO_RESOURCE_SERVER_ID` cuando `COGNITO_ENABLED=true`, y el render de la task definition las
  inyecta solo en el contenedor `auth`.

## Prueba manual

```bash
# Token obtenido via Hosted UI (code + PKCE) con el scope camisetas360-api/Profile.Read
curl -i http://localhost:8083/api/v1/auth/profile -H "Authorization: Bearer $ACCESS_TOKEN"
```

- `200` con `userId` = `sub` y `tenantId` = id del user pool.
- `403` si falta el scope `camisetas360-api/Profile.Read`.
- `401` con ID token, `client_id` no autorizado, token expirado o `COGNITO_ENABLED=false`.

## Tests

- Unitarios: `CognitoPropertiesTest`, `CognitoAuthoritiesConverterTest`, `TrustedIssuersTest`,
  `ClaimsMapperTest`, `CognitoUserInfoClientTest`.
- Integracion (`clean verify`): `CognitoJwtSecurityIT` (Entra + Cognito con JWKS locales y tokens firmados),
  `CognitoDisabledIT`, `CognitoProfilePersistenceIT` (PostgreSQL via Testcontainers).
