#!/usr/bin/env bash
# Paso 4: UI for Apache Kafka (provectuslabs/kafka-ui) en ECS, publicado en el API del frontend, SOLO para cuentas ADMIN:
#
#   https://<frontend-api>/kafka-ui/  -> VPC Link -> NLB :8088 -> kafka-ui-service (:8080)
#   kafka-ui -> NLB :9092 -> kafka-service (bootstrap = KAFKA_ADVERTISED_LISTENERS del broker)
#
# Acceso: login OAuth2 con Amazon Cognito (Hosted UI) y, opcional, Microsoft Entra ID. Solo entran el
# grupo de Cognito ADMIN o el app role de Entra ADMIN: el filtro deploy/ecs/kafka-ui/admin-gate responde 403
# (UI y API) a cualquier otra cuenta (CUSTOMER, CATALOG_MANAGER, sin grupos), y el RBAC de la UI solo da
# permisos de lectura al rol ADMIN. Ademas el cluster es de SOLO LECTURA.
# La configuracion (OAuth2 + RBAC + filtro) esta en deploy/ecs/kafka-ui/ y se publica como imagen propia en ECR.
# Prueba local: python3 tests/kafka-ui/verify_kafka_ui_access.py
#
# Crea lo que falte (no borra nada): dominio de Hosted UI del user pool, app client confidencial
# camisetas360-kafka-ui (code flow, callback /kafka-ui/login/oauth2/code/cognito), grupo ADMIN, repo ECR
# e imagen, grupo de seguridad, target group c360-kafka-ui + listener TCP 8088, log group, task definition
# kafka-ui-task (revision nueva en cada ejecucion), servicio kafka-ui-service y las rutas ANY /kafka-ui y
# /kafka-ui/{proxy+}. Requiere Docker local para construir la imagen.
#
#   bash 04-kafka-ui.sh
#   COGNITO_ISSUER_URI=https://cognito-idp.us-east-1.amazonaws.com/<pool>   (por defecto el pool del proyecto)
#   KAFKA_UI_ENTRA_CLIENT_ID=... KAFKA_UI_ENTRA_CLIENT_SECRET=... [ENTRA_TENANT_ID=...]  (opcional, Entra)
set -euo pipefail

# Git Bash en Windows convierte argumentos como /kafka-ui en rutas de Windows.
export MSYS_NO_PATHCONV=1
aws() { command aws "$@" | tr -d '\r'; }

export AWS_DEFAULT_REGION=us-east-1
REGION=us-east-1
ECS_CLUSTER=ecommerce-cluster
NLB_NAME=camisetas360-nlb
VPCLINK_NAME=camisetas360-vpclink
FRONTEND_API=api-gateway-frontend
ECR_REPOSITORY=camisetas360-kafka-ui
TASK_FAMILY=kafka-ui-task
ECS_SERVICE=kafka-ui-service
CONTAINER=kafka-ui
TG_NAME=c360-kafka-ui
NLB_PORT=8088
CONTAINER_PORT=8080
CONTEXT_PATH=/kafka-ui
CLUSTER_NAME=camisetas360-aws
LOG_GROUP=/ecs/kafka-ui
SG_NLB_NAME=camisetas360-nlb
SG_VPCLINK_NAME=camisetas360-vpclink
SG_KAFKA_UI_NAME=camisetas360-kafka-ui
COGNITO_CLIENT_NAME=camisetas360-kafka-ui
ADMIN_GROUP=ADMIN
TAG_KEY=Project
TAG_VALUE=camisetas360
COGNITO_ISSUER_URI="${COGNITO_ISSUER_URI:-https://cognito-idp.us-east-1.amazonaws.com/us-east-1_SmfKHFzMK}"
ENTRA_TENANT_ID="${ENTRA_TENANT_ID:-e5372bf0-c5e3-4286-887c-79069f209c1f}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_DIR="${SCRIPT_DIR}/../ecs/kafka-ui"

log() { echo "$*" >&2; }
fail() { echo "ERROR: $*" >&2; exit 1; }
none() { [ -z "$1" ] || [ "$1" = "None" ]; }

USER_POOL_ID="${COGNITO_ISSUER_URI##*/}"
[[ "${USER_POOL_ID}" =~ ^us-east-1_[A-Za-z0-9]+$ ]] || fail "COGNITO_ISSUER_URI no termina en un user pool de us-east-1"
ENTRA_ENABLED=false
if [ -n "${KAFKA_UI_ENTRA_CLIENT_ID:-}" ] || [ -n "${KAFKA_UI_ENTRA_CLIENT_SECRET:-}" ]; then
  [ -n "${KAFKA_UI_ENTRA_CLIENT_ID:-}" ] && [ -n "${KAFKA_UI_ENTRA_CLIENT_SECRET:-}" ] \
    || fail "para Entra define KAFKA_UI_ENTRA_CLIENT_ID y KAFKA_UI_ENTRA_CLIENT_SECRET"
  ENTRA_ENABLED=true
fi
command -v docker >/dev/null || fail "se necesita Docker para construir la imagen de Kafka UI"

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
LAB_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/LabRole"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

read -r NLB_ARN VPC_ID NLB_DNS < <(aws elbv2 describe-load-balancers --names "${NLB_NAME}" \
  --query 'LoadBalancers[0].[LoadBalancerArn,VpcId,DNSName]' --output text) || fail "no existe el NLB ${NLB_NAME} (provision.sh)"
SUBNETS_CSV="$(aws elbv2 describe-load-balancers --load-balancer-arns "${NLB_ARN}" \
  --query 'LoadBalancers[0].AvailabilityZones[].SubnetId' --output text | tr '\t' ',')"

FRONTEND_ID="$(aws apigatewayv2 get-apis --query "Items[?Name=='${FRONTEND_API}'].ApiId | [0]" --output text)"
none "${FRONTEND_ID}" && fail "no existe ${FRONTEND_API} (01-api-gateway.sh)"
FRONTEND_URL="$(aws apigatewayv2 get-api --api-id "${FRONTEND_ID}" --query ApiEndpoint --output text)"
BASE_URL="${FRONTEND_URL}${CONTEXT_PATH}"

# Mismo bootstrap que anuncia el broker (KAFKA_ADVERTISED_LISTENERS), para no apuntar a un NLB viejo.
KAFKA_TD="$(aws ecs describe-services --cluster "${ECS_CLUSTER}" --services kafka-service \
  --query 'services[0].taskDefinition' --output text)"
none "${KAFKA_TD}" && fail "no existe kafka-service en ${ECS_CLUSTER} (deploy-backend.yml)"
ADVERTISED="$(aws ecs describe-task-definition --task-definition "${KAFKA_TD}" \
  --query "taskDefinition.containerDefinitions[0].environment[?name=='KAFKA_ADVERTISED_LISTENERS'].value | [0]" --output text)"
BOOTSTRAP="${ADVERTISED#*://}"
none "${ADVERTISED}" && BOOTSTRAP="${NLB_DNS}:9092"
[ "${BOOTSTRAP%%:*}" = "${NLB_DNS}" ] || log "AVISO: Kafka anuncia ${BOOTSTRAP}, distinto del NLB actual ${NLB_DNS}"
log "= bootstrap Kafka ${BOOTSTRAP}"

# --- Cognito: Hosted UI, app client y grupo ADMIN -----------------------------------------
DOMAIN="$(aws cognito-idp describe-user-pool --user-pool-id "${USER_POOL_ID}" --query 'UserPool.Domain' --output text)"
if none "${DOMAIN}"; then
  DOMAIN="camisetas360-${ACCOUNT_ID}"
  aws cognito-idp create-user-pool-domain --user-pool-id "${USER_POOL_ID}" --domain "${DOMAIN}" >/dev/null
  log "+ dominio Hosted UI ${DOMAIN}"
else
  log "= dominio Hosted UI ${DOMAIN}"
fi
HOSTED_UI="https://${DOMAIN}.auth.${REGION}.amazoncognito.com"

CALLBACK="${BASE_URL}/login/oauth2/code/cognito"
CLIENT_ARGS=(--user-pool-id "${USER_POOL_ID}" --client-name "${COGNITO_CLIENT_NAME}"
  --allowed-o-auth-flows code --allowed-o-auth-flows-user-pool-client
  --allowed-o-auth-scopes openid email profile --supported-identity-providers COGNITO
  --callback-urls "${CALLBACK}" --logout-urls "${BASE_URL}/"
  --explicit-auth-flows ALLOW_REFRESH_TOKEN_AUTH --prevent-user-existence-errors ENABLED)
CLIENT_ID="$(aws cognito-idp list-user-pool-clients --user-pool-id "${USER_POOL_ID}" --max-results 60 \
  --query "UserPoolClients[?ClientName=='${COGNITO_CLIENT_NAME}'].ClientId | [0]" --output text)"
if none "${CLIENT_ID}"; then
  CLIENT_ID="$(aws cognito-idp create-user-pool-client "${CLIENT_ARGS[@]}" --generate-secret \
    --query 'UserPoolClient.ClientId' --output text)"
  log "+ app client ${COGNITO_CLIENT_NAME} (${CLIENT_ID})"
else
  # update-user-pool-client reemplaza la configuracion completa: se reenvia toda (sin --generate-secret).
  aws cognito-idp update-user-pool-client --client-id "${CLIENT_ID}" "${CLIENT_ARGS[@]}" >/dev/null
  log "= app client ${COGNITO_CLIENT_NAME} (${CLIENT_ID})"
fi
CLIENT_SECRET="$(aws cognito-idp describe-user-pool-client --user-pool-id "${USER_POOL_ID}" --client-id "${CLIENT_ID}" \
  --query 'UserPoolClient.ClientSecret' --output text)"
none "${CLIENT_SECRET}" && fail "el app client ${COGNITO_CLIENT_NAME} no tiene secreto"
aws cognito-idp get-group --user-pool-id "${USER_POOL_ID}" --group-name "${ADMIN_GROUP}" >/dev/null 2>&1 || {
  aws cognito-idp create-group --user-pool-id "${USER_POOL_ID}" --group-name "${ADMIN_GROUP}" \
    --description "Administradores de Camisetas360 (Kafka UI, catalogo, notificaciones)" >/dev/null
  log "+ grupo Cognito ${ADMIN_GROUP}"
}

# --- Imagen en ECR (provectuslabs/kafka-ui + configuracion OAuth2/RBAC + admin-gate) -----------------------------------
# La etiqueta cambia con cualquier archivo de la imagen (Dockerfile, configuracion y codigo de admin-gate).
IMAGE_TAG="cfg-$(cd "${IMAGE_DIR}" && find . -type f -not -path './admin-gate/target/*' -print0 | LC_ALL=C sort -z   | xargs -0 sha256sum | sha256sum | cut -c1-12)"
IMAGE="${REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}"
aws ecr describe-repositories --repository-names "${ECR_REPOSITORY}" >/dev/null 2>&1 || {
  aws ecr create-repository --repository-name "${ECR_REPOSITORY}" \
    --image-scanning-configuration scanOnPush=true --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" >/dev/null
  log "+ repo ECR ${ECR_REPOSITORY}"
}
if ! aws ecr describe-images --repository-name "${ECR_REPOSITORY}" --image-ids "imageTag=${IMAGE_TAG}" >/dev/null 2>&1; then
  command aws ecr get-login-password | docker login --username AWS --password-stdin "${REGISTRY}" >/dev/null
  docker build -q -t "${IMAGE}" "${IMAGE_DIR}" >/dev/null
  docker push -q "${IMAGE}" >/dev/null
  log "+ imagen ${IMAGE}"
else
  log "= imagen ${IMAGE}"
fi

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
SG_KAFKA_UI="$(sg_id "${SG_KAFKA_UI_NAME}")"
if none "${SG_KAFKA_UI}"; then
  SG_KAFKA_UI="$(aws ec2 create-security-group --group-name "${SG_KAFKA_UI_NAME}" \
    --description "Tareas ECS de Kafka UI" --vpc-id "${VPC_ID}" \
    --tag-specifications "ResourceType=security-group,Tags=[{Key=${TAG_KEY},Value=${TAG_VALUE}},{Key=Name,Value=${SG_KAFKA_UI_NAME}}]" \
    --query GroupId --output text)"
  log "+ grupo ${SG_KAFKA_UI_NAME} (${SG_KAFKA_UI})"
else
  log "= grupo ${SG_KAFKA_UI_NAME} (${SG_KAFKA_UI})"
fi
allow_tcp "${SG_NLB}" "${NLB_PORT}" "${SG_VPCLINK}"           # API Gateway -> NLB :8088
allow_tcp "${SG_KAFKA_UI}" "${CONTAINER_PORT}" "${SG_NLB}"    # NLB -> Kafka UI (trafico y health check)
allow_tcp "${SG_NLB}" 9092 "${SG_KAFKA_UI}"                   # Kafka UI -> NLB :9092 -> broker

# --- Target group + listener --------------------------------------------------------------
TG_ARN="$(aws elbv2 describe-target-groups --names "${TG_NAME}" \
  --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || true)"
if none "${TG_ARN}"; then
  TG_ARN="$(aws elbv2 create-target-group --name "${TG_NAME}" --protocol TCP --port "${CONTAINER_PORT}" \
    --vpc-id "${VPC_ID}" --target-type ip --health-check-protocol HTTP \
    --health-check-path "${CONTEXT_PATH}/actuator/health" \
    --health-check-interval-seconds 30 --healthy-threshold-count 3 --unhealthy-threshold-count 3 \
    --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" --query 'TargetGroups[0].TargetGroupArn' --output text)"
  aws elbv2 modify-target-group-attributes --target-group-arn "${TG_ARN}" --attributes \
    Key=deregistration_delay.timeout_seconds,Value=30 Key=preserve_client_ip.enabled,Value=false >/dev/null
  log "+ target group ${TG_NAME}"
else
  log "= target group ${TG_NAME}"
fi
LISTENER_ARN="$(aws elbv2 describe-listeners --load-balancer-arn "${NLB_ARN}" \
  --query "Listeners[?Port==\`${NLB_PORT}\`].ListenerArn | [0]" --output text)"
if none "${LISTENER_ARN}"; then
  LISTENER_ARN="$(aws elbv2 create-listener --load-balancer-arn "${NLB_ARN}" --protocol TCP --port "${NLB_PORT}" \
    --default-actions "Type=forward,TargetGroupArn=${TG_ARN}" --query 'Listeners[0].ListenerArn' --output text)"
  log "+ listener TCP ${NLB_PORT} -> ${TG_NAME}"
else
  log "= listener TCP ${NLB_PORT}"
fi

# --- Logs + task definition ---------------------------------------------------------------
aws logs create-log-group --log-group-name "${LOG_GROUP}" 2>/dev/null && log "+ log group ${LOG_GROUP}" || true

CONFIG_FILES="file:/etc/kafkaui/kafka-ui.yml"
${ENTRA_ENABLED} && CONFIG_FILES="${CONFIG_FILES},file:/etc/kafkaui/entra.yml"
ENV_VARS=(
  KAFKA_CLUSTERS_0_NAME "${CLUSTER_NAME}"
  KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS "${BOOTSTRAP}"
  KAFKA_CLUSTERS_0_READONLY true
  SERVER_SERVLET_CONTEXT_PATH "${CONTEXT_PATH}"
  SPRING_CONFIG_ADDITIONALLOCATION "${CONFIG_FILES}"
  KAFKA_UI_BASE_URL "${BASE_URL}"
  COGNITO_ISSUER_URI "${COGNITO_ISSUER_URI}"
  KAFKA_UI_COGNITO_CLIENT_ID "${CLIENT_ID}"
  KAFKA_UI_COGNITO_CLIENT_SECRET "${CLIENT_SECRET}"
  KAFKA_UI_COGNITO_LOGOUT_URL "${HOSTED_UI}/logout"
  DYNAMIC_CONFIG_ENABLED false
  JAVA_OPTS "-XX:MaxRAMPercentage=75"
)
if ${ENTRA_ENABLED}; then
  ENV_VARS+=(KAFKA_UI_ENTRA_CLIENT_ID "${KAFKA_UI_ENTRA_CLIENT_ID}" KAFKA_UI_ENTRA_CLIENT_SECRET "${KAFKA_UI_ENTRA_CLIENT_SECRET}"
             ENTRA_TENANT_ID "${ENTRA_TENANT_ID}")
fi
ENV_JSON=""
for ((i = 0; i < ${#ENV_VARS[@]}; i += 2)); do
  case "${ENV_VARS[i+1]}" in *'"'*|*'\'*) fail "valor con comillas o \\ en ${ENV_VARS[i]}";; esac
  ENV_JSON+="${ENV_JSON:+,}{\"name\":\"${ENV_VARS[i]}\",\"value\":\"${ENV_VARS[i+1]}\"}"
done
# JSON en linea (no archivo temporal): el AWS CLI de Windows no ve las rutas /tmp de Git Bash.
TD_JSON="$(cat <<JSON
{
  "family": "${TASK_FAMILY}",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "${LAB_ROLE_ARN}",
  "taskRoleArn": "${LAB_ROLE_ARN}",
  "containerDefinitions": [{
    "name": "${CONTAINER}",
    "image": "${IMAGE}",
    "essential": true,
    "portMappings": [{"containerPort": ${CONTAINER_PORT}, "protocol": "tcp"}],
    "environment": [${ENV_JSON}],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {"awslogs-group": "${LOG_GROUP}", "awslogs-region": "${REGION}", "awslogs-stream-prefix": "ecs"}
    }
  }]
}
JSON
)"
TD_ARN="$(aws ecs register-task-definition --cli-input-json "${TD_JSON}" \
  --query 'taskDefinition.taskDefinitionArn' --output text)"
log "+ task definition ${TD_ARN##*/}"

# --- Servicio ECS -------------------------------------------------------------------------
STATUS="$(aws ecs describe-services --cluster "${ECS_CLUSTER}" --services "${ECS_SERVICE}" \
  --query 'services[0].status' --output text 2>/dev/null || true)"
if [ "${STATUS}" != "ACTIVE" ]; then
  aws ecs create-service --cluster "${ECS_CLUSTER}" --service-name "${ECS_SERVICE}" \
    --task-definition "${TD_ARN}" --desired-count 1 --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[${SUBNETS_CSV}],securityGroups=[${SG_KAFKA_UI}],assignPublicIp=DISABLED}" \
    --load-balancers "targetGroupArn=${TG_ARN},containerName=${CONTAINER},containerPort=${CONTAINER_PORT}" \
    --health-check-grace-period-seconds 90 \
    --deployment-configuration "deploymentCircuitBreaker={enable=true,rollback=true},maximumPercent=200,minimumHealthyPercent=100" \
    --tags "key=${TAG_KEY},value=${TAG_VALUE}" >/dev/null
  log "+ servicio ${ECS_SERVICE}"
else
  aws ecs update-service --cluster "${ECS_CLUSTER}" --service "${ECS_SERVICE}" \
    --task-definition "${TD_ARN}" --desired-count 1 >/dev/null
  log "= servicio ${ECS_SERVICE} -> ${TD_ARN##*/}"
fi

# --- Rutas del API del frontend -----------------------------------------------------------
VPCLINK_ID="$(aws apigatewayv2 get-vpc-links --query "Items[?Name=='${VPCLINK_NAME}'].VpcLinkId | [0]" --output text)"
none "${VPCLINK_ID}" && fail "no existe el VPC Link ${VPCLINK_NAME} (02-integraciones.sh)"
INT_ID="$(aws apigatewayv2 get-integrations --api-id "${FRONTEND_ID}" \
  --query "Items[?IntegrationUri=='${LISTENER_ARN}' && ConnectionId=='${VPCLINK_ID}'].IntegrationId | [0]" --output text)"
if none "${INT_ID}"; then
  INT_ID="$(aws apigatewayv2 create-integration --api-id "${FRONTEND_ID}" \
    --integration-type HTTP_PROXY --integration-method ANY \
    --connection-type VPC_LINK --connection-id "${VPCLINK_ID}" \
    --integration-uri "${LISTENER_ARN}" --payload-format-version 1.0 \
    --timeout-in-millis 29000 --query IntegrationId --output text)"
  log "+ integracion :${NLB_PORT} (${INT_ID})"
fi
ROUTES="$(aws apigatewayv2 get-routes --api-id "${FRONTEND_ID}" --query 'Items[].[RouteKey,RouteId]' --output text)"
for key in "ANY ${CONTEXT_PATH}" "ANY ${CONTEXT_PATH}/{proxy+}"; do
  id="$(awk -F'\t' -v k="${key}" '$1==k {print $2}' <<< "${ROUTES}")"
  if [ -z "${id}" ]; then
    aws apigatewayv2 create-route --api-id "${FRONTEND_ID}" --route-key "${key}" --target "integrations/${INT_ID}" >/dev/null
    log "+ ${key}"
  else
    aws apigatewayv2 update-route --api-id "${FRONTEND_ID}" --route-id "${id}" --target "integrations/${INT_ID}" >/dev/null
    log "= ${key}"
  fi
done

# --- Espera a que la revision nueva quede sana ---------------------------------------------
log "Esperando a ${ECS_SERVICE} (${TD_ARN##*/})..."
aws ecs wait services-stable --cluster "${ECS_CLUSTER}" --services "${ECS_SERVICE}" \
  || fail "${ECS_SERVICE} no se estabilizo (revisa ${LOG_GROUP}; el circuit breaker revierte la revision)"
ACTUAL="$(aws ecs describe-services --cluster "${ECS_CLUSTER}" --services "${ECS_SERVICE}" \
  --query 'services[0].taskDefinition' --output text)"
[ "${ACTUAL}" = "${TD_ARN}" ] || fail "${ECS_SERVICE} quedo en ${ACTUAL##*/} (rollback); revisa ${LOG_GROUP}"

echo
echo "Listo. Kafka UI: ${BASE_URL}/  (login Cognito$(${ENTRA_ENABLED} && echo ' o Entra ID'); solo grupo/rol ${ADMIN_GROUP}, solo lectura)"
echo "Agrega administradores con: aws cognito-idp admin-add-user-to-group --user-pool-id ${USER_POOL_ID} --username <email> --group-name ${ADMIN_GROUP}"
