#!/usr/bin/env bash
# Paso 2: conecta las rutas de api-camisetas360-backend (creadas por 01-api-gateway.sh) con los
# microservicios: VPC Link (grupo camisetas360-vpclink) -> listener del NLB interno de cada servicio.
# Requiere que deploy/aws/provision.sh ya haya creado el NLB, sus listeners y el grupo del VPC Link.
# Es idempotente: reutiliza el VPC Link y las integraciones existentes y solo re-apunta las rutas.
#
#   bash 02-integraciones.sh
set -euo pipefail

# El AWS CLI de Windows (Git Bash) termina las lineas con \r.
aws() { command aws "$@" | tr -d '\r'; }

export AWS_DEFAULT_REGION=us-east-1
BACKEND_API=api-camisetas360-backend
NLB_NAME=camisetas360-nlb
VPCLINK_NAME=camisetas360-vpclink
SG_VPCLINK_NAME=camisetas360-vpclink

# Prefijo de ruta -> puerto del listener del NLB (puertos de produccion, ver CLAUDE.md).
declare -A PUERTOS=(
  [/api/v1/catalog]=8080
  [/api/v1/auth]=8081
  [/api/v1/orders]=8082
  [/api/v1/carrito]=8083
  [/api/v1/recomendaciones]=8084
  [/api/v1/notifications]=8085
  [/api/v1/analytics]=8086
  [/api/v1/pago]=8087
)

fail() { echo "ERROR: $*" >&2; exit 1; }

API_ID="$(aws apigatewayv2 get-apis --query "Items[?Name=='${BACKEND_API}'].ApiId | [0]" --output text)"
[ -n "${API_ID}" ] && [ "${API_ID}" != "None" ] || fail "no existe ${BACKEND_API}; ejecuta antes 01-api-gateway.sh"

read -r NLB_ARN VPC_ID < <(aws elbv2 describe-load-balancers --names "${NLB_NAME}" \
  --query 'LoadBalancers[0].[LoadBalancerArn,VpcId]' --output text) || fail "no existe el NLB ${NLB_NAME}"
mapfile -t SUBNETS < <(aws elbv2 describe-load-balancers --load-balancer-arns "${NLB_ARN}" \
  --query 'LoadBalancers[0].AvailabilityZones[].SubnetId' --output text | tr '\t' '\n')
SG_ID="$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=${SG_VPCLINK_NAME}" "Name=vpc-id,Values=${VPC_ID}" \
  --query 'SecurityGroups[0].GroupId' --output text)"
[ -n "${SG_ID}" ] && [ "${SG_ID}" != "None" ] || fail "no existe el grupo ${SG_VPCLINK_NAME} (lo crea provision.sh)"

# --- VPC Link -------------------------------------------------------------------------
VPCLINK_ID="$(aws apigatewayv2 get-vpc-links --query "Items[?Name=='${VPCLINK_NAME}'].VpcLinkId | [0]" --output text)"
if [ -z "${VPCLINK_ID}" ] || [ "${VPCLINK_ID}" = "None" ]; then
  VPCLINK_ID="$(aws apigatewayv2 create-vpc-link --name "${VPCLINK_NAME}" \
    --subnet-ids "${SUBNETS[@]}" --security-group-ids "${SG_ID}" --query VpcLinkId --output text)"
  echo "+ VPC Link ${VPCLINK_NAME} (${VPCLINK_ID})"
else
  echo "= VPC Link ${VPCLINK_NAME} (${VPCLINK_ID})"
fi
for _ in $(seq 1 60); do
  ESTADO="$(aws apigatewayv2 get-vpc-link --vpc-link-id "${VPCLINK_ID}" --query VpcLinkStatus --output text)"
  [ "${ESTADO}" = "AVAILABLE" ] && break
  [ "${ESTADO}" = "FAILED" ] && fail "el VPC Link quedo en FAILED"
  echo "  esperando VPC Link (${ESTADO})..."; sleep 10
done
[ "${ESTADO}" = "AVAILABLE" ] || fail "el VPC Link no quedo AVAILABLE"

# --- Integraciones (una por listener) ---------------------------------------------------
declare -A INTEGRACION=()
for prefijo in "${!PUERTOS[@]}"; do
  puerto="${PUERTOS[${prefijo}]}"
  [ -n "${INTEGRACION[${puerto}]:-}" ] && continue
  LISTENER="$(aws elbv2 describe-listeners --load-balancer-arn "${NLB_ARN}" \
    --query "Listeners[?Port==\`${puerto}\`].ListenerArn | [0]" --output text)"
  [ -n "${LISTENER}" ] && [ "${LISTENER}" != "None" ] || fail "el NLB no tiene listener en ${puerto}"
  ID="$(aws apigatewayv2 get-integrations --api-id "${API_ID}" \
    --query "Items[?IntegrationUri=='${LISTENER}' && ConnectionId=='${VPCLINK_ID}'].IntegrationId | [0]" --output text)"
  if [ -z "${ID}" ] || [ "${ID}" = "None" ]; then
    ID="$(aws apigatewayv2 create-integration --api-id "${API_ID}" \
      --integration-type HTTP_PROXY --integration-method ANY \
      --connection-type VPC_LINK --connection-id "${VPCLINK_ID}" \
      --integration-uri "${LISTENER}" --payload-format-version 1.0 \
      --timeout-in-millis 29000 --query IntegrationId --output text)"
    echo "+ integracion :${puerto} (${ID})"
  else
    echo "= integracion :${puerto} (${ID})"
  fi
  INTEGRACION[${puerto}]="${ID}"
done

# --- Rutas -> integracion ---------------------------------------------------------------
SIN_DESTINO=0
while read -r ROUTE_ID METODO RUTA; do
  [ -z "${ROUTE_ID}" ] && continue
  puerto=""
  for prefijo in "${!PUERTOS[@]}"; do
    case "${RUTA}" in "${prefijo}"|"${prefijo}"/*) puerto="${PUERTOS[${prefijo}]}" ;; esac
  done
  if [ -z "${puerto}" ]; then
    echo "! ${METODO} ${RUTA}: ningun servicio coincide, se deja sin destino"; SIN_DESTINO=1; continue
  fi
  aws apigatewayv2 update-route --api-id "${API_ID}" --route-id "${ROUTE_ID}" \
    --target "integrations/${INTEGRACION[${puerto}]}" >/dev/null
  echo "  ${METODO} ${RUTA} -> :${puerto}"
done < <(aws apigatewayv2 get-routes --api-id "${API_ID}" \
  --query 'Items[].[RouteId,RouteKey]' --output text)

echo
echo "Listo. URL del backend para el frontend: $(aws apigatewayv2 get-api --api-id "${API_ID}" --query ApiEndpoint --output text)"
exit "${SIN_DESTINO}"
