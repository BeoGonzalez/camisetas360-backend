#!/usr/bin/env bash
# Paso 3: publica el frontend (Angular + nginx) detras de api-camisetas360-frontend, un solo origen:
#
#   https://<frontend-api>/                     -> frontend-service (nginx :80) por VPC Link -> NLB :80
#   https://<frontend-api>/api/v1/<servicio>/*  -> microservicio por VPC Link -> NLB :8080..8087
#   POST /api/v1/auth/cognito/{login,register}  -> Lambda camisetas360-cognito-login (ya existente)
#
# Crea lo que falte (no borra nada): grupo de seguridad de las tareas del frontend, target group
# c360-frontend + listener TCP 80 del NLB, log group, task definition frontend-task (si no existe),
# servicio ECS frontend-service, rutas/integraciones del API del frontend, y el CORS de ambos API
# (origen del frontend + localhost:4200/4201 para `pnpm start` contra AWS).
#
# El pipeline del repo camisetas360-frontend (deploy-ecr.yml) despues solo publica imagenes nuevas
# sobre frontend-task / frontend-service. Requiere provision.sh y 02-integraciones.sh ya ejecutados
# y la imagen camisetas360-frontend:latest en ECR.
#
#   bash 03-frontend.sh
set -euo pipefail

# Git Bash en Windows convierte argumentos como /health o /ecs/frontend en rutas de Windows.
export MSYS_NO_PATHCONV=1
aws() { command aws "$@" | tr -d '\r'; }

export AWS_DEFAULT_REGION=us-east-1
REGION=us-east-1
ECS_CLUSTER=ecommerce-cluster
NLB_NAME=camisetas360-nlb
VPCLINK_NAME=camisetas360-vpclink
FRONTEND_API=api-camisetas360-frontend
BACKEND_API=api-camisetas360-backend
ECR_REPOSITORY=camisetas360-frontend
TASK_FAMILY=frontend-task
ECS_SERVICE=frontend-service
CONTAINER=frontend
TG_NAME=c360-frontend
PORT=80
LOG_GROUP=/ecs/frontend
SG_NLB_NAME=camisetas360-nlb
SG_VPCLINK_NAME=camisetas360-vpclink
SG_FRONTEND_NAME=camisetas360-frontend
TAG_KEY=Project
TAG_VALUE=camisetas360
LOCAL_ORIGINS=(http://localhost:4200 http://localhost:4201 http://localhost:5173)

# Prefijo -> puerto del listener del NLB (igual que 02-integraciones.sh).
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

log() { echo "$*" >&2; }
fail() { echo "ERROR: $*" >&2; exit 1; }
none() { [ -z "$1" ] || [ "$1" = "None" ]; }

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
LAB_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/LabRole"
IMAGE="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPOSITORY}:latest"

read -r NLB_ARN VPC_ID < <(aws elbv2 describe-load-balancers --names "${NLB_NAME}" \
  --query 'LoadBalancers[0].[LoadBalancerArn,VpcId]' --output text) || fail "no existe el NLB ${NLB_NAME} (provision.sh)"
SUBNETS_CSV="$(aws elbv2 describe-load-balancers --load-balancer-arns "${NLB_ARN}" \
  --query 'LoadBalancers[0].AvailabilityZones[].SubnetId' --output text | tr '\t' ',')"

aws ecr describe-images --repository-name "${ECR_REPOSITORY}" --image-ids imageTag=latest >/dev/null 2>&1 \
  || fail "no existe ${ECR_REPOSITORY}:latest en ECR; ejecuta antes el pipeline del frontend"

# --- Grupos de seguridad ------------------------------------------------------------------
sg_id() {
  aws ec2 describe-security-groups --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=$1" \
    --query 'SecurityGroups[0].GroupId' --output text
}
allow_tcp() { # destino puerto origen
  local err
  if ! err="$(aws ec2 authorize-security-group-ingress --group-id "$1" \
        --ip-permissions "IpProtocol=tcp,FromPort=$2,ToPort=$2,UserIdGroupPairs=[{GroupId=$3}]" 2>&1 >/dev/null)"; then
    grep -q 'InvalidPermission.Duplicate' <<< "${err}" || { echo "${err}" >&2; return 1; }
  fi
}
SG_NLB="$(sg_id "${SG_NLB_NAME}")"; none "${SG_NLB}" && fail "falta el grupo ${SG_NLB_NAME}"
SG_VPCLINK="$(sg_id "${SG_VPCLINK_NAME}")"; none "${SG_VPCLINK}" && fail "falta el grupo ${SG_VPCLINK_NAME}"
SG_FRONTEND="$(sg_id "${SG_FRONTEND_NAME}")"
if none "${SG_FRONTEND}"; then
  SG_FRONTEND="$(aws ec2 create-security-group --group-name "${SG_FRONTEND_NAME}" \
    --description "Tareas ECS del frontend (nginx)" --vpc-id "${VPC_ID}" \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=${TAG_KEY},Value=${TAG_VALUE}},{Key=Name,Value=${SG_FRONTEND_NAME}}]" \
    --query GroupId --output text)"
  log "+ grupo ${SG_FRONTEND_NAME} (${SG_FRONTEND})"
else
  log "= grupo ${SG_FRONTEND_NAME} (${SG_FRONTEND})"
fi
allow_tcp "${SG_NLB}" "${PORT}" "${SG_VPCLINK}"      # API Gateway -> NLB :80
allow_tcp "${SG_FRONTEND}" "${PORT}" "${SG_NLB}"     # NLB -> nginx (trafico y health check)

# --- Target group + listener --------------------------------------------------------------
TG_ARN="$(aws elbv2 describe-target-groups --names "${TG_NAME}" \
  --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || true)"
if none "${TG_ARN}"; then
  TG_ARN="$(aws elbv2 create-target-group --name "${TG_NAME}" --protocol TCP --port "${PORT}" \
    --vpc-id "${VPC_ID}" --target-type ip --health-check-protocol HTTP --health-check-path /health \
    --health-check-interval-seconds 30 --healthy-threshold-count 3 --unhealthy-threshold-count 3 \
    --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" --query 'TargetGroups[0].TargetGroupArn' --output text)"
  aws elbv2 modify-target-group-attributes --target-group-arn "${TG_ARN}" --attributes \
    Key=deregistration_delay.timeout_seconds,Value=30 Key=preserve_client_ip.enabled,Value=false >/dev/null
  log "+ target group ${TG_NAME}"
else
  log "= target group ${TG_NAME}"
fi
LISTENER_ARN="$(aws elbv2 describe-listeners --load-balancer-arn "${NLB_ARN}" \
  --query "Listeners[?Port==\`${PORT}\`].ListenerArn | [0]" --output text)"
if none "${LISTENER_ARN}"; then
  LISTENER_ARN="$(aws elbv2 create-listener --load-balancer-arn "${NLB_ARN}" --protocol TCP --port "${PORT}" \
    --default-actions "Type=forward,TargetGroupArn=${TG_ARN}" --query 'Listeners[0].ListenerArn' --output text)"
  log "+ listener TCP ${PORT} -> ${TG_NAME}"
else
  log "= listener TCP ${PORT}"
fi

# --- Logs + task definition ---------------------------------------------------------------
aws logs create-log-group --log-group-name "${LOG_GROUP}" 2>/dev/null && log "+ log group ${LOG_GROUP}" || true

if ! aws ecs describe-task-definition --task-definition "${TASK_FAMILY}" >/dev/null 2>&1; then
  # JSON en linea (no archivo temporal): el AWS CLI de Windows no ve las rutas /tmp de Git Bash.
  TD_JSON="$(cat <<JSON
{
  "family": "${TASK_FAMILY}",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "256",
  "memory": "512",
  "executionRoleArn": "${LAB_ROLE_ARN}",
  "taskRoleArn": "${LAB_ROLE_ARN}",
  "containerDefinitions": [{
    "name": "${CONTAINER}",
    "image": "${IMAGE}",
    "essential": true,
    "portMappings": [{"containerPort": ${PORT}, "protocol": "tcp"}],
    "healthCheck": {
      "command": ["CMD-SHELL", "wget -q -O - http://127.0.0.1/health | grep -q OK || exit 1"],
      "interval": 30, "timeout": 5, "retries": 3, "startPeriod": 10
    },
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {"awslogs-group": "${LOG_GROUP}", "awslogs-region": "${REGION}", "awslogs-stream-prefix": "ecs"}
    }
  }]
}
JSON
)"
  aws ecs register-task-definition --cli-input-json "${TD_JSON}" >/dev/null
  log "+ task definition ${TASK_FAMILY} (${IMAGE})"
else
  log "= task definition ${TASK_FAMILY}"
fi

# --- Servicio ECS -------------------------------------------------------------------------
STATUS="$(aws ecs describe-services --cluster "${ECS_CLUSTER}" --services "${ECS_SERVICE}" \
  --query 'services[0].status' --output text 2>/dev/null || true)"
if [ "${STATUS}" != "ACTIVE" ]; then
  aws ecs create-service --cluster "${ECS_CLUSTER}" --service-name "${ECS_SERVICE}" \
    --task-definition "${TASK_FAMILY}" --desired-count 1 --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[${SUBNETS_CSV}],securityGroups=[${SG_FRONTEND}],assignPublicIp=DISABLED}" \
    --load-balancers "targetGroupArn=${TG_ARN},containerName=${CONTAINER},containerPort=${PORT}" \
    --health-check-grace-period-seconds 30 \
    --deployment-configuration "deploymentCircuitBreaker={enable=true,rollback=true},maximumPercent=200,minimumHealthyPercent=100" \
    --tags "key=${TAG_KEY},value=${TAG_VALUE}" >/dev/null
  log "+ servicio ${ECS_SERVICE}"
else
  log "= servicio ${ECS_SERVICE}"
fi

# --- API Gateway del frontend -------------------------------------------------------------
api_id() { aws apigatewayv2 get-apis --query "Items[?Name=='$1'].ApiId | [0]" --output text; }
FRONTEND_ID="$(api_id "${FRONTEND_API}")"; none "${FRONTEND_ID}" && fail "no existe ${FRONTEND_API} (01-api-gateway.sh)"
BACKEND_ID="$(api_id "${BACKEND_API}")"
VPCLINK_ID="$(aws apigatewayv2 get-vpc-links --query "Items[?Name=='${VPCLINK_NAME}'].VpcLinkId | [0]" --output text)"
none "${VPCLINK_ID}" && fail "no existe el VPC Link ${VPCLINK_NAME} (02-integraciones.sh)"

integracion() { # puerto -> id de integracion (VPC Link -> listener)
  local listener id
  listener="$(aws elbv2 describe-listeners --load-balancer-arn "${NLB_ARN}" \
    --query "Listeners[?Port==\`$1\`].ListenerArn | [0]" --output text)"
  none "${listener}" && fail "el NLB no tiene listener en $1"
  id="$(aws apigatewayv2 get-integrations --api-id "${FRONTEND_ID}" \
    --query "Items[?IntegrationUri=='${listener}' && ConnectionId=='${VPCLINK_ID}'].IntegrationId | [0]" --output text)"
  if none "${id}"; then
    id="$(aws apigatewayv2 create-integration --api-id "${FRONTEND_ID}" \
      --integration-type HTTP_PROXY --integration-method ANY \
      --connection-type VPC_LINK --connection-id "${VPCLINK_ID}" \
      --integration-uri "${listener}" --payload-format-version 1.0 \
      --timeout-in-millis 29000 --query IntegrationId --output text)"
    log "+ integracion :$1 (${id})"
  fi
  echo "${id}"
}

ROUTES="$(aws apigatewayv2 get-routes --api-id "${FRONTEND_ID}" --query 'Items[].[RouteKey,RouteId]' --output text)"
ruta() { # "METODO /path" integracion
  local id
  id="$(awk -F'\t' -v k="$1" '$1==k {print $2}' <<< "${ROUTES}")"
  if [ -z "${id}" ]; then
    aws apigatewayv2 create-route --api-id "${FRONTEND_ID}" --route-key "$1" --target "integrations/$2" >/dev/null
    log "+ $1"
  else
    aws apigatewayv2 update-route --api-id "${FRONTEND_ID}" --route-id "${id}" --target "integrations/$2" >/dev/null
    log "= $1"
  fi
}

INT_WEB="$(integracion "${PORT}")"
ruta "GET /" "${INT_WEB}"
ruta "GET /{proxy+}" "${INT_WEB}"
for prefijo in "${!PUERTOS[@]}"; do
  INT="$(integracion "${PUERTOS[${prefijo}]}")"
  ruta "ANY ${prefijo}" "${INT}"
  ruta "ANY ${prefijo}/{proxy+}" "${INT}"
done
# Las rutas POST /api/v1/auth/cognito/{login,register} (Lambda) son mas especificas que
# ANY /api/v1/auth/{proxy+} y siguen yendo a la Lambda: no se tocan.

# --- CORS ---------------------------------------------------------------------------------
FRONTEND_URL="$(aws apigatewayv2 get-api --api-id "${FRONTEND_ID}" --query ApiEndpoint --output text)"
ORIGINS_JSON="$(printf '"%s",' "${FRONTEND_URL}" "${LOCAL_ORIGINS[@]}")"
CORS="{\"AllowOrigins\":[${ORIGINS_JSON%,}],\"AllowHeaders\":[\"authorization\",\"content-type\"],\"AllowMethods\":[\"GET\",\"POST\",\"PUT\",\"DELETE\",\"OPTIONS\"],\"ExposeHeaders\":[\"retry-after\",\"location\"],\"MaxAge\":300}"
aws apigatewayv2 update-api --api-id "${FRONTEND_ID}" --cors-configuration "${CORS}" >/dev/null
log "= CORS ${FRONTEND_API}: ${FRONTEND_URL} ${LOCAL_ORIGINS[*]}"
if ! none "${BACKEND_ID}"; then
  aws apigatewayv2 update-api --api-id "${BACKEND_ID}" --cors-configuration "${CORS}" >/dev/null
  log "= CORS ${BACKEND_API}: ${FRONTEND_URL} ${LOCAL_ORIGINS[*]}"
fi

# --- Espera a que nginx quede sano --------------------------------------------------------
log "Esperando a ${ECS_SERVICE} en el NLB..."
for _ in $(seq 1 40); do
  ESTADO="$(aws elbv2 describe-target-health --target-group-arn "${TG_ARN}" \
    --query 'TargetHealthDescriptions[0].TargetHealth.State' --output text)"
  [ "${ESTADO}" = "healthy" ] && break
  sleep 15
done
[ "${ESTADO}" = "healthy" ] || fail "${ECS_SERVICE} no quedo healthy en ${TG_NAME} (estado: ${ESTADO})"

echo
echo "Listo. Frontend: ${FRONTEND_URL}/"
