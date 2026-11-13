#!/usr/bin/env bash
# =============================================================================
# Aprovisiona en AWS (Learner Lab, us-east-1) la infraestructura que necesita
# deploy-backend.yml. Es idempotente: lo que ya existe se reutiliza y nunca se
# borra ni se modifica (salvo agregar reglas de seguridad que falten).
#
# Orden:
#   1. ECR    repositorios de imagenes
#   2. ECS    cluster, log groups y task definitions iniciales
#   3. NLB    grupos de seguridad, NLB interno, target groups y listeners
#   4. Aurora grupo de seguridad, grupo de subredes, cluster e instancia
#   5. DynamoDB tabla ConexionesWebSocket de la telemetria (las tablas de
#             analytics y recommendation las crea cada servicio al arrancar)
#   6. ECS    servicios (necesitan el target group ya conectado al NLB)
#
# Las credenciales de los contenedores NO se gestionan aqui: se registran a
# mano en cada task definition (ver docs) y deploy-backend.yml las conserva.
#
# Entradas (variables de entorno):
#   CATALOG             catalogo de servicios de deploy-backend.yml (JSON array)
#   ECS_CLUSTER         nombre del cluster (ecommerce-cluster)
#   BACKEND_SUBNET_ID   subred privada 1: NLB, tareas ECS
#   DATABASE_SUBNET_ID  subred privada 2: instancia de Aurora (otra AZ)
#   DB_PASSWORD         contrasena maestra de Aurora (solo al crearla)
#   DB_NAME, DB_USERNAME, DB_INSTANCE_CLASS, CONNECTIONS_TABLE (opcionales)
#
# Salidas (GITHUB_OUTPUT): nlb_dns, db_endpoint
#
# No se usa IAM: en AWS Academy no se pueden crear roles; todo usa LabRole.
# =============================================================================
set -euo pipefail

: "${AWS_REGION:?}" "${ECS_CLUSTER:?}" "${CATALOG:?}"
: "${BACKEND_SUBNET_ID:?Falta la variable BACKEND_SUBNET_ID (subred privada 1)}"
: "${DATABASE_SUBNET_ID:?Falta la variable DATABASE_SUBNET_ID (subred privada 2)}"

DB_NAME="${DB_NAME:-ecommerce}"
DB_USERNAME="${DB_USERNAME:-dbadmin}"
DB_INSTANCE_CLASS="${DB_INSTANCE_CLASS:-db.t4g.medium}"
DB_CLUSTER_ID="camisetas360-aurora"
DB_INSTANCE_ID="camisetas360-aurora-1"
DB_SUBNET_GROUP="camisetas360-db-subnets"
NLB_NAME="camisetas360-nlb"
# Debe coincidir con TABLA_CONEXIONES de las Lambdas de telemetria (mismo valor por defecto).
CONNECTIONS_TABLE="${CONNECTIONS_TABLE:-ConexionesWebSocket}"

# AWS no permite nombres de grupo de seguridad que empiecen con "sg-".
SG_VPCLINK_NAME="camisetas360-vpclink"
SG_NLB_NAME="camisetas360-nlb"
SG_SERVICES_NAME="camisetas360-servicios"
SG_BROKERS_NAME="camisetas360-brokers"
SG_AURORA_NAME="camisetas360-aurora"

TAG_KEY=Project
TAG_VALUE=camisetas360

log() { echo "[$(date -u +%H:%M:%S)] $*"; }
fail() { echo "::error::$*"; exit 1; }
output() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "$1=$2" >> "${GITHUB_OUTPUT}"
  fi
}

# Reintenta un comando: la primera vez que se usa ECS en la cuenta, su rol
# vinculado al servicio puede tardar en existir ("service linked role").
retry() {
  local n
  for n in 1 2 3 4 5; do
    if "$@"; then
      return 0
    fi
    log "  reintento ${n}/5 en 15 s..."
    sleep 15
  done
  return 1
}

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
LAB_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/LabRole"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

read -r VPC_ID BACKEND_AZ < <(aws ec2 describe-subnets --subnet-ids "${BACKEND_SUBNET_ID}" \
  --query 'Subnets[0].[VpcId,AvailabilityZone]' --output text)
read -r DB_VPC_ID DATABASE_AZ < <(aws ec2 describe-subnets --subnet-ids "${DATABASE_SUBNET_ID}" \
  --query 'Subnets[0].[VpcId,AvailabilityZone]' --output text)

[ "${VPC_ID}" = "${DB_VPC_ID}" ] || fail "Las dos subredes deben estar en la misma VPC (${VPC_ID} / ${DB_VPC_ID})."
[ "${BACKEND_AZ}" != "${DATABASE_AZ}" ] || fail "Aurora exige subredes en dos zonas: BACKEND_SUBNET_ID y DATABASE_SUBNET_ID estan ambas en ${BACKEND_AZ}."

log "Cuenta ${ACCOUNT_ID}, VPC ${VPC_ID}, backend ${BACKEND_SUBNET_ID} (${BACKEND_AZ}), base de datos ${DATABASE_SUBNET_ID} (${DATABASE_AZ})"

# Filas del catalogo: service kind repository task-family ecs-service container-name port cpu memory
catalog_rows() {
  jq -r '.[] | [.service, .kind, .repository, .["task-family"], .["ecs-service"], .["container-name"], .port,
                (if .cpu == "" then "512" else .cpu end), (if .memory == "" then "1024" else .memory end)] | @tsv' \
    <<< "${CATALOG}"
}

target_group_name() { echo "c360-$1"; }

# =============================================================================
# 1. ECR
# =============================================================================
log "== 1/6 ECR: repositorios"
while IFS=$'\t' read -r _ _ REPOSITORY _ _ _ _ _ _; do
  if aws ecr describe-repositories --repository-names "${REPOSITORY}" >/dev/null 2>&1; then
    log "  = ${REPOSITORY}"
  else
    aws ecr create-repository --repository-name "${REPOSITORY}" \
      --image-scanning-configuration scanOnPush=true \
      --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" >/dev/null
    log "  + ${REPOSITORY}"
  fi
done < <(catalog_rows)

# =============================================================================
# 2. ECS: cluster, log groups y task definitions iniciales
# =============================================================================
log "== 2/6 ECS: cluster, log groups y task definitions"

CLUSTER_STATUS="$(aws ecs describe-clusters --clusters "${ECS_CLUSTER}" \
  --query 'clusters[0].status' --output text 2>/dev/null || true)"
if [ "${CLUSTER_STATUS}" = "ACTIVE" ]; then
  log "  = cluster ${ECS_CLUSTER}"
else
  retry aws ecs create-cluster --cluster-name "${ECS_CLUSTER}" \
    --capacity-providers FARGATE \
    --default-capacity-provider-strategy capacityProvider=FARGATE,weight=1 \
    --tags "key=${TAG_KEY},value=${TAG_VALUE}" >/dev/null
  log "  + cluster ${ECS_CLUSTER}"
fi

while IFS=$'\t' read -r SERVICE _ REPOSITORY TASK_FAMILY _ CONTAINER PORT CPU MEMORY; do
  LOG_GROUP="/ecs/${SERVICE}"
  if [ "$(aws logs describe-log-groups --log-group-name-prefix "${LOG_GROUP}" \
        --query "logGroups[?logGroupName=='${LOG_GROUP}'] | length(@)" --output text)" = "0" ]; then
    aws logs create-log-group --log-group-name "${LOG_GROUP}" --tags "${TAG_KEY}=${TAG_VALUE}"
    # 7 dias de logs: suficiente para diagnosticar y no consume presupuesto.
    aws logs put-retention-policy --log-group-name "${LOG_GROUP}" --retention-in-days 7
    log "  + log group ${LOG_GROUP}"
  fi

  if aws ecs describe-task-definition --task-definition "${TASK_FAMILY}" >/dev/null 2>&1; then
    log "  = task definition ${TASK_FAMILY}"
    continue
  fi

  # Revision inicial. La imagen ":bootstrap" no existe todavia: el servicio se
  # crea con 0 tareas y deploy-backend.yml registra la revision real (imagen
  # del commit + variables) en el mismo workflow.
  jq -n \
    --arg family "${TASK_FAMILY}" --arg name "${CONTAINER}" --argjson port "${PORT}" \
    --arg image "${REGISTRY}/${REPOSITORY}:bootstrap" --arg cpu "${CPU}" --arg memory "${MEMORY}" \
    --arg role "${LAB_ROLE_ARN}" --arg logGroup "${LOG_GROUP}" --arg region "${AWS_REGION}" \
    '{
      family: $family,
      networkMode: "awsvpc",
      requiresCompatibilities: ["FARGATE"],
      runtimePlatform: {cpuArchitecture: "X86_64", operatingSystemFamily: "LINUX"},
      cpu: $cpu,
      memory: $memory,
      executionRoleArn: $role,
      taskRoleArn: $role,
      containerDefinitions: [{
        name: $name,
        image: $image,
        essential: true,
        portMappings: [{containerPort: $port, protocol: "tcp"}],
        logConfiguration: {
          logDriver: "awslogs",
          options: {"awslogs-group": $logGroup, "awslogs-region": $region, "awslogs-stream-prefix": "ecs"}
        }
      }]
    }' > /tmp/task-definition.json
  aws ecs register-task-definition --cli-input-json file:///tmp/task-definition.json >/dev/null
  log "  + task definition ${TASK_FAMILY} (${CPU} CPU / ${MEMORY} MB)"
done < <(catalog_rows)

# =============================================================================
# 3. NLB: grupos de seguridad, balanceador, target groups y listeners
# =============================================================================
log "== 3/6 NLB: grupos de seguridad, balanceador, target groups y listeners"

sg_id() {
  aws ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=${VPC_ID}" "Name=group-name,Values=$1" \
    --query 'SecurityGroups[0].GroupId' --output text
}

ensure_sg() { # nombre descripcion -> id
  local id
  id="$(sg_id "$1")"
  if [ -z "${id}" ] || [ "${id}" = "None" ]; then
    id="$(aws ec2 create-security-group --group-name "$1" --description "$2" --vpc-id "${VPC_ID}" \
      --tag-specifications "ResourceType=security-group,Tags=[{Key=${TAG_KEY},Value=${TAG_VALUE}},{Key=Name,Value=$1}]" \
      --query GroupId --output text)"
    log "  + grupo de seguridad $1 (${id})" >&2
  else
    log "  = grupo de seguridad $1 (${id})" >&2
  fi
  echo "${id}"
}

allow_tcp() { # grupo-destino puerto-desde puerto-hasta grupo-origen
  local err
  if ! err="$(aws ec2 authorize-security-group-ingress --group-id "$1" \
        --ip-permissions "IpProtocol=tcp,FromPort=$2,ToPort=$3,UserIdGroupPairs=[{GroupId=$4}]" 2>&1 >/dev/null)"; then
    grep -q 'InvalidPermission.Duplicate' <<< "${err}" || { echo "${err}"; return 1; }
  fi
}

SG_VPCLINK="$(ensure_sg "${SG_VPCLINK_NAME}" "VPC Link de API Gateway hacia el NLB")"
SG_NLB="$(ensure_sg "${SG_NLB_NAME}" "NLB interno del backend")"
SG_SERVICES="$(ensure_sg "${SG_SERVICES_NAME}" "Tareas ECS de los microservicios")"
SG_BROKERS="$(ensure_sg "${SG_BROKERS_NAME}" "Tareas ECS de RabbitMQ y Kafka")"

# Solo API Gateway (VPC Link) entra al NLB; los servicios usan el NLB para
# llamarse entre si (carrito -> catalog) y para RabbitMQ/Kafka.
allow_tcp "${SG_NLB}" 8080 8087 "${SG_VPCLINK}"
allow_tcp "${SG_NLB}" 8080 8080 "${SG_SERVICES}"
allow_tcp "${SG_NLB}" 5672 5672 "${SG_SERVICES}"
allow_tcp "${SG_NLB}" 9092 9092 "${SG_SERVICES}"
# Solo el NLB llega a las tareas (trafico y health checks).
allow_tcp "${SG_SERVICES}" 8080 8087 "${SG_NLB}"
allow_tcp "${SG_BROKERS}" 5672 5672 "${SG_NLB}"
allow_tcp "${SG_BROKERS}" 9092 9092 "${SG_NLB}"
log "  reglas de entrada verificadas"

NLB_ARN="$(aws elbv2 describe-load-balancers --names "${NLB_NAME}" \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null || true)"
if [ -z "${NLB_ARN}" ] || [ "${NLB_ARN}" = "None" ]; then
  NLB_ARN="$(aws elbv2 create-load-balancer --name "${NLB_NAME}" --type network --scheme internal \
    --subnets "${BACKEND_SUBNET_ID}" --security-groups "${SG_NLB}" \
    --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" \
    --query 'LoadBalancers[0].LoadBalancerArn' --output text)"
  log "  + NLB ${NLB_NAME}"
  aws elbv2 wait load-balancer-available --load-balancer-arns "${NLB_ARN}"
else
  log "  = NLB ${NLB_NAME}"
fi
NLB_DNS="$(aws elbv2 describe-load-balancers --load-balancer-arns "${NLB_ARN}" \
  --query 'LoadBalancers[0].DNSName' --output text)"

declare -A TARGET_GROUPS
while IFS=$'\t' read -r SERVICE KIND _ _ _ _ PORT _ _; do
  TG_NAME="$(target_group_name "${SERVICE}")"
  TG_ARN="$(aws elbv2 describe-target-groups --names "${TG_NAME}" \
    --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || true)"
  if [ -z "${TG_ARN}" ] || [ "${TG_ARN}" = "None" ]; then
    if [ "${KIND}" = "broker" ]; then
      HEALTH=(--health-check-protocol TCP)
    else
      HEALTH=(--health-check-protocol HTTP --health-check-path /actuator/health/liveness)
    fi
    TG_ARN="$(aws elbv2 create-target-group --name "${TG_NAME}" --protocol TCP --port "${PORT}" \
      --vpc-id "${VPC_ID}" --target-type ip "${HEALTH[@]}" \
      --health-check-interval-seconds 30 --healthy-threshold-count 3 --unhealthy-threshold-count 3 \
      --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" \
      --query 'TargetGroups[0].TargetGroupArn' --output text)"
    # Despliegues mas rapidos y sin conservar la IP del cliente (una tarea que
    # llama al NLB y cae en si misma perderia la conexion).
    aws elbv2 modify-target-group-attributes --target-group-arn "${TG_ARN}" --attributes \
      Key=deregistration_delay.timeout_seconds,Value=30 Key=preserve_client_ip.enabled,Value=false >/dev/null
    log "  + target group ${TG_NAME} (TCP ${PORT})"
  else
    log "  = target group ${TG_NAME}"
  fi
  TARGET_GROUPS["${SERVICE}"]="${TG_ARN}"

  if [ "$(aws elbv2 describe-listeners --load-balancer-arn "${NLB_ARN}" \
        --query "Listeners[?Port==\`${PORT}\`] | length(@)" --output text)" = "0" ]; then
    aws elbv2 create-listener --load-balancer-arn "${NLB_ARN}" --protocol TCP --port "${PORT}" \
      --default-actions "Type=forward,TargetGroupArn=${TG_ARN}" >/dev/null
    log "  + listener TCP ${PORT} -> ${TG_NAME}"
  fi
done < <(catalog_rows)

# =============================================================================
# 4. Aurora PostgreSQL
# =============================================================================
log "== 4/6 Aurora PostgreSQL"

SG_AURORA="$(ensure_sg "${SG_AURORA_NAME}" "Aurora PostgreSQL")"
allow_tcp "${SG_AURORA}" 5432 5432 "${SG_SERVICES}"

if aws rds describe-db-subnet-groups --db-subnet-group-name "${DB_SUBNET_GROUP}" >/dev/null 2>&1; then
  log "  = grupo de subredes ${DB_SUBNET_GROUP}"
else
  aws rds create-db-subnet-group --db-subnet-group-name "${DB_SUBNET_GROUP}" \
    --db-subnet-group-description "Aurora de camisetas360 (privada 1 + privada 2)" \
    --subnet-ids "${BACKEND_SUBNET_ID}" "${DATABASE_SUBNET_ID}" \
    --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" >/dev/null
  log "  + grupo de subredes ${DB_SUBNET_GROUP}"
fi

CLUSTER_STATE="$(aws rds describe-db-clusters --db-cluster-identifier "${DB_CLUSTER_ID}" \
  --query 'DBClusters[0].Status' --output text 2>/dev/null || true)"

if [ -z "${CLUSTER_STATE}" ] || [ "${CLUSTER_STATE}" = "None" ]; then
  : "${DB_PASSWORD:?Falta el secret DB_PASSWORD para crear Aurora}"
  case "${DB_PASSWORD}" in
    */*|*\"*|*@*|*\'*|*' '*) fail "DB_PASSWORD no puede contener / \" @ ' ni espacios (regla de RDS)." ;;
  esac
  [ "${#DB_PASSWORD}" -ge 8 ] || fail "DB_PASSWORD debe tener al menos 8 caracteres."

  ENGINE_VERSION="$(aws rds describe-db-engine-versions --engine aurora-postgresql \
    --query "DBEngineVersions[?starts_with(EngineVersion, '16.')].EngineVersion | [-1]" --output text)"
  if [ -z "${ENGINE_VERSION}" ] || [ "${ENGINE_VERSION}" = "None" ]; then
    fail "No hay versiones 16.x de Aurora PostgreSQL disponibles."
  fi

  aws rds create-db-cluster --db-cluster-identifier "${DB_CLUSTER_ID}" \
    --engine aurora-postgresql --engine-version "${ENGINE_VERSION}" \
    --master-username "${DB_USERNAME}" --master-user-password "${DB_PASSWORD}" \
    --database-name "${DB_NAME}" \
    --db-subnet-group-name "${DB_SUBNET_GROUP}" --vpc-security-group-ids "${SG_AURORA}" \
    --storage-encrypted --backup-retention-period 1 --no-deletion-protection \
    --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" >/dev/null
  log "  + cluster ${DB_CLUSTER_ID} (aurora-postgresql ${ENGINE_VERSION}, base ${DB_NAME})"
elif [ "${CLUSTER_STATE}" = "stopped" ]; then
  aws rds start-db-cluster --db-cluster-identifier "${DB_CLUSTER_ID}" >/dev/null
  log "  ~ cluster ${DB_CLUSTER_ID} estaba detenido: se inicia"
else
  log "  = cluster ${DB_CLUSTER_ID} (${CLUSTER_STATE})"
fi

if aws rds describe-db-instances --db-instance-identifier "${DB_INSTANCE_ID}" >/dev/null 2>&1; then
  log "  = instancia ${DB_INSTANCE_ID}"
else
  ENGINE_VERSION="$(aws rds describe-db-clusters --db-cluster-identifier "${DB_CLUSTER_ID}" \
    --query 'DBClusters[0].EngineVersion' --output text)"
  # AWS Academy solo permite clases burstable hasta "medium".
  CLASS=""
  for candidate in "${DB_INSTANCE_CLASS}" db.t4g.medium db.t3.medium; do
    if [ "$(aws rds describe-orderable-db-instance-options --engine aurora-postgresql \
          --engine-version "${ENGINE_VERSION}" --db-instance-class "${candidate}" \
          --query 'length(OrderableDBInstanceOptions)' --output text)" != "0" ]; then
      CLASS="${candidate}"
      break
    fi
  done
  [ -n "${CLASS}" ] || fail "Ninguna clase de instancia permitida (${DB_INSTANCE_CLASS}, db.t4g.medium, db.t3.medium) sirve para Aurora PostgreSQL ${ENGINE_VERSION}."

  # En la zona de la subred privada 2. Sin monitoreo mejorado ni Performance
  # Insights: AWS Academy no permite el rol que necesitan.
  aws rds create-db-instance --db-instance-identifier "${DB_INSTANCE_ID}" \
    --db-cluster-identifier "${DB_CLUSTER_ID}" --engine aurora-postgresql \
    --db-instance-class "${CLASS}" --availability-zone "${DATABASE_AZ}" \
    --no-publicly-accessible --monitoring-interval 0 --no-enable-performance-insights \
    --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" >/dev/null
  log "  + instancia ${DB_INSTANCE_ID} (${CLASS}, ${DATABASE_AZ})"
fi

log "  esperando que Aurora este disponible (la primera vez tarda unos 10-15 min)..."
aws rds wait db-instance-available --db-instance-identifier "${DB_INSTANCE_ID}"
DB_ENDPOINT="$(aws rds describe-db-clusters --db-cluster-identifier "${DB_CLUSTER_ID}" \
  --query 'DBClusters[0].Endpoint' --output text)"
log "  Aurora disponible: ${DB_ENDPOINT}"

# =============================================================================
# 5. DynamoDB: conexiones del API Gateway WebSocket (telemetria)
# =============================================================================
log "== 5/6 DynamoDB: tabla de conexiones WebSocket"

if aws dynamodb describe-table --table-name "${CONNECTIONS_TABLE}" >/dev/null 2>&1; then
  log "  = tabla ${CONNECTIONS_TABLE}"
else
  aws dynamodb create-table --table-name "${CONNECTIONS_TABLE}" \
    --attribute-definitions AttributeName=connectionId,AttributeType=S \
    --key-schema AttributeName=connectionId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --tags "Key=${TAG_KEY},Value=${TAG_VALUE}" >/dev/null
  aws dynamodb wait table-exists --table-name "${CONNECTIONS_TABLE}"
  log "  + tabla ${CONNECTIONS_TABLE} (on-demand, PK connectionId)"
fi

# TTL sobre expiresAt: borra las conexiones que nunca llamaron a $disconnect
# (API Gateway corta toda conexion a las 2 horas).
TTL_STATUS="$(aws dynamodb describe-time-to-live --table-name "${CONNECTIONS_TABLE}" \
  --query 'TimeToLiveDescription.TimeToLiveStatus' --output text)"
if [ "${TTL_STATUS}" = "ENABLED" ] || [ "${TTL_STATUS}" = "ENABLING" ]; then
  log "  = TTL sobre expiresAt"
else
  aws dynamodb update-time-to-live --table-name "${CONNECTIONS_TABLE}" \
    --time-to-live-specification Enabled=true,AttributeName=expiresAt >/dev/null
  log "  + TTL sobre expiresAt"
fi

# =============================================================================
# 6. ECS: servicios
# =============================================================================
log "== 6/6 ECS: servicios"

while IFS=$'\t' read -r SERVICE KIND _ TASK_FAMILY ECS_SERVICE CONTAINER PORT _ _; do
  STATUS="$(aws ecs describe-services --cluster "${ECS_CLUSTER}" --services "${ECS_SERVICE}" \
    --query 'services[0].status' --output text 2>/dev/null || true)"
  if [ "${STATUS}" = "ACTIVE" ] || [ "${STATUS}" = "DRAINING" ]; then
    log "  = servicio ${ECS_SERVICE}"
    continue
  fi

  if [ "${KIND}" = "broker" ]; then
    SG="${SG_BROKERS}"
    DEPLOYMENT="deploymentCircuitBreaker={enable=true,rollback=true},maximumPercent=100,minimumHealthyPercent=0"
  else
    SG="${SG_SERVICES}"
    DEPLOYMENT="deploymentCircuitBreaker={enable=true,rollback=true},maximumPercent=200,minimumHealthyPercent=100"
  fi

  # 0 tareas: deploy-backend.yml lo sube a 1 con la imagen real.
  retry aws ecs create-service --cluster "${ECS_CLUSTER}" --service-name "${ECS_SERVICE}" \
    --task-definition "${TASK_FAMILY}" --desired-count 0 --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[${BACKEND_SUBNET_ID}],securityGroups=[${SG}],assignPublicIp=DISABLED}" \
    --load-balancers "targetGroupArn=${TARGET_GROUPS[${SERVICE}]},containerName=${CONTAINER},containerPort=${PORT}" \
    --health-check-grace-period-seconds 180 \
    --deployment-configuration "${DEPLOYMENT}" \
    --propagate-tags SERVICE --tags "key=${TAG_KEY},value=${TAG_VALUE}" >/dev/null
  log "  + servicio ${ECS_SERVICE} (0 tareas)"
done < <(catalog_rows)

output nlb_dns "${NLB_DNS}"
output db_endpoint "${DB_ENDPOINT}"
output vpclink_security_group "${SG_VPCLINK}"

log "Listo. NLB: ${NLB_DNS} | Aurora: ${DB_ENDPOINT} | grupo para el VPC Link: ${SG_VPCLINK_NAME} (${SG_VPCLINK})"
