# Base de datos

| Archivo | Contenido |
|---|---|
| `schema_restaurantes_v2.sql` | Esquema PostgreSQL 15+: 51 tablas, RLS forzado, roles, funciones (`app.*`), triggers y particiones |
| `tests_schema_v2.sql` | 101 comprobaciones: aislamiento de tenants, integridad, secuenciales, sync idempotente, permisos, particiones, bloqueo de login y cola de trabajos |

El modelo y las reglas de diseño están documentados en `../../modelo_datos_restaurantes.md`; los resultados de las pruebas de concurrencia y volumen, en `../../pruebas_resultados.md`.

## Aplicar el esquema

Con un rol de **migración** (superusuario o dueño con `BYPASSRLS`; cambia el dueño de funciones y crea roles):

```bash
psql -v ON_ERROR_STOP=1 -f schema_restaurantes_v2.sql <base>
```

El rol de la aplicación (`app_backend`, y los logins que pertenezcan a él) **no es dueño de las tablas** ni tiene `BYPASSRLS`. Los logins de conexión los crea Operaciones; en desarrollo, `../backend/dev/02-roles-dev.sql`.

**`BYPASSRLS` no se hereda por membresía.** El login que usa el relay del outbox y la cola de trabajos (`sched_login`, miembro de `app_scheduler`) debe crearse con `BYPASSRLS` propio:

```sql
CREATE ROLE sched_login LOGIN PASSWORD '...' BYPASSRLS IN ROLE app_scheduler;
```

Si faltara, RLS se le aplicaría y fallaría con `unrecognized configuration parameter "app.tenant_id"`.

## Ejecutar las pruebas del esquema

**Solo sobre una base recién creada** con el esquema aplicado (por ejemplo la de `../backend/docker-compose.yml`, tras `docker compose down -v && docker compose up -d`):

```bash
psql -U postgres -d restaurantes -f tests_schema_v2.sql
```

Cada línea imprime `PASS` o `FAIL`. **No son repetibles**: usan RUC, correos y conteos fijos. Sobre una base con datos (por ejemplo, después de las pruebas Java) varias fallan por datos duplicados, no por defectos del esquema (comprobado: 101 PASS y 0 FAIL sobre base limpia; 47 PASS y 23 FAIL sobre una base sucia). Nunca contra datos reales.

## Reglas de uso

1. Toda conexión de la aplicación fija el tenant con `SET LOCAL` (nunca `SET` de sesión).
2. Consultas sobre tablas particionadas siempre con rango de fechas.
3. Una importación con `numero` de pedido ya asignado debe sembrar `contadores_pedido`.
4. Los IDs del cliente son UUIDv7.
