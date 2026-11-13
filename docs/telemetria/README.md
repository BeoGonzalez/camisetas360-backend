# Telemetría en tiempo real (DynamoDB Streams + API Gateway WebSocket)

Implementación de la guía "Telemetría a tiempo real con frontend" adaptada a Camisetas360: en vez de
camiones, el dashboard recibe en vivo el estado de cada producto (vistas y unidades agregadas al carrito).

```
catalog / carrito --Kafka--> analytics --UpdateItem--> DynamoDB camisetas360-analytics-productos
                                                              │ DynamoDB Streams (NEW_IMAGE)
                                                              ▼
                                                   Lambda "Stream"  (TelemetriaStreamHandler)
                                                              │ scan ConexionesWebSocket
                                                              ▼ postToConnection
Frontend  <── wss:// API Gateway WebSocket <── Lambda "Mensajero" (MensajeroHandler)
            ($connect valida el token Entra,     guarda/borra conexiones en ConexionesWebSocket)
```

## Qué ya hace el backend

| Pieza | Dónde |
|---|---|
| Activa **DynamoDB Streams (NEW_IMAGE)** en `camisetas360-analytics-productos` al arrancar, y también si la tabla ya existía sin stream | `analytics` → `DynamoDbTableInitializer` (`DYNAMODB_PRODUCTOS_STREAM`, default `true`) |
| Lambda **Mensajero**: `$connect` (valida token Entra, guarda la conexión con TTL), `$disconnect` (la borra), `$default` (se ignora) | `telemetria` → `com.camisetas360.telemetria.MensajeroHandler::handleRequest` |
| Lambda **Stream**: lee INSERT/MODIFY del stream, deduplica por SKU y hace broadcast; borra las conexiones que responden 410 Gone | `telemetria` → `com.camisetas360.telemetria.TelemetriaStreamHandler::handleRequest` |
| Build y pruebas en CI; el jar queda como artefacto `lambda-telemetria` | `.github/workflows/ci.yml` (job `lambdas`) |

Diferencias con el código de la guía (corregidas a propósito):

- `$disconnect` **borra** la conexión (en la guía insertaba de nuevo) y además se borran las conexiones cerradas (410).
- `$connect` exige un access token de Entra con rol `ADMIN` o `CATALOG_MANAGER` y scope `Catalog.Read`, las mismas reglas
  que el dashboard STOMP de analytics. El `clientId` por query string de la guía se podía inventar.
- La URL del API sale de la variable `WEBSOCKET_API_ENDPOINT`, no del código. Si pegas la `wss://`, se convierte sola a `https://`.
- Scan paginado, mensajes de 50 productos como máximo (el límite de API Gateway es 128 KB) y un solo jar sin Spring (arranque en frío corto).
- Se transmite la tabla de **productos**, que no tiene datos personales. Las tablas `eventos` y `UserInteractions` contienen el
  `usuarioId` y por eso no se envían a todos.

## Configuración en AWS Academy (us-east-1, rol `LabRole`)

### 0. Compilar el jar

```powershell
cd telemetria
.\mvnw.cmd clean verify      # genera target\telemetria-lambda-0.0.1-SNAPSHOT.jar (~11 MB)
```

También puedes descargarlo del artefacto `lambda-telemetria` de la ejecución de CI en GitHub Actions.

### 1. Desplegar analytics con el stream

Basta con desplegar la nueva versión de `analytics`: al arrancar crea la tabla con stream o se lo activa a la existente.
En los logs aparece `DynamoDB Streams (NEW_IMAGE) activado en camisetas360-analytics-productos`.
Para comprobarlo, entra en DynamoDB → Tablas → `camisetas360-analytics-productos` → *Exportaciones y streams*: debe decir *Activado: Nueva imagen*.
Si LabRole no tuviera permiso `dynamodb:UpdateTable`, actívalo ahí mismo a mano con tipo **Nueva imagen**.

### 2. Tabla de conexiones

**La crea el pipeline**: el job `provision` de `deploy-backend.yml` (`deploy/aws/provision.sh`, paso 5) crea
`ConexionesWebSocket` con su TTL si no existe. Solo hace falta crearla a mano si todavía no corriste el pipeline:

DynamoDB → Crear tabla:

- Nombre: `ConexionesWebSocket`
- Clave de partición: `connectionId` (String)
- Configuración: la predeterminada (on-demand)

Después, en *Configuración adicional → Tiempo de vida (TTL)*, activa el TTL con el atributo `expiresAt`.

Alternativa con CloudShell:

```bash
aws dynamodb create-table --table-name ConexionesWebSocket \
  --attribute-definitions AttributeName=connectionId,AttributeType=S \
  --key-schema AttributeName=connectionId,KeyType=HASH --billing-mode PAY_PER_REQUEST
aws dynamodb wait table-exists --table-name ConexionesWebSocket
aws dynamodb update-time-to-live --table-name ConexionesWebSocket \
  --time-to-live-specification Enabled=true,AttributeName=expiresAt
```

### 3. Lambda Mensajero

Lambda → Crear función → *Crear desde cero*:

- Nombre: `mensajero`, tiempo de ejecución **Java 21**, arquitectura x86_64
- Rol de ejecución: *Usar un rol existente* → `LabRole`

Luego:

- **Código**: *Cargar desde → archivo .zip o .jar*, sube `telemetria-lambda-0.0.1-SNAPSHOT.jar`.
- **Configuración del tiempo de ejecución → Editar → Controlador**: `com.camisetas360.telemetria.MensajeroHandler::handleRequest`
- **Configuración → Configuración general**: memoria 512 MB, tiempo de espera 15 s (el primer `$connect` descarga las claves de Entra).
- **Configuración → Variables de entorno**:

| Variable | Valor |
|---|---|
| `ENTRA_ISSUER_URI` | el mismo que en los microservicios (`https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_AUDIENCE` | el mismo que en los microservicios (client ID de la API) |
| `TABLA_CONEXIONES` | `ConexionesWebSocket` (opcional, es el default) |
| `WS_AUTH_ENABLED` | opcional; `false` solo para probar sin token con wscat |

### 4. API Gateway WebSocket

API Gateway → Crear API → **API de WebSocket**:

- Nombre: `camisetas360-telemetria`, expresión de selección de rutas: `request.body.action`
- Rutas predefinidas: **$connect**, **$disconnect** y **$default**
- Integración de las tres: **Lambda** → `mensajero`
- Etapa: `prod`, con implementación automática

Al crear las integraciones desde el asistente, la consola agrega a `mensajero` el permiso para que API Gateway la invoque.
En *Etapas → prod* aparecen dos URL:

- `wss://<api-id>.execute-api.us-east-1.amazonaws.com/prod`, la que usa el frontend
- `https://<api-id>.execute-api.us-east-1.amazonaws.com/prod`, la *URL de conexión* que usa la Lambda Stream

### 5. Lambda Stream

Crea otra función igual que la anterior: nombre `stream`, Java 21, `LabRole`, el **mismo jar**.

- **Controlador**: `com.camisetas360.telemetria.TelemetriaStreamHandler::handleRequest`
- **Memoria y tiempo**: 512 MB y 30 s
- **Variables de entorno**:

| Variable | Valor |
|---|---|
| `WEBSOCKET_API_ENDPOINT` | `https://<api-id>.execute-api.us-east-1.amazonaws.com/prod` |
| `TABLA_CONEXIONES` | `ConexionesWebSocket` (opcional) |

- **Agregar desencadenador → DynamoDB**:
  - Tabla: `camisetas360-analytics-productos`
  - Tamaño de lote: 25
  - Posición inicial: **Último** (*Latest*)

`LabRole` debe permitir leer el stream (`dynamodb:GetRecords`, `GetShardIterator`, `DescribeStream`, `ListStreams`) y
`execute-api:ManageConnections`. En el Learner Lab normalmente ya los tiene. Si falla, aparece en CloudWatch como `AccessDenied`.

## Contrato del WebSocket

**Conexión:** `wss://<api-id>.execute-api.us-east-1.amazonaws.com/prod?token=<access_token>`

Un navegador no puede enviar cabeceras en un WebSocket, por eso el token va en la query. Es el mismo access token que el
frontend ya pide para el dashboard de analytics (scope `.../Catalog.Read`), y el usuario necesita el rol `ADMIN` o
`CATALOG_MANAGER`. Si el token falta o no es válido, el handshake se rechaza con 401. Las conexiones duran como máximo
2 horas (límite de API Gateway), así que al reconectar hay que pedir un token nuevo.

**Mensaje del servidor** (solo lectura: lo que envíe el cliente se ignora):

```json
{
  "tipo": "productos.actualizados",
  "generadoEn": "2026-11-12T10:00:00.123Z",
  "productos": [
    { "sku": "CHI-LOC", "nombre": "Chile Local", "categoria": "Selecciones", "precio": 27990.0,
      "vistas": 42, "agregados": 7, "actualizadoEn": "2026-11-12T10:00:00.050Z" }
  ]
}
```

Cada producto trae su estado acumulado (no un delta): el frontend reemplaza por `sku`.

Cambios respecto al `DashboardPage.jsx` de la guía:

```js
// token de Entra (MSAL) en vez de clientId
const { accessToken } = await instance.acquireTokenSilent({ scopes: [CATALOG_READ_SCOPE], account: accounts[0] });
const ws = new WebSocket(`${wsEndpoint}?token=${encodeURIComponent(accessToken)}`);

ws.onmessage = (event) => {
  const mensaje = JSON.parse(event.data);
  if (mensaje.tipo !== 'productos.actualizados') return;
  setProductos(prev => {
    const nuevo = { ...prev };
    mensaje.productos.forEach(p => { nuevo[p.sku] = p; });
    return nuevo;
  });
};
```

## Prueba de punta a punta

1. Conecta con wscat: `npx wscat -c "wss://<api-id>.execute-api.us-east-1.amazonaws.com/prod?token=<token>"`.
   Para probar sin Entra, define antes `WS_AUTH_ENABLED=false` en `mensajero`.
2. En `ConexionesWebSocket` aparece el `connectionId`.
3. Genera actividad: ver un producto en catalog o agregarlo al carrito. Ese evento llega a analytics por Kafka y analytics hace `UpdateItem` en productos.
4. Llega el mensaje `productos.actualizados`. En CloudWatch, la Lambda `stream` registra `Telemetria: N productos -> M conexiones`.
5. Al cerrar wscat, `$disconnect` borra la fila.

Errores típicos:

| Síntoma | Causa |
|---|---|
| `401` al conectar | Token vencido, de otro tenant o audience, o sin rol `ADMIN`/`CATALOG_MANAGER` o sin scope `Catalog.Read`. El motivo aparece en los logs de `mensajero` (el token nunca se registra). |
| `stream` registra `Falta la variable de entorno WEBSOCKET_API_ENDPOINT` | Falta la variable en la Lambda `stream`. |
| `stream` no se ejecuta | La tabla de productos no tiene stream (paso 1) o falta el desencadenador (paso 5). |
| `ForbiddenException` en `postToConnection` | El endpoint no corresponde a la API o etapa correcta, o falta `execute-api:ManageConnections`. |
| `ClassNotFoundException` | El controlador está mal escrito: revisa el nombre completo del paquete. |
