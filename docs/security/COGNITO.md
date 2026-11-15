# Amazon Cognito en el servicio `auth`

`auth` acepta access tokens de **dos issuers**: Microsoft Entra ID (siempre) y Amazon Cognito
(opcional, `COGNITO_ENABLED=true`). Cada token se enruta por su claim `iss` exacto a su propio
decoder/JWKS/validadores; un issuer desconocido recibe `401`.

> Alcance: solo `auth` confia en Cognito. `catalog`, `carrito`, `orders`, `notifications`,
> `recommendation` y `analytics` siguen aceptando unicamente tokens de Entra ID.

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

## Configuracion en AWS (consola de Cognito, us-east-1)

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
