#!/usr/bin/env bash
# Funciones comunes de 01-api-gateway.sh y 03-frontend.sh (se cargan con `source`, no se ejecuta solo):
# authorizer JWT de Cognito, integracion con la Lambda de login y que rutas son publicas.
#
# Seguridad en dos capas: el API Gateway rechaza (401) las peticiones sin un access token valido del
# user pool en las rutas privadas, y cada microservicio vuelve a validar el token y decide rol + scope
# (403). Las rutas publicas no llevan authorizer.
#
# GATEWAY_JWT=cognito (por defecto) | off. Con "off" todas las rutas quedan sin authorizer (p. ej. si el
# frontend tambien inicia sesion con Microsoft Entra ID: un authorizer JWT del API Gateway admite un solo
# issuer). Los microservicios validan igual ambos issuers.

COGNITO_POOL_NAME="${COGNITO_POOL_NAME:-camisetas360-users}"
COGNITO_WEB_CLIENT_NAME="${COGNITO_WEB_CLIENT_NAME:-camisetas360-web}"
COGNITO_LAMBDA_NAME="${COGNITO_LAMBDA_NAME:-camisetas360-cognito-login}"
AUTHORIZER_NAME=cognito-jwt
GATEWAY_JWT="${GATEWAY_JWT:-cognito}"
# Rutas de la Lambda de login (sin token: es donde se obtiene).
COGNITO_LAMBDA_ROUTES=(
  "POST /api/v1/auth/cognito/login"
  "POST /api/v1/auth/cognito/register"
  "POST /api/v1/auth/cognito/refresh"
  "POST /api/v1/auth/cognito/confirm"
)
# Rutas publicas del backend: lecturas del catalogo y los retornos de Webpay (el navegador vuelve
# desde Transbank sin bearer token). Deben coincidir con los permitAll() de cada SecurityConfig.
PUBLIC_API_ROUTES=(
  "GET /api/v1/catalog/products"
  "GET /api/v1/catalog/products/{sku}"
  "GET /api/v1/catalog/products/{sku}/imagen"
  "GET /api/v1/pago/retorno"
  "GET /api/v1/pago/webpay/retorno"
  "POST /api/v1/pago/webpay/retorno"
)

_none() { [ -z "$1" ] || [ "$1" = "None" ] || [ "$1" = "null" ]; }

# Ruta publica: la pagina (nginx), Kafka UI (tiene su propio login OAuth2), la Lambda de login y PUBLIC_API_ROUTES.
es_publica() {
  local ruta="$1" metodo path
  metodo="${ruta%% *}"; path="${ruta#* }"
  case "${path}" in
    /|/\{proxy+\}) [ "${metodo}" = "GET" ] && return 0 ;;
    /kafka-ui|/kafka-ui/*) return 0 ;;
    /api/v1/auth/cognito/*) return 0 ;;
  esac
  local p
  for p in "${PUBLIC_API_ROUTES[@]}"; do [ "${p}" = "${ruta}" ] && return 0; done
  return 1
}

# Exporta COGNITO_POOL_ID, COGNITO_ISSUER, COGNITO_CLIENT_ID y COGNITO_LAMBDA_ARN (vacios si no existen).
cargar_cognito() {
  COGNITO_POOL_ID="$(aws cognito-idp list-user-pools --max-results 60 \
    --query "UserPools[?Name=='${COGNITO_POOL_NAME}'].Id | [0]" --output text)"
  COGNITO_ISSUER="" COGNITO_CLIENT_ID="" COGNITO_LAMBDA_ARN=""
  if ! _none "${COGNITO_POOL_ID}"; then
    COGNITO_ISSUER="https://cognito-idp.${AWS_DEFAULT_REGION}.amazonaws.com/${COGNITO_POOL_ID}"
    COGNITO_CLIENT_ID="$(aws cognito-idp list-user-pool-clients --user-pool-id "${COGNITO_POOL_ID}" --max-results 60 \
      --query "UserPoolClients[?ClientName=='${COGNITO_WEB_CLIENT_NAME}'].ClientId | [0]" --output text)"
    _none "${COGNITO_CLIENT_ID}" && COGNITO_CLIENT_ID=""
  else
    COGNITO_POOL_ID=""
  fi
  COGNITO_LAMBDA_ARN="$(aws lambda get-function --function-name "${COGNITO_LAMBDA_NAME}" \
    --query 'Configuration.FunctionArn' --output text 2>/dev/null || true)"
  _none "${COGNITO_LAMBDA_ARN}" && COGNITO_LAMBDA_ARN=""
  return 0
}

# Authorizer JWT del user pool (audiencia = app client web; Cognito pone client_id en el access token).
# Imprime su id, o nada si no hay pool o GATEWAY_JWT=off.
asegurar_authorizer() {
  local api="$1" id config
  [ "${GATEWAY_JWT}" = "off" ] && return 0
  [ -n "${COGNITO_ISSUER}" ] && [ -n "${COGNITO_CLIENT_ID}" ] || return 0
  config="Audience=${COGNITO_CLIENT_ID},Issuer=${COGNITO_ISSUER}"
  id="$(aws apigatewayv2 get-authorizers --api-id "${api}" \
    --query "Items[?Name=='${AUTHORIZER_NAME}'].AuthorizerId | [0]" --output text)"
  if _none "${id}"; then
    id="$(aws apigatewayv2 create-authorizer --api-id "${api}" --name "${AUTHORIZER_NAME}" \
      --authorizer-type JWT --identity-source '$request.header.Authorization' \
      --jwt-configuration "${config}" --query AuthorizerId --output text)"
    echo "  + authorizer ${AUTHORIZER_NAME} (${id})" >&2
  else
    aws apigatewayv2 update-authorizer --api-id "${api}" --authorizer-id "${id}" \
      --jwt-configuration "${config}" >/dev/null
    echo "  = authorizer ${AUTHORIZER_NAME} (${id})" >&2
  fi
  echo "${id}"
}

# Integracion AWS_PROXY con la Lambda de login + permiso para que este API la invoque. Imprime su id.
integracion_lambda() {
  local api="$1" id cuenta
  [ -n "${COGNITO_LAMBDA_ARN}" ] || return 0
  id="$(aws apigatewayv2 get-integrations --api-id "${api}" \
    --query "Items[?IntegrationUri=='${COGNITO_LAMBDA_ARN}'].IntegrationId | [0]" --output text)"
  if _none "${id}"; then
    id="$(aws apigatewayv2 create-integration --api-id "${api}" --integration-type AWS_PROXY \
      --integration-uri "${COGNITO_LAMBDA_ARN}" --payload-format-version 2.0 \
      --timeout-in-millis 29000 --query IntegrationId --output text)"
    echo "  + integracion Lambda ${COGNITO_LAMBDA_NAME} (${id})" >&2
  else
    # 29 s (maximo del HTTP API): el primer login tras un arranque en frio de Java tarda unos segundos.
    aws apigatewayv2 update-integration --api-id "${api}" --integration-id "${id}"       --timeout-in-millis 29000 >/dev/null
  fi
  cuenta="$(cut -d: -f5 <<< "${COGNITO_LAMBDA_ARN}")"
  aws lambda add-permission --function-name "${COGNITO_LAMBDA_NAME}" --statement-id "apigw-${api}" \
    --action lambda:InvokeFunction --principal apigateway.amazonaws.com \
    --source-arn "arn:aws:execute-api:${AWS_DEFAULT_REGION}:${cuenta}:${api}/*/*/api/v1/auth/cognito/*" \
    >/dev/null 2>&1 || true
  echo "${id}"
}

# Crea (o re-apunta) las rutas de la Lambda de login en el API.
rutas_lambda() {
  local api="$1" integ existentes ruta rid
  integ="$(integracion_lambda "${api}")"
  if [ -z "${integ}" ]; then
    echo "  ! no existe la Lambda ${COGNITO_LAMBDA_NAME}: ejecuta antes 00-cognito.sh" >&2; return 0
  fi
  existentes="$(aws apigatewayv2 get-routes --api-id "${api}" --query 'Items[].[RouteKey,RouteId]' --output text)"
  for ruta in "${COGNITO_LAMBDA_ROUTES[@]}"; do
    rid="$(awk -F'\t' -v k="${ruta}" '$1==k {print $2}' <<< "${existentes}")"
    if [ -z "${rid}" ]; then
      aws apigatewayv2 create-route --api-id "${api}" --route-key "${ruta}" \
        --target "integrations/${integ}" --authorization-type NONE >/dev/null
      echo "  + ${ruta} -> Lambda" >&2
    else
      aws apigatewayv2 update-route --api-id "${api}" --route-id "${rid}" \
        --target "integrations/${integ}" --authorization-type NONE >/dev/null
      echo "  = ${ruta} -> Lambda" >&2
    fi
  done
}

# Pone el authorizer JWT en cada ruta privada y lo quita de las publicas.
aplicar_seguridad() {
  local api="$1" auth="$2" rid ruta tipo
  while IFS=$'\t' read -r rid ruta tipo; do
    [ -z "${rid}" ] && continue
    if [ -z "${auth}" ] || es_publica "${ruta}"; then
      [ "${tipo}" = "NONE" ] || aws apigatewayv2 update-route --api-id "${api}" --route-id "${rid}" \
        --authorization-type NONE >/dev/null
      echo "  publica  ${ruta}" >&2
    else
      aws apigatewayv2 update-route --api-id "${api}" --route-id "${rid}" \
        --authorization-type JWT --authorizer-id "${auth}" >/dev/null
      echo "  JWT      ${ruta}" >&2
    fi
  done < <(aws apigatewayv2 get-routes --api-id "${api}" \
    --query 'Items[].[RouteId,RouteKey,AuthorizationType]' --output text)
}
