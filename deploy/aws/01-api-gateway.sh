#!/usr/bin/env bash
# Paso 1: crea las dos HTTP API (api-camisetas360-frontend y api-camisetas360-backend) con sus rutas,
# sin integraciones todavia. El CORS del backend permite la URL de la API del frontend y localhost:4200/4201/5173.
# Ejecutar en AWS CloudShell (us-east-1). Es idempotente: si la API o una ruta ya existe, la reutiliza.
#
#   bash 01-api-gateway.sh
#
# Siguientes pasos: 02-integraciones.sh (VPC Link -> NLB para el API del backend) y 03-frontend.sh
# (frontend en ECS + API del frontend como origen unico de la pagina y de /api/v1/*).
set -euo pipefail

# El AWS CLI de Windows (Git Bash) termina las lineas con \r: se limpia para comparar ids y rutas.
aws() { command aws "$@" | tr -d '\r'; }

export AWS_DEFAULT_REGION=us-east-1
BACKEND_API=api-camisetas360-backend
FRONTEND_API=api-camisetas360-frontend
# Origenes locales de desarrollo (Angular `pnpm start` en 4200/4201 contra AWS, Vite en 5173).
# La URL de la API del frontend se agrega sola mas abajo. 03-frontend.sh aplica el mismo CORS.
LOCAL_ORIGINS=(http://localhost:4200 http://localhost:4201 http://localhost:5173)

BACKEND_ROUTES=(
  "GET /api/v1/auth/profile"
  "GET /api/v1/catalog/products"
  "GET /api/v1/catalog/products/{sku}"
  "POST /api/v1/catalog/products"
  "PUT /api/v1/catalog/products/{sku}"
  "DELETE /api/v1/catalog/products/{sku}"
  "GET /api/v1/catalog/products/{sku}/imagen"
  "PUT /api/v1/catalog/products/{sku}/imagen"
  "DELETE /api/v1/catalog/products/{sku}/imagen"
  "GET /api/v1/carrito"
  "POST /api/v1/carrito/items"
  "DELETE /api/v1/carrito/items/{sku}"
  "PUT /api/v1/carrito/envio"
  "POST /api/v1/carrito/checkout"
  "GET /api/v1/orders"
  "GET /api/v1/orders/{orderId}"
  "GET /api/v1/recomendaciones"
  "GET /api/v1/analytics/recomendaciones"
  "GET /api/v1/analytics/metricas"
  "POST /api/v1/notifications/email"
  "POST /api/v1/pago/iniciar"
  "POST /api/v1/pago/confirmar"
  "GET /api/v1/pago/retorno"
  "GET /api/v1/pago/{id}"
  "GET /api/v1/pago/orden/{ordenId}"
  "GET /api/v1/pago/alertas"
  "POST /api/v1/pago/webpay/iniciar"
  "GET /api/v1/pago/webpay/retorno"
  "POST /api/v1/pago/webpay/retorno"
)

FRONTEND_ROUTES=(
  "GET /"
  "GET /{proxy+}"
)

api_id() {
  aws apigatewayv2 get-apis --query "Items[?Name=='$1'].ApiId | [0]" --output text
}

crear_api() {
  local nombre="$1" cors="$2" id
  id="$(api_id "${nombre}")"
  if [ "${id}" = "None" ] || [ -z "${id}" ]; then
    if [ -n "${cors}" ]; then
      id="$(aws apigatewayv2 create-api --name "${nombre}" --protocol-type HTTP \
        --cors-configuration "${cors}" --query ApiId --output text)"
    else
      id="$(aws apigatewayv2 create-api --name "${nombre}" --protocol-type HTTP \
        --query ApiId --output text)"
    fi
    echo "API creada: ${nombre} (${id})" >&2
  else
    echo "API existente: ${nombre} (${id})" >&2
    # Re-ejecutar el script tambien actualiza el CORS (p. ej. si cambio la URL del frontend).
    if [ -n "${cors}" ]; then
      aws apigatewayv2 update-api --api-id "${id}" --cors-configuration "${cors}" >/dev/null
    fi
  fi
  # Etapa $default con despliegue automatico: cada cambio queda publicado sin pasos extra.
  if ! aws apigatewayv2 get-stage --api-id "${id}" --stage-name '$default' >/dev/null 2>&1; then
    aws apigatewayv2 create-stage --api-id "${id}" --stage-name '$default' --auto-deploy >/dev/null
  fi
  echo "${id}"
}

crear_rutas() {
  local id="$1"; shift
  local existentes
  existentes="$(aws apigatewayv2 get-routes --api-id "${id}" --query 'Items[].RouteKey' --output text)"
  for ruta in "$@"; do
    if grep -qxF "${ruta}" <<< "$(tr '\t' '\n' <<< "${existentes}")"; then
      echo "  = ${ruta}"
    else
      aws apigatewayv2 create-route --api-id "${id}" --route-key "${ruta}" >/dev/null
      echo "  + ${ruta}"
    fi
  done
}

# Primero el frontend: el CORS del backend necesita su URL (https://<id>.execute-api.<region>.amazonaws.com).
FRONTEND_ID="$(crear_api "${FRONTEND_API}" "")"
crear_rutas "${FRONTEND_ID}" "${FRONTEND_ROUTES[@]}"
FRONTEND_URL="$(aws apigatewayv2 get-api --api-id "${FRONTEND_ID}" --query ApiEndpoint --output text)"

ORIGINS_JSON="$(printf '"%s",' "${FRONTEND_URL}" "${LOCAL_ORIGINS[@]}")"
CORS_JSON="{\"AllowOrigins\":[${ORIGINS_JSON%,}],\"AllowHeaders\":[\"authorization\",\"content-type\"],\"AllowMethods\":[\"GET\",\"POST\",\"PUT\",\"DELETE\",\"OPTIONS\"],\"ExposeHeaders\":[\"retry-after\",\"location\"],\"MaxAge\":300}"

BACKEND_ID="$(crear_api "${BACKEND_API}" "${CORS_JSON}")"
crear_rutas "${BACKEND_ID}" "${BACKEND_ROUTES[@]}"

echo
echo "URL backend : $(aws apigatewayv2 get-api --api-id "${BACKEND_ID}" --query ApiEndpoint --output text)"
echo "URL frontend: $(aws apigatewayv2 get-api --api-id "${FRONTEND_ID}" --query ApiEndpoint --output text)"
