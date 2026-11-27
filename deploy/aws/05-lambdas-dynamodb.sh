#!/usr/bin/env bash
# Paso 5: telemetria en tiempo real con DynamoDB + AWS Lambda, desde el mismo jar que el login de Cognito
# (telemetria/, Java 21). Idempotente (crea lo que falte, actualiza codigo y configuracion, nunca borra):
#
#   bash deploy/aws/05-lambdas-dynamodb.sh
#
#   DynamoDB camisetas360-analytics-productos (PK sku, Streams NEW_IMAGE)
#        │ trigger (lote 25, LATEST)
#        ▼
#   Lambda stream     (TelemetriaStreamHandler)  -- postToConnection -->  clientes wss://
#   Lambda mensajero  (MensajeroHandler)  <-- $connect/$disconnect/$default --  API WebSocket api-gateway-telemetria
#        │ guarda/borra conexiones
#        ▼
#   DynamoDB ConexionesWebSocket (PK connectionId, TTL expiresAt)
#
# $connect acepta access tokens de Cognito (user pool de 00-cognito.sh, grupos ADMIN o CATALOG_MANAGER)
# y, si se definen ENTRA_ISSUER_URI y ENTRA_AUDIENCE, tambien de Entra ID. Las tablas las crea tambien
# analytics/provision.sh al arrancar; aqui se crean si faltan para poder enganchar el trigger ya.
#
# Opcionales: ENTRA_ISSUER_URI, ENTRA_AUDIENCE, WS_AUTH_ENABLED=false (solo pruebas con wscat),
# LAMBDA_JAR=<jar ya compilado>, DYNAMODB_TABLE_PREFIX (defecto camisetas360-analytics-).
set -euo pipefail

export MSYS_NO_PATHCONV=1
aws() { command aws "$@" | tr -d '\r'; }

export AWS_DEFAULT_REGION=us-east-1
REGION=us-east-1
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-lambda-jar.sh
source "${SCRIPT_DIR}/lib-lambda-jar.sh"
# shellcheck source=lib-seguridad-gateway.sh
source "${SCRIPT_DIR}/lib-seguridad-gateway.sh"

TABLA_PRODUCTOS="${DYNAMODB_TABLE_PREFIX:-camisetas360-analytics-}productos"
TABLA_CONEXIONES=ConexionesWebSocket
WS_API=api-gateway-telemetria
WS_STAGE=prod
MENSAJERO=mensajero
STREAM=stream

log() { echo "$*" >&2; }
fail() { echo "ERROR: $*" >&2; exit 1; }
none() { [ -z "$1" ] || [ "$1" = "None" ] || [ "$1" = "null" ]; }

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
none "${ACCOUNT_ID}" && fail "sin credenciales de AWS"

# --- Tablas DynamoDB ----------------------------------------------------------------------
if ! aws dynamodb describe-table --table-name "${TABLA_CONEXIONES}" >/dev/null 2>&1; then
  aws dynamodb create-table --table-name "${TABLA_CONEXIONES}" \
    --attribute-definitions AttributeName=connectionId,AttributeType=S \
    --key-schema AttributeName=connectionId,KeyType=HASH --billing-mode PAY_PER_REQUEST \
    --tags Key=Project,Value=camisetas360 >/dev/null
  aws dynamodb wait table-exists --table-name "${TABLA_CONEXIONES}"
  aws dynamodb update-time-to-live --table-name "${TABLA_CONEXIONES}" \
    --time-to-live-specification Enabled=true,AttributeName=expiresAt >/dev/null
  log "+ tabla ${TABLA_CONEXIONES} (TTL expiresAt)"
else
  log "= tabla ${TABLA_CONEXIONES}"
fi

# Mismo esquema que crea analytics (DynamoDbTableInitializer): PK sku (S), on-demand, stream NEW_IMAGE.
if ! aws dynamodb describe-table --table-name "${TABLA_PRODUCTOS}" >/dev/null 2>&1; then
  aws dynamodb create-table --table-name "${TABLA_PRODUCTOS}" \
    --attribute-definitions AttributeName=sku,AttributeType=S \
    --key-schema AttributeName=sku,KeyType=HASH --billing-mode PAY_PER_REQUEST \
    --stream-specification StreamEnabled=true,StreamViewType=NEW_IMAGE \
    --tags Key=Project,Value=camisetas360 >/dev/null
  aws dynamodb wait table-exists --table-name "${TABLA_PRODUCTOS}"
  log "+ tabla ${TABLA_PRODUCTOS} (stream NEW_IMAGE)"
else
  VISTA="$(aws dynamodb describe-table --table-name "${TABLA_PRODUCTOS}" \
    --query 'Table.StreamSpecification.StreamViewType' --output text)"
  if [ "${VISTA}" != "NEW_IMAGE" ] && [ "${VISTA}" != "NEW_AND_OLD_IMAGES" ]; then
    none "${VISTA}" || fail "${TABLA_PRODUCTOS} tiene un stream ${VISTA}; cambialo a NEW_IMAGE"
    aws dynamodb update-table --table-name "${TABLA_PRODUCTOS}" \
      --stream-specification StreamEnabled=true,StreamViewType=NEW_IMAGE >/dev/null
    aws dynamodb wait table-exists --table-name "${TABLA_PRODUCTOS}"
    log "= tabla ${TABLA_PRODUCTOS}: stream NEW_IMAGE activado"
  else
    log "= tabla ${TABLA_PRODUCTOS} (stream ${VISTA})"
  fi
fi
STREAM_ARN="$(aws dynamodb describe-table --table-name "${TABLA_PRODUCTOS}" --query 'Table.LatestStreamArn' --output text)"

# --- Jar y Lambda mensajero ------------------------------------------------------------------
construir_jar
cargar_cognito
[ -n "${COGNITO_ISSUER}" ] && [ -n "${COGNITO_CLIENT_ID}" ] || \
  [ -n "${ENTRA_ISSUER_URI:-}" ] || fail "no hay user pool ${COGNITO_POOL_NAME} (00-cognito.sh) ni ENTRA_ISSUER_URI"

VARS="\"TABLA_CONEXIONES\":\"${TABLA_CONEXIONES}\",\"WS_AUTH_ENABLED\":\"${WS_AUTH_ENABLED:-true}\""
[ -n "${COGNITO_ISSUER}" ] && VARS+=",\"COGNITO_ISSUER_URI\":\"${COGNITO_ISSUER}\",\"COGNITO_CLIENT_IDS\":\"${COGNITO_CLIENT_ID}\""
[ -n "${ENTRA_ISSUER_URI:-}" ] && VARS+=",\"ENTRA_ISSUER_URI\":\"${ENTRA_ISSUER_URI}\",\"ENTRA_AUDIENCE\":\"${ENTRA_AUDIENCE:?Define ENTRA_AUDIENCE}\""
MENSAJERO_ARN="$(desplegar_lambda "${MENSAJERO}" com.camisetas360.telemetria.MensajeroHandler::handleRequest \
  512 15 "{\"Variables\":{${VARS}}}")"

# --- API WebSocket ---------------------------------------------------------------------------
WS_ID="$(aws apigatewayv2 get-apis --query "Items[?Name=='${WS_API}'].ApiId | [0]" --output text)"
if none "${WS_ID}"; then
  WS_ID="$(aws apigatewayv2 create-api --name "${WS_API}" --protocol-type WEBSOCKET \
    --route-selection-expression '$request.body.action' --query ApiId --output text)"
  log "+ API WebSocket ${WS_API} (${WS_ID})"
else
  log "= API WebSocket ${WS_API} (${WS_ID})"
fi
INTEGRACION="$(aws apigatewayv2 get-integrations --api-id "${WS_ID}" \
  --query "Items[?contains(IntegrationUri, '${MENSAJERO_ARN}')].IntegrationId | [0]" --output text)"
if none "${INTEGRACION}"; then
  INTEGRACION="$(aws apigatewayv2 create-integration --api-id "${WS_ID}" --integration-type AWS_PROXY \
    --integration-uri "arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/${MENSAJERO_ARN}/invocations" \
    --query IntegrationId --output text)"
  log "  + integracion -> ${MENSAJERO}"
fi
RUTAS="$(aws apigatewayv2 get-routes --api-id "${WS_ID}" --query 'Items[].[RouteKey,RouteId]' --output text)"
for ruta in '$connect' '$disconnect' '$default'; do
  rid="$(awk -F'\t' -v k="${ruta}" '$1==k {print $2}' <<< "${RUTAS}")"
  if [ -z "${rid}" ]; then
    aws apigatewayv2 create-route --api-id "${WS_ID}" --route-key "${ruta}" \
      --target "integrations/${INTEGRACION}" >/dev/null
    log "  + ruta ${ruta}"
  else
    aws apigatewayv2 update-route --api-id "${WS_ID}" --route-id "${rid}" \
      --target "integrations/${INTEGRACION}" >/dev/null
    log "  = ruta ${ruta}"
  fi
done
if ! aws apigatewayv2 get-stage --api-id "${WS_ID}" --stage-name "${WS_STAGE}" >/dev/null 2>&1; then
  aws apigatewayv2 create-stage --api-id "${WS_ID}" --stage-name "${WS_STAGE}" --auto-deploy >/dev/null
  log "  + etapa ${WS_STAGE}"
fi
aws apigatewayv2 create-deployment --api-id "${WS_ID}" --stage-name "${WS_STAGE}" >/dev/null 2>&1 || true
aws lambda add-permission --function-name "${MENSAJERO}" --statement-id "apigw-ws-${WS_ID}" \
  --action lambda:InvokeFunction --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:${REGION}:${ACCOUNT_ID}:${WS_ID}/*" >/dev/null 2>&1 || true
WS_HTTPS="https://${WS_ID}.execute-api.${REGION}.amazonaws.com/${WS_STAGE}"

# --- Lambda stream + trigger de DynamoDB -------------------------------------------------------
desplegar_lambda "${STREAM}" com.camisetas360.telemetria.TelemetriaStreamHandler::handleRequest 512 30 \
  "{\"Variables\":{\"TABLA_CONEXIONES\":\"${TABLA_CONEXIONES}\",\"WEBSOCKET_API_ENDPOINT\":\"${WS_HTTPS}\"}}" >/dev/null
MAPPING="$(aws lambda list-event-source-mappings --function-name "${STREAM}" \
  --query "EventSourceMappings[?EventSourceArn=='${STREAM_ARN}'].UUID | [0]" --output text)"
if none "${MAPPING}"; then
  aws lambda create-event-source-mapping --function-name "${STREAM}" --event-source-arn "${STREAM_ARN}" \
    --batch-size 25 --starting-position LATEST --maximum-retry-attempts 3 \
    --bisect-batch-on-function-error >/dev/null
  log "  + trigger DynamoDB ${TABLA_PRODUCTOS} -> ${STREAM}"
else
  log "  = trigger DynamoDB ${TABLA_PRODUCTOS} -> ${STREAM}"
fi

echo
echo "Listo. Dashboard en tiempo real:"
echo "  wss://${WS_ID}.execute-api.${REGION}.amazonaws.com/${WS_STAGE}?token=<access token de Cognito (ADMIN/CATALOG_MANAGER)>"
echo "  Lambdas ${MENSAJERO} y ${STREAM} (Java 21, jar $(basename "${LAMBDA_JAR}"))"
