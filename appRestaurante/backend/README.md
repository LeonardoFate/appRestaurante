# Backend — Plataforma de restaurantes

Java 21 + Quarkus 3.40 (LTS). Monolito modular multi-tenant sobre PostgreSQL con RLS.
Decisiones y contexto: [ADR-014](../../01%20Decisiones/ADR-014%20Backend%20en%20Java%20con%20Quarkus.md) y `../../arquitectura_restaurantes.md`. El esquema SQL y sus pruebas están en [`../database/`](../database/).

## API disponible

| Método y ruta | Roles | Qué hace |
|---|---|---|
| `POST /api/v1/pedidos` | owner, admin, cajero, mesero | Crea un pedido. El cliente envía `id` (UUIDv7), `canal` (`salon` o `para_llevar`), `mesaId` y `items[productoId, cantidad, nota]`. **No envía precios ni totales**: salen del menú. 201 si lo crea, 200 si es una repetición idempotente |
| `GET /api/v1/pedidos/{id}` | ídem | Consulta un pedido del restaurante del token (404 si no existe o es de otro) |
| `POST /api/v1/pedidos/{id}/confirmar` | owner, admin, cajero, mesero | Manda el pedido a cocina (`PedidoConfirmado`). Idempotente. Cocina recibe los ítems con ≤ 1 s de retraso |
| `POST /api/v1/pedidos/{id}/cancelar` `{motivo}` | owner, admin, cajero | Cancela con motivo. Solo antes de entregar y **sin cobros** (la devolución aún no existe) |
| `GET /api/v1/cocina/estaciones`, `GET /api/v1/cocina/items?estacion=` | cocina, admin, owner, mesero, cajero | La pantalla de cocina (KDS): ítems enviados, preparando o listos de la **sucursal del token**, del más antiguo al más reciente |
| `POST /api/v1/cocina/items/{id}/preparando` y `/listo` | cocina, admin, owner | Cambia el estado del ítem; el pedido avanza solo. Idempotente |
| `POST /api/v1/cocina/items/{id}/servido` | mesero, cajero, cocina, admin, owner | El mesero sirve el ítem. Con todos servidos el pedido queda `entregado` y, si está pagado, `cerrado` |
| `POST /api/v1/cocina/items/{id}/anular` `{motivo}` | owner, admin, cajero | Anula un ítem: recalcula los totales; sin cobros |
| `POST /api/v1/pagos` | ídem | Registra un cobro (`id`, `pedidoId`, `metodo`, `monto`). Idempotente por `id`. Al completar el total escribe `PedidoPagado` en el outbox |
| `POST /api/v1/auth/login` | público | Correo y contraseña → JWT de acceso (15 min) + refresh token. `tenantId` y `sucursalId` solo si el usuario tiene varios. Tras 5 fallos la cuenta se bloquea 15 min; los errores no revelan si el usuario existe |
| `POST /api/v1/auth/refresh` | público | Cambia un refresh token (de **un solo uso**) por tokens nuevos. Reutilizar uno ya usado cierra toda la sesión |
| `POST /api/v1/auth/logout` | público | Cierra la sesión (idempotente) |
| `GET /health/live`, `/health/ready` | público | Salud |

Errores: `{ "codigo": "...", "mensaje": "..." }` con 400 (formato), 401/403 (identidad y roles), 404, 409 (conflicto de estado) o 422 (regla de negocio).

## Identidad

Todo endpoint de negocio exige un JWT. El restaurante, la sucursal y el usuario salen **solo** del token verificado:
`sub` (usuario), `tenant_id`, `sucursal_id`, `groups` (roles) y emisor `restaurantes`. Los emite `POST /api/v1/auth/login`. Las pruebas firman con una clave de prueba (`src/test/resources`, nunca para uso real). En producción la clave pública llega por `JWT_PUBLIC_KEY_LOCATION`, la privada de firma por `JWT_PRIVATE_KEY_LOCATION` y, si falta alguna, la aplicación no arranca. Aún falta probar el arranque en perfil `prod` con claves reales.

## Estructura

```
src/main/java/com/restaurantes/
  shared/tenancy/   TenantTransaction (unidad de trabajo: unico acceso a la BD, fija app.tenant_id por transaccion)
  shared/security/  CallerContext (identidad del JWT)
  shared/events/    Outbox (eventos en la misma transaccion)
  shared/errors/    ReglaDeNegocioException (Java puro) · shared/web/ traduccion a HTTP
  shared/ids/       UuidV7
  shared/jobs/      ColaDeJobs, JobWorker, ProcesadorDeJob, TareasPeriodicas
  menu/ pedidos/ pagos/ auth/ facturacion/ plataforma/ cocina/ salon/   con codigo; el resto de modulos solo tiene su package-info
  auth/ menu/ salon/ pedidos/ cocina/ delivery/ pagos/ facturacion/ sync/ reportes/ plataforma/
                    un paquete por modulo: api (publico) · internal (privado) · domain (Java puro)
```

Reglas verificadas por ArchUnit en cada build: lo `internal` de un modulo es privado, `shared` no depende de los
modulos, y `domain` no usa Quarkus, Jakarta ni JDBC.

## Probar

Las pruebas usan una base real con el esquema v2 (no hay mocks del aislamiento).

```bash
docker compose up -d                 # PostgreSQL 16 + esquema + usuario api_login
mvn test                             # necesita Java 21 y Maven
# o sin instalar nada, con Docker:
docker run --rm -v "$PWD":/work -w /work -v restaurantes-m2:/root/.m2 \
  --network restaurantes-dev -e TEST_DB_URL=jdbc:postgresql://postgres:5432/restaurantes \
  maven:3.9-eclipse-temurin-21 mvn -B test
```

## Eventos, cola de trabajos y facturación

- **Outbox** (`shared.events.Outbox`): un caso de uso escribe el evento en la misma transacción del cambio. Ejemplo: al completar el cobro, `PedidoPagado`.
- **Relay** (`OutboxRelay`, cada 1 s): entrega cada evento a los `ManejadorDeEvento` suscritos. Cada manejador corre en su transacción del tenant, con deduplicación por consumidor en esa misma transacción; si falla, se reintenta con espera creciente (5 s hasta 1 h).
- **Cola de trabajos** (`ColaDeJobs`, `JobWorker`, cada 2 s): tabla `jobs` en PostgreSQL con lease, reintentos y cola de fallidos. Un procesador implementa `ProcesadorDeJob`.
- **Facturación** (`facturacion`): al llegar `PedidoPagado` crea el comprobante en estado `generado` (secuencial, clave de acceso, detalles) y encola el trabajo `sri.emitir`. **El procesador de `sri.emitir` aún no existe**: el trabajo queda `pendiente` hasta el spike del SRI.
- El relay y el worker usan un **segundo datasource (`scheduler`)** con otro login (`sched_login`, **con `BYPASSRLS` propio**, rol `app_scheduler`) y privilegios mínimos sobre `outbox` y `jobs`. En producción: `SCHEDULER_DB_URL`, `SCHEDULER_DB_USER`, `SCHEDULER_DB_PASSWORD`.
- En las pruebas el planificador está apagado y cada prueba invoca el relay y el worker a mano (determinista); `SchedulerActivoTest` lo enciende para comprobar el funcionamiento periódico.

## Pedido y cocina

El estado del pedido sigue una **tabla de transiciones por canal** (`pedidos.domain.MaquinaDeEstados`, probada par por par): `creado → confirmado → en_preparacion → listo → entregado → cerrado`. **Se deriva del estado de sus ítems** (la cocina marca ítems; el pedido avanza solo, de a un paso, sin retroceder). Solo `pedidos` cambia el estado del pedido; `cocina` cambia el de cada ítem y lo notifica. Más detalle en `../../01 Decisiones/ADR-019 Máquina de estados del pedido y cocina.md`.

## Pruebas (175)

Contra la base real, sin mocks de la base ni de la identidad: aislamiento entre tenants, flujo HTTP completo con JWT firmados, concurrencia (perfil `PoolGrande`, 16 conexiones), dominio puro y reglas de arquitectura. El perfil normal usa **una** conexion a proposito, para que cualquier fuga de contexto entre usos se vea al instante.

## Reglas que no se negocian

1. Todo acceso a la base pasa por `TenantTransaction.withTenant(...)` en el borde del caso de uso; los repositorios usan `tx.connection()`. Nunca `SET` de sesion (fuga entre tenants con PgBouncer; ver F-001 en el vault).
2. Entre modulos solo por `api`; solo `pedidos` cambia el estado de un pedido.
3. Si una decision depende de lo leido (por ejemplo cobrar), se bloquea la fila antes de leer (`buscarBloqueando`); ver F-009 en el vault.
2. Los IDs nuevos salen de `UuidV7.next()`; la base rechaza otras versiones.
3. Consultas sobre tablas particionadas siempre con rango de fechas (F-002).
4. El rol de la aplicacion no es dueno de las tablas ni tiene `BYPASSRLS`.
