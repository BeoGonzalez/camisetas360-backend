#!/usr/bin/env bash
# Paso 0: Amazon Cognito para el login de la tienda. Idempotente (crea lo que falte, actualiza la
# configuracion de lo existente, nunca borra). Ejecutar en Git Bash o CloudShell (us-east-1):
#
#   bash deploy/aws/00-cognito.sh
#
# Crea o reutiliza:
#   - User pool camisetas360-users: login con email, politica de contrasena, recuperacion por email.
#   - Resource server camisetas360-api con los scopes de la API (Profile.Read, Catalog.Read,
#     Checkout.Create, Orders.Read, Notifications.Send) para el flujo Hosted UI (code + PKCE).
#   - Grupos CUSTOMER, CATALOG_MANAGER y ADMIN (los roles que leen los microservicios).
#   - Dominio del Hosted UI camisetas360-<cuenta>.auth.us-east-1.amazoncognito.com.
#   - App client publico camisetas360-web (SPA, sin secreto): USER_PASSWORD_AUTH (Lambda de login),
#     SRP, refresh y code + PKCE con callbacks del frontend (API del frontend si existe + localhost).
#   - Lambda camisetas360-cognito-login: Java 21, jar de telemetria/ (handler CognitoLoginHandler), rol
#     LabRole: login, refresh, registro (grupo CUSTOMER) y confirmacion. 01-api-gateway.sh la publica en
#     /api/v1/auth/cognito/*. Requiere JDK 21 (compila con mvnw) o LAMBDA_JAR=<jar ya compilado>.
#
# Al final imprime las variables de GitHub (Repository variables) que usa deploy-backend.yml.
# Re-ejecutarlo despues de 01-api-gateway.sh agrega la URL del frontend a los callbacks.
#
# Opcionales: AUTO_CONFIRM=false (exige codigo por email al registrarse), FRONTEND_URL=https://... extra.
set -euo pipefail

export MSYS_NO_PATHCONV=1
aws() { command aws "$@" | tr -d '\r'; }

export AWS_DEFAULT_REGION=us-east-1
REGION=us-east-1
POOL_NAME=camisetas360-users
RESOURCE_SERVER_ID=camisetas360-api
WEB_CLIENT_NAME=camisetas360-web
LAMBDA_NAME=camisetas360-cognito-login
FRONTEND_API=api-gateway-frontend
AUTO_CONFIRM="${AUTO_CONFIRM:-true}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-lambda-jar.sh
source "${SCRIPT_DIR}/lib-lambda-jar.sh"
SCOPES=(Profile.Read Catalog.Read Checkout.Create Orders.Read Notifications.Send)

log() { echo "$*" >&2; }
fail() { echo "ERROR: $*" >&2; exit 1; }
none() { [ -z "$1" ] || [ "$1" = "None" ] || [ "$1" = "null" ]; }

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
none "${ACCOUNT_ID}" && fail "sin credenciales de AWS"

# --- User pool -----------------------------------------------------------------------------
POOL_ID="$(aws cognito-idp list-user-pools --max-results 60 \
  --query "UserPools[?Name=='${POOL_NAME}'].Id | [0]" --output text)"
POLICIES='{"PasswordPolicy":{"MinimumLength":8,"RequireUppercase":true,"RequireLowercase":true,"RequireNumbers":true,"RequireSymbols":false,"TemporaryPasswordValidityDays":7}}'
RECOVERY='{"RecoveryMechanisms":[{"Priority":1,"Name":"verified_email"}]}'
if none "${POOL_ID}"; then
  POOL_ID="$(aws cognito-idp create-user-pool --pool-name "${POOL_NAME}" \
    --username-attributes email --auto-verified-attributes email \
    --username-configuration CaseSensitive=false \
    --policies "${POLICIES}" --account-recovery-setting "${RECOVERY}" \
    --admin-create-user-config AllowAdminCreateUserOnly=false \
    --schema Name=email,AttributeDataType=String,Required=true,Mutable=true \
             Name=name,AttributeDataType=String,Required=false,Mutable=true \
    --user-pool-tags Project=camisetas360 \
    --query UserPool.Id --output text)"
  log "+ user pool ${POOL_NAME} (${POOL_ID})"
else
  log "= user pool ${POOL_NAME} (${POOL_ID})"
fi
ISSUER="https://cognito-idp.${REGION}.amazonaws.com/${POOL_ID}"

# --- Resource server (scopes de la API) ----------------------------------------------------
SCOPES_JSON="["
for s in "${SCOPES[@]}"; do SCOPES_JSON+="{\"ScopeName\":\"${s}\",\"ScopeDescription\":\"camisetas360 ${s}\"},"; done
SCOPES_JSON="${SCOPES_JSON%,}]"
if aws cognito-idp describe-resource-server --user-pool-id "${POOL_ID}" --identifier "${RESOURCE_SERVER_ID}" >/dev/null 2>&1; then
  aws cognito-idp update-resource-server --user-pool-id "${POOL_ID}" --identifier "${RESOURCE_SERVER_ID}" \
    --name "${RESOURCE_SERVER_ID}" --scopes "${SCOPES_JSON}" >/dev/null
  log "= resource server ${RESOURCE_SERVER_ID}"
else
  aws cognito-idp create-resource-server --user-pool-id "${POOL_ID}" --identifier "${RESOURCE_SERVER_ID}" \
    --name "${RESOURCE_SERVER_ID}" --scopes "${SCOPES_JSON}" >/dev/null
  log "+ resource server ${RESOURCE_SERVER_ID}"
fi

# --- Grupos (precedencia: menor = mas prioritario) -----------------------------------------
crear_grupo() {
  if aws cognito-idp get-group --user-pool-id "${POOL_ID}" --group-name "$1" >/dev/null 2>&1; then
    log "= grupo $1"
  else
    aws cognito-idp create-group --user-pool-id "${POOL_ID}" --group-name "$1" \
      --precedence "$2" --description "$3" >/dev/null
    log "+ grupo $1"
  fi
}
crear_grupo ADMIN 1 "Administradores (Kafka UI, alertas de fraude, notificaciones)"
crear_grupo CATALOG_MANAGER 5 "Gestion del catalogo (productos, stock, precios, fotos)"
crear_grupo CUSTOMER 10 "Clientes de la tienda (rol por defecto al registrarse)"

# --- Dominio del Hosted UI -------------------------------------------------------------------
DOMAIN="$(aws cognito-idp describe-user-pool --user-pool-id "${POOL_ID}" --query 'UserPool.Domain' --output text)"
if none "${DOMAIN}"; then
  DOMAIN="camisetas360-${ACCOUNT_ID}"
  aws cognito-idp create-user-pool-domain --user-pool-id "${POOL_ID}" --domain "${DOMAIN}" >/dev/null
  log "+ dominio ${DOMAIN}"
else
  log "= dominio ${DOMAIN}"
fi
HOSTED_UI="https://${DOMAIN}.auth.${REGION}.amazoncognito.com"

# --- App client publico del frontend ---------------------------------------------------------
FRONTEND_ID="$(aws apigatewayv2 get-apis --query "Items[?Name=='${FRONTEND_API}'].ApiId | [0]" --output text)"
CALLBACKS=(http://localhost:4200 http://localhost:4201 http://localhost:5173)
if ! none "${FRONTEND_ID}"; then
  CALLBACKS=("$(aws apigatewayv2 get-api --api-id "${FRONTEND_ID}" --query ApiEndpoint --output text)" "${CALLBACKS[@]}")
fi
[ -n "${FRONTEND_URL:-}" ] && CALLBACKS=("${FRONTEND_URL%/}" "${CALLBACKS[@]}")
CALLBACK_URLS=(); for u in "${CALLBACKS[@]}"; do CALLBACK_URLS+=("${u}/" "${u}/auth/callback"); done
OAUTH_SCOPES=(openid email profile aws.cognito.signin.user.admin)
for s in "${SCOPES[@]}"; do OAUTH_SCOPES+=("${RESOURCE_SERVER_ID}/${s}"); done

CLIENT_ARGS=(--user-pool-id "${POOL_ID}" --client-name "${WEB_CLIENT_NAME}"
  --explicit-auth-flows ALLOW_USER_PASSWORD_AUTH ALLOW_USER_SRP_AUTH ALLOW_REFRESH_TOKEN_AUTH
  --prevent-user-existence-errors ENABLED --enable-token-revocation
  --access-token-validity 60 --id-token-validity 60 --refresh-token-validity 30
  --token-validity-units AccessToken=minutes,IdToken=minutes,RefreshToken=days
  --supported-identity-providers COGNITO
  --allowed-o-auth-flows code --allowed-o-auth-flows-user-pool-client
  --allowed-o-auth-scopes "${OAUTH_SCOPES[@]}"
  --callback-urls "${CALLBACK_URLS[@]}" --logout-urls "${CALLBACK_URLS[@]}"
  --read-attributes email email_verified name --write-attributes email name)
CLIENT_ID="$(aws cognito-idp list-user-pool-clients --user-pool-id "${POOL_ID}" --max-results 60 \
  --query "UserPoolClients[?ClientName=='${WEB_CLIENT_NAME}'].ClientId | [0]" --output text)"
if none "${CLIENT_ID}"; then
  CLIENT_ID="$(aws cognito-idp create-user-pool-client "${CLIENT_ARGS[@]}" --no-generate-secret \
    --query UserPoolClient.ClientId --output text)"
  log "+ app client ${WEB_CLIENT_NAME} (${CLIENT_ID})"
else
  aws cognito-idp update-user-pool-client --client-id "${CLIENT_ID}" "${CLIENT_ARGS[@]}" >/dev/null
  log "= app client ${WEB_CLIENT_NAME} (${CLIENT_ID})"
fi

# --- Lambda de login / registro (Java 21, jar unico de telemetria/) ---------------------------
construir_jar
LAMBDA_ENV="{\"Variables\":{\"USER_POOL_ID\":\"${POOL_ID}\",\"CLIENT_ID\":\"${CLIENT_ID}\",\"DEFAULT_GROUP\":\"CUSTOMER\",\"AUTO_CONFIRM\":\"${AUTO_CONFIRM}\"}}"
desplegar_lambda "${LAMBDA_NAME}" com.camisetas360.telemetria.CognitoLoginHandler::handleRequest   1024 20 "${LAMBDA_ENV}" >/dev/null

echo
echo "Listo. User pool ${POOL_ID}, app client ${CLIENT_ID}, Hosted UI ${HOSTED_UI}"
echo
echo "Variables de GitHub (Settings > Secrets and variables > Actions > Variables):"
echo "  COGNITO_ENABLED=true"
echo "  COGNITO_ISSUER_URI=${ISSUER}"
echo "  COGNITO_CLIENT_IDS=${CLIENT_ID}"
echo "  COGNITO_RESOURCE_SERVER_ID=${RESOURCE_SERVER_ID}"
echo "  COGNITO_ALLOWED_GROUPS=CUSTOMER,CATALOG_MANAGER,ADMIN"
echo "  COGNITO_DEFAULT_ROLE=CUSTOMER"
echo "  COGNITO_USER_INFO_URI=${HOSTED_UI}/oauth2/userInfo"
echo
echo "Frontend: userPoolId=${POOL_ID} clientId=${CLIENT_ID} dominio=${HOSTED_UI}"
echo "Administrador: aws cognito-idp admin-add-user-to-group --user-pool-id ${POOL_ID} --username <email> --group-name ADMIN"
