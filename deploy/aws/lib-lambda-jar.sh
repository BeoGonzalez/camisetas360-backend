#!/usr/bin/env bash
# Funciones comunes de 00-cognito.sh y 05-lambdas-dynamodb.sh (se cargan con `source`): compilan el jar
# unico de Lambdas (proyecto telemetria/, Java 21, sin Spring) y lo suben a cada funcion.
#
#   telemetria/target/telemetria-lambda-0.0.1-SNAPSHOT.jar contiene los tres handlers:
#     com.camisetas360.telemetria.CognitoLoginHandler::handleRequest      (camisetas360-cognito-login)
#     com.camisetas360.telemetria.MensajeroHandler::handleRequest         (mensajero, API WebSocket)
#     com.camisetas360.telemetria.TelemetriaStreamHandler::handleRequest  (stream, DynamoDB Streams)
#
# LAMBDA_JAR=<ruta> usa un jar ya compilado (p. ej. el artefacto lambda-telemetria de CI) y no compila.

LAMBDA_RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAMBDA_RUNTIME=java21

_winpath() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

# Deja en LAMBDA_JAR la ruta del jar (compila con los tests si no se paso uno).
construir_jar() {
  if [ -n "${LAMBDA_JAR:-}" ]; then
    [ -f "${LAMBDA_JAR}" ] || { echo "ERROR: no existe LAMBDA_JAR=${LAMBDA_JAR}" >&2; exit 1; }
    return 0
  fi
  echo "Compilando el jar de Lambdas (telemetria/, mvnw clean verify)..." >&2
  local mvnw=./mvnw
  case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) mvnw=./mvnw.cmd ;; esac
  (cd "${LAMBDA_RAIZ}/telemetria" && "${mvnw}" -B -q -ntp clean verify >&2) \
    || { echo "ERROR: fallo la compilacion o los tests de telemetria/" >&2; exit 1; }
  LAMBDA_JAR="${LAMBDA_RAIZ}/telemetria/target/telemetria-lambda-0.0.1-SNAPSHOT.jar"
  [ -f "${LAMBDA_JAR}" ] || { echo "ERROR: no se genero ${LAMBDA_JAR}" >&2; exit 1; }
}

# desplegar_lambda <nombre> <handler> <memoria MB> <timeout s> <environment JSON {"Variables":{...}}>
# Crea la funcion (rol LabRole) o actualiza su codigo y configuracion. Imprime su ARN.
desplegar_lambda() {
  local nombre="$1" handler="$2" memoria="$3" timeout="$4" entorno="$5" cuenta zip
  cuenta="$(aws sts get-caller-identity --query Account --output text)"
  zip="fileb://$(_winpath "${LAMBDA_JAR}")"
  if aws lambda get-function --function-name "${nombre}" >/dev/null 2>&1; then
    aws lambda update-function-code --function-name "${nombre}" --zip-file "${zip}" >/dev/null
    aws lambda wait function-updated --function-name "${nombre}"
    aws lambda update-function-configuration --function-name "${nombre}" --runtime "${LAMBDA_RUNTIME}" \
      --handler "${handler}" --memory-size "${memoria}" --timeout "${timeout}" \
      --environment "${entorno}" >/dev/null
    aws lambda wait function-updated --function-name "${nombre}"
    echo "= lambda ${nombre} (${handler})" >&2
  else
    aws lambda create-function --function-name "${nombre}" --runtime "${LAMBDA_RUNTIME}" \
      --architectures x86_64 --role "arn:aws:iam::${cuenta}:role/LabRole" --handler "${handler}" \
      --zip-file "${zip}" --memory-size "${memoria}" --timeout "${timeout}" \
      --environment "${entorno}" --tags Project=camisetas360 >/dev/null
    aws lambda wait function-active --function-name "${nombre}"
    echo "+ lambda ${nombre} (${handler})" >&2
  fi
  aws lambda get-function --function-name "${nombre}" --query 'Configuration.FunctionArn' --output text
}
