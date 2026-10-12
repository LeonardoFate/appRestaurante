# Base de datos

| Archivo | Contenido |
|---|---|
| `schema_restaurantes_v2.sql` | Esquema PostgreSQL 15+: 50 tablas, RLS forzado, roles, funciones (`app.*`), triggers y particiones |
| `tests_schema_v2.sql` | 86 comprobaciones: aislamiento de tenants, integridad, secuenciales, sync idempotente, permisos, particiones y bloqueo de login |

El modelo y las reglas de diseño están documentados en `../../modelo_datos_restaurantes.md`; los resultados de las pruebas de concurrencia y volumen, en `../../pruebas_resultados.md`.

## Aplicar el esquema

Con un rol de **migración** (superusuario o dueño con `BYPASSRLS`; cambia el dueño de funciones y crea roles):

```bash
psql -v ON_ERROR_STOP=1 -f schema_restaurantes_v2.sql <base>
```

El rol de la aplicación (`app_backend`, y los logins que pertenezcan a él) **no es dueño de las tablas** ni tiene `BYPASSRLS`. Los logins de conexión los crea Operaciones; en desarrollo, `../backend/dev/02-roles-dev.sql`.

## Ejecutar las pruebas del esquema

Sobre una base con el esquema ya aplicado (por ejemplo la de `../backend/docker-compose.yml`):

```bash
psql -U postgres -d restaurantes -f tests_schema_v2.sql
```

Cada línea imprime `PASS` o `FAIL`. Las pruebas crean sus propios tenants; no deben ejecutarse contra datos reales.

## Reglas de uso

1. Toda conexión de la aplicación fija el tenant con `SET LOCAL` (nunca `SET` de sesión).
2. Consultas sobre tablas particionadas siempre con rango de fechas.
3. Una importación con `numero` de pedido ya asignado debe sembrar `contadores_pedido`.
4. Los IDs del cliente son UUIDv7.
