#!/usr/bin/env bash
# Paso 1: crea las dos HTTP API (frontend y backend) con sus rutas, sin integraciones todavia.
# Ejecutar en AWS CloudShell (us-east-1). Es idempotente: si la API o una ruta ya existe, la reutiliza.
#
#   bash 01-api-gateway.sh
#
# Las integraciones (VPC Link -> NLB) y la Lambda autorizadora se agregan en pasos posteriores.
set -euo pipefail

export AWS_DEFAULT_REGION=us-east-1
BACKEND_API=camisetas360-backend-api
FRONTEND_API=camisetas360-frontend-api
# Origenes permitidos por CORS. Cuando exista la URL del frontend se agrega con el paso de CORS.
CORS_ORIGINS='["http://localhost:5173"]'

BACKEND_ROUTES=(
  "GET /api/v1/auth/profile"
  "GET /api/v1/catalog/products"
  "GET /api/v1/catalog/products/{sku}"
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

CORS_JSON="{\"AllowOrigins\":${CORS_ORIGINS},\"AllowHeaders\":[\"authorization\",\"content-type\"],\"AllowMethods\":[\"GET\",\"POST\",\"PUT\",\"DELETE\",\"OPTIONS\"],\"MaxAge\":300}"

BACKEND_ID="$(crear_api "${BACKEND_API}" "${CORS_JSON}")"
crear_rutas "${BACKEND_ID}" "${BACKEND_ROUTES[@]}"

FRONTEND_ID="$(crear_api "${FRONTEND_API}" "")"
crear_rutas "${FRONTEND_ID}" "${FRONTEND_ROUTES[@]}"

echo
echo "URL backend : $(aws apigatewayv2 get-api --api-id "${BACKEND_ID}" --query ApiEndpoint --output text)"
echo "URL frontend: $(aws apigatewayv2 get-api --api-id "${FRONTEND_ID}" --query ApiEndpoint --output text)"
