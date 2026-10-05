# PostgreSQL académico en EKS

Los manifests preparan `orders` y su PostgreSQL privado. No despliegan el cluster EKS,
RabbitMQ, el proveedor JWT ni los demás servicios. El workflow existente continúa
desplegando a EC2; no se inventaron nombres de cluster, cuentas AWS ni repositorios ECR.

## Requisitos

- EKS con nodos EC2 y Amazon EBS CSI add-on autorizado mediante IAM/Pod Identity.
- CNI que aplique NetworkPolicy; habilitarlo explícitamente en Amazon VPC CNI.
- Acceso de los nodos a la imagen ECR elegida y a las imágenes PostgreSQL.
- RabbitMQ real accesible desde el namespace y configuración JWT real.

`storage-class.yaml` usa el driver estándar `ebs.csi.aws.com`, gp3 cifrado,
`WaitForFirstConsumer` y política `Retain`. EKS Auto Mode requiere el provisioner
`ebs.csi.eks.amazonaws.com`; no son intercambiables para volúmenes ya creados.
[Documentación EBS CSI](https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html).

## Secrets externos

Crear fuera de Git dos archivos locales de entorno, uno por Secret. Pueden llamarse
`.env.postgres-staging` y `.env.orders-staging`; `.env.*` está ignorado. No publicar
su contenido ni usar `--dry-run -o yaml` para guardar credenciales en el repositorio.

El primero contiene `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.
El segundo contiene `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`,
`RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`, `ENTRA_ISSUER_URI`, `ENTRA_AUDIENCE`.

```powershell
kubectl apply -f deploy/eks/staging/namespace.yaml
kubectl -n camisetas360-staging create secret generic postgres-credentials --from-env-file=.env.postgres-staging
kubectl -n camisetas360-staging create secret generic orders-runtime --from-env-file=.env.orders-staging
kubectl apply -f deploy/eks/storage-class.yaml
```

Estos comandos crean Secrets `Opaque`; los manifests solo contienen `secretKeyRef`.
Para rotar valores, usar el gestor externo elegido y reiniciar los consumidores.
Cambiar `POSTGRES_PASSWORD` en el Secret no cambia la contraseña de un cluster ya
inicializado: rotar también el rol PostgreSQL mediante un canal administrativo seguro.
Restringir RBAC sobre Secrets y configurar su protección conforme al cluster.

Para producción crear archivos y Secrets distintos en `camisetas360-production`.
Cada namespace genera su propio StatefulSet/PVC y datos; nunca reutilizar el PVC de staging.

## Imagen y despliegue

La referencia `camisetas360-orders:verified` es un marcador, no una imagen publicada.
En una copia de despliegue del overlay, añadir la imagen ECR verificada e inmutable:

```yaml
images:
  - name: camisetas360-orders
    newName: <URI-ECR-real>/camisetas360-orders
    newTag: <tag-inmutable-verificado>
```

Los valores entre ángulos deben sustituirse antes de desplegar. Luego:

```powershell
kubectl kustomize deploy/eks/staging
kubectl apply -k deploy/eks/staging
kubectl -n camisetas360-staging rollout status statefulset/postgres --timeout=300s
kubectl -n camisetas360-staging rollout status deployment/orders --timeout=300s
kubectl -n camisetas360-staging get pvc
kubectl -n camisetas360-staging get service postgres
kubectl -n camisetas360-staging logs deployment/orders
```

`orders` recibe `jdbc:postgresql://postgres:5432/$(POSTGRES_DB)` y credenciales por
Secret. Flyway aplica V1–V3 antes de que Hibernate valide; una migración o validación
fallida impide el arranque. El Service PostgreSQL es ClusterIP: no hay LoadBalancer,
NodePort, Ingress ni hostPort. NetworkPolicy admite 5432 solo desde pods `app: orders`
del mismo namespace, si el CNI aplica políticas.

El StatefulSet crea `data-postgres-0` mediante `volumeClaimTemplates`; el PVC conserva
datos ante recreaciones del pod. Una instancia no proporciona alta disponibilidad.
El volumen EBS está ligado a una zona; planificar recuperación y backups restaurables.
No eliminar el PVC para resolver un error de arranque. La retención no sustituye backups.
[StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/).

Los probes de `orders` consultan Actuator real. Readiness incluye conexión DB;
liveness solo verifica el estado de la aplicación, para evitar reinicios por una caída DB.
La API de órdenes conserva JWT, rol CUSTOMER y scope Orders.Read. Los probes exponen
solo UP/DOWN, sin datos internos. PostgreSQL usa `pg_isready`, con startupProbe para
permitir su inicialización y recuperación.

## Límite académico y operación

Este despliegue usa el usuario bootstrap de PostgreSQL también para Flyway/JPA por
simplicidad académica. Para operación comercial separar roles de migración y aplicación,
revisar TLS, mínimo privilegio, backups/PITR, alertas y recuperación probada. Evaluar
Amazon RDS/Aurora PostgreSQL como alternativa administrada; esta propuesta mantiene
PostgreSQL dentro de EKS por la restricción académica.

Observar logs de Flyway, errores JDBC/Hikari y consumidores RabbitMQ, latencia HTTP,
readiness, reinicios, capacidad del PVC y backups. Integrar las métricas y alertas con
la plataforma elegida; no se afirma que exista aún una instalación Prometheus/CloudWatch.
