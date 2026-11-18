#!/usr/bin/env bash
# Sube en lote las fotos de las camisetas al catalogo (PUT /api/v1/catalog/products/{sku}/imagen).
# Las fotos se guardan en Aurora (columna BYTEA de catalog), igual que al subirlas desde /admin/productos.
#
# Cada archivo se llama como el SKU del producto, por ejemplo:
#   fotos/PL-ARS-LOC-2627.jpg   fotos/PL-ARS-VIS-2627.png   fotos/PL-ARS-ALT-2627.webp
# Formatos: JPG, PNG o WebP; maximo 2 MB por foto.
#
# Requiere un access token de Microsoft Entra de una cuenta con rol CATALOG_MANAGER o ADMIN
# (inicia sesion con Microsoft en la pagina y copia el header Authorization de cualquier
# llamada a /api/v1/ en DevTools -> Network, sin la palabra "Bearer").
#
#   TOKEN=eyJ... bash catalog/scripts/subir-fotos.sh fotos/
#   TOKEN=eyJ... API=http://localhost:4200 bash catalog/scripts/subir-fotos.sh fotos/   # contra local
#   bash catalog/scripts/subir-fotos.sh --faltantes      # lista los SKU que aun no tienen foto (sin token)
set -euo pipefail

API="${API:-https://bbsgv4efrd.execute-api.us-east-1.amazonaws.com}"
API="${API%/}"
MAX_BYTES=$((2 * 1024 * 1024))

productos() { curl -fsS "${API}/api/v1/catalog/products"; }

if [ "${1:-}" = "--faltantes" ]; then
  productos | python -c "
import sys, json
sin = [p for p in json.load(sys.stdin) if p.get('imageVersion') is None]
for p in sin:
    print(p['sku'], '|', p.get('team') or '-', '|', p.get('kitType') or '-', '|', p['name'])
print(f'{len(sin)} productos sin foto', file=sys.stderr)
"
  exit 0
fi

DIR="${1:?Uso: TOKEN=... $0 <carpeta-con-fotos>   (o $0 --faltantes)}"
[ -d "${DIR}" ] || { echo "No existe la carpeta ${DIR}" >&2; exit 1; }
: "${TOKEN:?Falta TOKEN (access token de Entra con rol CATALOG_MANAGER o ADMIN)}"
TOKEN="${TOKEN#Bearer }"

SKUS="$(productos | python -c "import sys, json; print('\n'.join(p['sku'] for p in json.load(sys.stdin)))")"

OK=0; FALLOS=0; OMITIDOS=0
shopt -s nullglob nocaseglob
for f in "${DIR%/}"/*.jpg "${DIR%/}"/*.jpeg "${DIR%/}"/*.png "${DIR%/}"/*.webp; do
  nombre="$(basename "${f}")"
  sku="${nombre%.*}"
  case "${nombre,,}" in
    *.jpg|*.jpeg) tipo=image/jpeg ;;
    *.png) tipo=image/png ;;
    *.webp) tipo=image/webp ;;
  esac

  if ! grep -qxF "${sku}" <<< "${SKUS}"; then
    echo "OMITIDO ${nombre}: no existe un producto con SKU ${sku}"; OMITIDOS=$((OMITIDOS + 1)); continue
  fi
  bytes="$(wc -c < "${f}" | tr -d ' ')"
  if [ "${bytes}" -gt "${MAX_BYTES}" ]; then
    echo "OMITIDO ${nombre}: pesa $((bytes / 1024)) KB (maximo 2048 KB)"; OMITIDOS=$((OMITIDOS + 1)); continue
  fi

  code="$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: ${tipo}" \
    --data-binary @"${f}" "${API}/api/v1/catalog/products/${sku}/imagen" || echo 000)"
  case "${code}" in
    2??) echo "OK      ${sku}"; OK=$((OK + 1)) ;;
    401) echo "ERROR   ${sku}: 401, el token expiro o no es de Entra. Copia uno nuevo."; exit 1 ;;
    403) echo "ERROR   ${sku}: 403, la cuenta no tiene rol CATALOG_MANAGER/ADMIN o falta el scope."; exit 1 ;;
    *) echo "ERROR   ${sku}: HTTP ${code}"; FALLOS=$((FALLOS + 1)) ;;
  esac
done

echo
echo "Subidas: ${OK}  Errores: ${FALLOS}  Omitidas: ${OMITIDOS}"
[ "${FALLOS}" -eq 0 ]
