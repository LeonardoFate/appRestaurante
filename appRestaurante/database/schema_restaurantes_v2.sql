-- =============================================================================
-- schema_restaurantes_v2.sql
-- Plataforma de restaurantes (POS + Delivery) - PostgreSQL 15+
-- Especificacion: modelo_datos_restaurantes.md (v2.0) y arquitectura_restaurantes.md (v2.0)
--
-- COMO EJECUTARLO
--   * Con un rol de MIGRACION (dueno de las tablas, con BYPASSRLS o superusuario).
--     El rol de la aplicacion (app_backend) NO es dueno ni tiene BYPASSRLS.
--   * psql -v ON_ERROR_STOP=1 -f appRestaurante/database/schema_restaurantes_v2.sql <base>
--   * Los roles se crean sin LOGIN ni clave. Operaciones debe crear los usuarios
--     de conexion y asignarles membresia, por ejemplo:
--       CREATE ROLE api_login LOGIN PASSWORD '...' IN ROLE app_backend;
--       CREATE ROLE sched_login LOGIN PASSWORD '...' BYPASSRLS IN ROLE app_scheduler;
--     OJO: BYPASSRLS (y LOGIN) son atributos del rol y NO se heredan por membresia. El login del
--     scheduler y el del reporteo necesitan BYPASSRLS propio; con solo "IN ROLE app_scheduler"
--     RLS se les seguiria aplicando.
--   * PgBouncer en modo "transaction" y SIEMPRE SET LOCAL app.tenant_id (nunca SET).
--
-- ESTADO: validado en PostgreSQL 15.19 y 16 con tests_schema_v2.sql (108 comprobaciones),
-- con PgBouncer 1.26 en modo transaction, con pruebas de concurrencia (pgbench) y con
-- 500k pedidos. Resultados y reglas de uso en pruebas_resultados.md.
--
-- VERIFICAR CON LA NORMATIVA VIGENTE DEL SRI: codigos de IVA, formas de pago,
-- tipos de emision/contingencia y formato exacto de la clave de acceso.
-- =============================================================================

begin;

-- -----------------------------------------------------------------------------
-- 0. Esquema auxiliar, roles y funciones base
-- -----------------------------------------------------------------------------
create schema if not exists app;

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'app_backend') then
    create role app_backend nologin nobypassrls;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'app_auth') then
    -- Dueno de las funciones de autenticacion (login previo al contexto de tenant).
    create role app_auth nologin bypassrls;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'app_scheduler') then
    -- Relay de outbox y tareas programadas que recorren varios tenants.
    create role app_scheduler nologin bypassrls;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'app_reportes') then
    -- Metricas de plataforma: solo lectura, auditable.
    create role app_reportes nologin bypassrls;
  end if;
end $$;

-- UUIDv7 (marca de tiempo en ms + aleatorio). PostgreSQL 15 no lo trae nativo.
create or replace function public.uuid_v7() returns uuid
language sql volatile as $$
  select encode(
    set_bit(
      set_bit(
        overlay(uuid_send(gen_random_uuid())
                placing substring(int8send(floor(extract(epoch from clock_timestamp()) * 1000)::bigint) from 3)
                from 1 for 6),
        52, 1),
      53, 1),
    'hex')::uuid
$$;

-- Fecha (UTC) codificada en un UUIDv7. Determinista: un reintento da la misma fecha.
create or replace function app.fecha_de_uuid_v7(p_id uuid) returns date
language sql immutable as $$
  select ((to_timestamp(
            (('x' || substr(replace(p_id::text, '-', ''), 1, 12))::bit(48)::bigint) / 1000.0
          )) at time zone 'UTC')::date
$$;

-- Tenant de la transaccion actual. Si no esta definido, FALLA (no devuelve todo).
-- Tras terminar una transaccion con SET LOCAL el GUC queda en '' y el cast falla: es lo deseado.
create or replace function app.current_tenant() returns uuid
language sql stable as $$
  select current_setting('app.tenant_id')::uuid
$$;

-- Bloquea UPDATE/DELETE en tablas de solo insercion.
create or replace function app.bloquear_modificacion() returns trigger
language plpgsql as $$
begin
  raise exception 'La tabla % es de solo insercion', tg_table_name
    using errcode = '42501';
end $$;

create or replace function app.set_actualizado_en() returns trigger
language plpgsql as $$
begin
  new.actualizado_en := now();
  return new;
end $$;

-- Concurrencia optimista: la aplicacion actualiza con WHERE version = :esperada
-- y esta funcion incrementa la version.
create or replace function app.incrementar_version() returns trigger
language plpgsql as $$
begin
  new.version := old.version + 1;
  return new;
end $$;

-- -----------------------------------------------------------------------------
-- 1. Planes, tenants y sucursales
-- -----------------------------------------------------------------------------
create table planes (
  id                     uuid primary key default uuid_v7(),
  codigo                 text not null unique,
  nombre                 text not null,
  max_sucursales         integer not null check (max_sucursales > 0),
  max_usuarios           integer not null check (max_usuarios > 0),
  max_dispositivos       integer not null check (max_dispositivos > 0),
  max_pedidos_mes        integer check (max_pedidos_mes > 0),          -- null = ilimitado
  permite_dominio_propio boolean not null default false,
  precio_mensual         numeric(12,2) not null default 0 check (precio_mensual >= 0),
  activo                 boolean not null default true,
  creado_en              timestamptz not null default now(),
  actualizado_en         timestamptz not null default now()
);

create table tenants (
  id                      uuid primary key default uuid_v7(),
  ruc                     char(13) not null unique check (ruc ~ '^[0-9]{13}$'),
  razon_social            text not null,
  nombre_comercial        text,
  slug                    text not null unique check (slug ~ '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'),
  plan_id                 uuid not null references planes (id),
  estado                  text not null default 'prueba'
                            check (estado in ('prueba','activo','suspendido','baja')),
  obligado_contabilidad   boolean not null default false,
  contribuyente_especial  text,
  regimen                 text,
  ambiente_sri            text not null default 'pruebas' check (ambiente_sri in ('pruebas','produccion')),
  ventana_offline_horas   integer not null default 72 check (ventana_offline_horas > 0),
  creado_en               timestamptz not null default now(),
  actualizado_en          timestamptz not null default now()
);

create table sucursales (
  id                   uuid primary key default uuid_v7(),
  tenant_id            uuid not null references tenants (id),
  nombre               text not null,
  cod_establecimiento  char(3) not null check (cod_establecimiento ~ '^[0-9]{3}$'),
  cod_punto_emision    char(3) not null check (cod_punto_emision ~ '^[0-9]{3}$'),
  direccion            text not null,
  lat                  numeric(9,6) check (lat between -90 and 90),
  lng                  numeric(9,6) check (lng between -180 and 180),
  zona_horaria         text not null default 'America/Guayaquil',
  acepta_delivery      boolean not null default false,
  acepta_para_llevar   boolean not null default true,
  horarios             jsonb not null default '{}'::jsonb,
  activa               boolean not null default true,
  creado_en            timestamptz not null default now(),
  actualizado_en       timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, cod_establecimiento, cod_punto_emision)
);

create table dominios_tenant (
  id             uuid primary key default uuid_v7(),
  tenant_id      uuid not null references tenants (id),
  dominio        text not null unique,
  verificado_en  timestamptz,
  creado_en      timestamptz not null default now()
);

-- -----------------------------------------------------------------------------
-- 2. Usuarios, roles y dispositivos
-- -----------------------------------------------------------------------------
-- usuarios NO lleva tenant_id: una persona puede trabajar en varios restaurantes.
create table usuarios (
  id              uuid primary key default uuid_v7(),
  email           text not null,
  nombre          text not null,
  telefono        text,
  password_hash   text,                       -- Argon2id; null si el login es externo
  mfa_secreto     text,                       -- TOTP cifrado a nivel de aplicacion
  idp_sub         text unique,                -- id en un IdP externo (Keycloak/OIDC)
  activo          boolean not null default true,
  -- Bloqueo por intentos fallidos de login (se gestiona con app.registrar_login_*).
  login_intentos          integer not null default 0 check (login_intentos >= 0),
  login_bloqueado_hasta   timestamptz,
  creado_en       timestamptz not null default now(),
  actualizado_en  timestamptz not null default now(),
  check (email = lower(email))
);
create unique index ux_usuarios_email on usuarios (email);

create table membresias (
  id              uuid primary key default uuid_v7(),
  tenant_id       uuid not null references tenants (id),
  usuario_id      uuid not null references usuarios (id),
  sucursal_id     uuid,                       -- null = todas las sucursales
  rol             text not null check (rol in ('owner','admin','cajero','mesero','cocina','repartidor')),
  pin_hash        text,                       -- Argon2/scrypt con sal; nunca el PIN
  pin_intentos    integer not null default 0 check (pin_intentos >= 0),
  bloqueado_hasta timestamptz,
  estado          text not null default 'activa' check (estado in ('activa','suspendida','revocada')),
  revocada_en     timestamptz,
  creado_en       timestamptz not null default now(),
  actualizado_en  timestamptz not null default now(),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id),
  check (estado <> 'revocada' or revocada_en is not null)
);
create unique index ux_membresias_unica
  on membresias (tenant_id, usuario_id, coalesce(sucursal_id, '00000000-0000-0000-0000-000000000000'::uuid), rol);
create index ix_membresias_usuario on membresias (usuario_id);

create table permisos_rol (
  tenant_id            uuid not null references tenants (id),
  rol                  text not null check (rol in ('owner','admin','cajero','mesero','cocina','repartidor')),
  permiso              text not null,               -- p. ej. 'pagos:anular', 'menu:editar'
  requiere_supervisor  boolean not null default false,
  primary key (tenant_id, rol, permiso)
);

create table dispositivos (
  id                uuid primary key default uuid_v7(),
  tenant_id         uuid not null references tenants (id),
  sucursal_id       uuid not null,
  tipo              text not null check (tipo in ('pos','kds','repartidor')),
  nombre            text not null,
  modelo            text,
  app_version       text,
  schema_version    integer,
  clave_publica     text,
  ultimo_sync_en    timestamptz,
  desfase_reloj_ms  bigint,
  pendientes_sync   integer not null default 0 check (pendientes_sync >= 0),
  estado            text not null default 'activo' check (estado in ('activo','revocado')),
  creado_en         timestamptz not null default now(),
  actualizado_en    timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);
create index ix_dispositivos_sucursal on dispositivos (tenant_id, sucursal_id);

create table codigos_enrolamiento (
  id           uuid primary key default uuid_v7(),
  tenant_id    uuid not null references tenants (id),
  sucursal_id  uuid not null,
  tipo         text not null check (tipo in ('pos','kds','repartidor')),
  codigo_hash  text not null unique,
  expira_en    timestamptz not null,
  usado_en     timestamptz,
  creado_por   uuid not null references usuarios (id),
  creado_en    timestamptz not null default now(),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);

-- Los refresh tokens deben llevar el tenant_id en su formato (p. ej. "<tenant>.<secreto>")
-- para poder fijar el contexto antes de consultar esta tabla.
create table refresh_tokens (
  id             uuid primary key default uuid_v7(),
  tenant_id      uuid not null references tenants (id),
  familia_id     uuid not null,
  usuario_id     uuid not null references usuarios (id),
  sucursal_id    uuid,                -- sucursal elegida al iniciar sesion; se conserva al renovar
  dispositivo_id uuid,
  token_hash     text not null unique,
  expira_en      timestamptz not null,
  usado_en       timestamptz,
  revocado_en    timestamptz,
  creado_en      timestamptz not null default now(),
  foreign key (tenant_id, dispositivo_id) references dispositivos (tenant_id, id),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);
create index ix_refresh_familia on refresh_tokens (tenant_id, familia_id);

-- -----------------------------------------------------------------------------
-- 3. Menu
-- -----------------------------------------------------------------------------
-- Catalogo de tarifas de IVA. tenant_id null = global. VERIFICAR codigos con el SRI.
create table tarifas_iva (
  id              uuid primary key default uuid_v7(),
  tenant_id       uuid references tenants (id),
  codigo_sri      text not null,
  nombre          text not null,
  porcentaje      numeric(5,2) not null check (porcentaje >= 0 and porcentaje <= 100),
  vigente_desde   date not null,
  vigente_hasta   date,
  check (vigente_hasta is null or vigente_hasta >= vigente_desde)
);
create unique index ux_tarifas_iva
  on tarifas_iva (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), codigo_sri, vigente_desde);

create table categorias (
  id              uuid primary key default uuid_v7(),
  tenant_id       uuid not null references tenants (id),
  nombre          text not null,
  orden           integer not null default 0,
  visible_en_app  boolean not null default true,
  activa          boolean not null default true,
  creado_en       timestamptz not null default now(),
  actualizado_en  timestamptz not null default now(),
  unique (tenant_id, id)
);

create table productos (
  id              uuid primary key default uuid_v7(),
  tenant_id       uuid not null references tenants (id),
  categoria_id    uuid not null,
  nombre          text not null,
  descripcion     text,
  precio_base     numeric(12,2) not null check (precio_base >= 0),
  tarifa_iva_id   uuid not null references tarifas_iva (id),
  imagen_url      text,
  activo          boolean not null default true,
  version_menu    bigint not null default 1,
  creado_en       timestamptz not null default now(),
  actualizado_en  timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, categoria_id) references categorias (tenant_id, id)
);
create index ix_productos_categoria on productos (tenant_id, categoria_id);
create index ix_productos_sync on productos (tenant_id, actualizado_en);

create table estaciones_cocina (
  id           uuid primary key default uuid_v7(),
  tenant_id    uuid not null references tenants (id),
  sucursal_id  uuid not null,
  nombre       text not null,
  activa       boolean not null default true,
  creado_en    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, sucursal_id, nombre),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);

create table productos_sucursal (
  tenant_id         uuid not null references tenants (id),
  producto_id       uuid not null,
  sucursal_id       uuid not null,
  precio            numeric(12,2) check (precio >= 0),          -- null = precio_base
  estacion_id       uuid,
  disponible        boolean not null default true,
  disponible_hasta  timestamptz,                                 -- reactivacion automatica
  actualizado_en    timestamptz not null default now(),
  primary key (producto_id, sucursal_id),
  foreign key (tenant_id, producto_id) references productos (tenant_id, id),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id),
  foreign key (tenant_id, estacion_id) references estaciones_cocina (tenant_id, id)
);

create table grupos_modificadores (
  id             uuid primary key default uuid_v7(),
  tenant_id      uuid not null references tenants (id),
  nombre         text not null,
  min_seleccion  integer not null default 0 check (min_seleccion >= 0),
  max_seleccion  integer not null default 1 check (max_seleccion >= 1),
  creado_en      timestamptz not null default now(),
  unique (tenant_id, id),
  check (min_seleccion <= max_seleccion)
);

create table modificadores (
  id            uuid primary key default uuid_v7(),
  tenant_id     uuid not null references tenants (id),
  grupo_id      uuid not null,
  nombre        text not null,
  precio_extra  numeric(12,2) not null default 0 check (precio_extra >= 0),
  disponible    boolean not null default true,
  orden         integer not null default 0,
  unique (tenant_id, id),
  foreign key (tenant_id, grupo_id) references grupos_modificadores (tenant_id, id)
);

create table productos_grupos_mod (
  tenant_id    uuid not null references tenants (id),
  producto_id  uuid not null,
  grupo_id     uuid not null,
  orden        integer not null default 0,
  primary key (producto_id, grupo_id),
  foreign key (tenant_id, producto_id) references productos (tenant_id, id),
  foreign key (tenant_id, grupo_id) references grupos_modificadores (tenant_id, id)
);

-- -----------------------------------------------------------------------------
-- 4. Salon
-- -----------------------------------------------------------------------------
create table areas (
  id           uuid primary key default uuid_v7(),
  tenant_id    uuid not null references tenants (id),
  sucursal_id  uuid not null,
  nombre       text not null,
  orden        integer not null default 0,
  unique (tenant_id, id),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);

create table mesas (
  id              uuid primary key default uuid_v7(),
  tenant_id       uuid not null references tenants (id),
  sucursal_id     uuid not null,
  area_id         uuid,
  codigo          text not null,
  capacidad       integer not null default 4 check (capacidad > 0),
  pos_x           integer,
  pos_y           integer,
  estado          text not null default 'libre'
                    check (estado in ('libre','ocupada','por_pagar','reservada','conflicto')),
  activa          boolean not null default true,
  version         integer not null default 1,
  creado_en       timestamptz not null default now(),
  actualizado_en  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, sucursal_id, codigo),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id),
  foreign key (tenant_id, area_id) references areas (tenant_id, id)
);

-- -----------------------------------------------------------------------------
-- 5. Clientes
-- -----------------------------------------------------------------------------
create table clientes (
  id                        uuid primary key default uuid_v7(),
  tenant_id                 uuid not null references tenants (id),
  nombre                    text not null,
  tipo_identificacion       text not null check (tipo_identificacion in ('04','05','06','07')),
  identificacion            text not null,
  telefono                  text,
  email                     text,
  consentimiento_marketing  boolean not null default false,
  consentimiento_en         timestamptz,
  anonimizado_en            timestamptz,
  creado_en                 timestamptz not null default now(),
  actualizado_en            timestamptz not null default now(),
  unique (tenant_id, id),
  check (not consentimiento_marketing or consentimiento_en is not null)
);
create unique index ux_clientes_identificacion
  on clientes (tenant_id, tipo_identificacion, identificacion)
  where tipo_identificacion <> '07';

create table direcciones_cliente (
  id           uuid primary key default uuid_v7(),
  tenant_id    uuid not null references tenants (id),
  cliente_id   uuid not null,
  etiqueta     text not null default 'Casa',
  direccion    text not null,
  referencia   text,
  lat          numeric(9,6) check (lat between -90 and 90),
  lng          numeric(9,6) check (lng between -180 and 180),
  creado_en    timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, cliente_id) references clientes (tenant_id, id)
);

-- -----------------------------------------------------------------------------
-- 6. Caja
-- -----------------------------------------------------------------------------
create table turnos_caja (
  id                     uuid primary key default uuid_v7(),
  tenant_id              uuid not null references tenants (id),
  sucursal_id            uuid not null,
  usuario_id             uuid not null references usuarios (id),
  dispositivo_id         uuid not null,
  monto_apertura         numeric(12,2) not null check (monto_apertura >= 0),
  monto_cierre_contado   numeric(12,2) check (monto_cierre_contado >= 0),
  monto_cierre_esperado  numeric(12,2),
  diferencia             numeric(12,2),
  motivo_diferencia      text,
  abierto_en             timestamptz not null default now(),
  cerrado_en             timestamptz,
  version                integer not null default 1,
  unique (tenant_id, id),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id),
  foreign key (tenant_id, dispositivo_id) references dispositivos (tenant_id, id),
  check (cerrado_en is null or monto_cierre_contado is not null),
  check (cerrado_en is null or cerrado_en >= abierto_en)
);
-- Un solo turno abierto por cajero y dispositivo.
create unique index ux_turno_abierto
  on turnos_caja (tenant_id, usuario_id, dispositivo_id) where cerrado_en is null;

create table movimientos_caja (
  id             uuid primary key default uuid_v7(),
  tenant_id      uuid not null references tenants (id),
  turno_caja_id  uuid not null,
  tipo           text not null check (tipo in ('ingreso','retiro','liquidacion_repartidor')),
  monto          numeric(12,2) not null check (monto > 0),
  motivo         text not null,
  usuario_id     uuid not null references usuarios (id),
  creado_en      timestamptz not null default now(),
  foreign key (tenant_id, turno_caja_id) references turnos_caja (tenant_id, id)
);
create index ix_movimientos_turno on movimientos_caja (tenant_id, turno_caja_id);

-- -----------------------------------------------------------------------------
-- 7. Pedidos
-- -----------------------------------------------------------------------------
-- Numero definitivo del pedido por sucursal y dia operativo (lo asigna el servidor).
create table contadores_pedido (
  tenant_id        uuid not null references tenants (id),
  sucursal_id      uuid not null,
  fecha_operativa  date not null,
  ultimo_valor     integer not null default 0 check (ultimo_valor >= 0),
  primary key (tenant_id, sucursal_id, fecha_operativa),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);

create table pedidos (
  id                          uuid primary key default uuid_v7(),   -- UUIDv7 generado en el cliente
  tenant_id                   uuid not null references tenants (id),
  sucursal_id                 uuid not null,
  numero                      integer check (numero > 0),             -- asignado por el servidor
  numero_local                text,                                   -- provisional del dispositivo
  canal                       text not null
                                check (canal in ('salon','para_llevar','delivery_propio','app_terceros')),
  estado                      text not null default 'creado'
                                check (estado in ('creado','pago_pendiente','confirmado','en_preparacion',
                                                  'listo','asignado','en_camino','entregado','cerrado',
                                                  'cancelado','devuelto_parcial','devuelto_total')),
  estado_pago                 text not null default 'sin_pago'
                                check (estado_pago in ('sin_pago','parcial','pagado',
                                                       'reembolsado_parcial','reembolsado')),
  momento_pago                text not null default 'al_final'
                                check (momento_pago in ('anticipado','al_final','contra_entrega')),
  mesa_id                     uuid,
  direccion_id                uuid,
  cliente_id                  uuid,
  turno_caja_id               uuid,
  dispositivo_id              uuid,
  usuario_id                  uuid references usuarios (id),
  pedido_origen_id            uuid,
  fecha_operativa             date not null,
  subtotal                    numeric(12,2) not null default 0 check (subtotal >= 0),
  descuento                   numeric(12,2) not null default 0 check (descuento >= 0),
  iva                         numeric(12,2) not null default 0 check (iva >= 0),
  propina                     numeric(12,2) not null default 0 check (propina >= 0),
  costo_envio                 numeric(12,2) not null default 0 check (costo_envio >= 0),
  total                       numeric(12,2) not null default 0 check (total >= 0),
  motivo_descuento            text,
  descuento_autorizado_por    uuid references usuarios (id),
  version                     integer not null default 1,
  creado_en_dispositivo       timestamptz,                            -- informativo (reloj no confiable)
  recibido_en                 timestamptz not null default now(),     -- autoritativo
  actualizado_en              timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, sucursal_id, fecha_operativa, numero),
  foreign key (tenant_id, sucursal_id)     references sucursales (tenant_id, id),
  foreign key (tenant_id, mesa_id)         references mesas (tenant_id, id),
  foreign key (tenant_id, direccion_id)    references direcciones_cliente (tenant_id, id),
  foreign key (tenant_id, cliente_id)      references clientes (tenant_id, id),
  foreign key (tenant_id, turno_caja_id)   references turnos_caja (tenant_id, id),
  foreign key (tenant_id, dispositivo_id)  references dispositivos (tenant_id, id),
  foreign key (tenant_id, pedido_origen_id) references pedidos (tenant_id, id),
  check (substr(id::text, 15, 1) = '7'),
  check (total = subtotal - descuento + iva + propina + costo_envio),
  check (descuento <= subtotal),
  check (canal <> 'salon' or mesa_id is not null),
  check (canal <> 'delivery_propio' or (direccion_id is not null and cliente_id is not null)),
  check (estado not in ('pago_pendiente','asignado','en_camino') or canal = 'delivery_propio'),
  check (descuento = 0 or motivo_descuento is not null)
);
create index ix_pedidos_activos
  on pedidos (tenant_id, sucursal_id, estado)
  where estado not in ('cerrado','cancelado','devuelto_total');
create index ix_pedidos_reportes on pedidos (tenant_id, sucursal_id, fecha_operativa, canal);
create index ix_pedidos_mesa on pedidos (tenant_id, mesa_id) where mesa_id is not null;
create index ix_pedidos_cliente on pedidos (tenant_id, cliente_id) where cliente_id is not null;

create table pedido_items (
  id                uuid primary key default uuid_v7(),               -- UUIDv7 del cliente
  tenant_id         uuid not null references tenants (id),
  pedido_id         uuid not null,
  producto_id       uuid,
  nombre            text not null,                                     -- snapshot
  precio_unitario   numeric(12,2) not null check (precio_unitario >= 0),
  tarifa_iva        numeric(5,2) not null check (tarifa_iva >= 0),
  codigo_iva_sri    text not null,
  cantidad          numeric(10,3) not null check (cantidad > 0),
  nota              text,
  estacion_id       uuid,
  tiempo_servicio   text not null default 'principal'
                      check (tiempo_servicio in ('entrada','principal','postre','bebida')),
  estado_cocina     text not null default 'pendiente'
                      check (estado_cocina in ('pendiente','enviado','preparando','listo','servido','anulado')),
  motivo_anulacion  text,
  anulado_por       uuid references usuarios (id),
  es_merma          boolean not null default false,
  enviado_en        timestamptz,
  preparando_en     timestamptz,
  listo_en          timestamptz,
  servido_en        timestamptz,
  version           integer not null default 1,
  creado_en         timestamptz not null default now(),
  actualizado_en    timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, pedido_id)   references pedidos (tenant_id, id),
  foreign key (tenant_id, producto_id) references productos (tenant_id, id),
  foreign key (tenant_id, estacion_id) references estaciones_cocina (tenant_id, id),
  check (substr(id::text, 15, 1) = '7'),
  check (estado_cocina <> 'anulado' or motivo_anulacion is not null)
);
create index ix_items_pedido on pedido_items (tenant_id, pedido_id);
create index ix_items_estacion_activos
  on pedido_items (tenant_id, estacion_id, enviado_en)
  where estado_cocina in ('enviado','preparando','listo');

create table pedido_item_modificadores (
  id               uuid primary key default uuid_v7(),
  tenant_id        uuid not null references tenants (id),
  pedido_item_id   uuid not null,
  modificador_id   uuid,
  nombre           text not null,                                      -- snapshot
  precio_extra     numeric(12,2) not null default 0 check (precio_extra >= 0),
  foreign key (tenant_id, pedido_item_id) references pedido_items (tenant_id, id),
  foreign key (tenant_id, modificador_id) references modificadores (tenant_id, id),
  check (substr(id::text, 15, 1) = '7')
);
create index ix_item_mod_item on pedido_item_modificadores (tenant_id, pedido_item_id);

-- Bitacora inmutable, particionada por mes.
create table pedido_historial (
  id               uuid not null default uuid_v7(),
  ocurrido_en      timestamptz not null default now(),
  tenant_id        uuid not null references tenants (id),
  pedido_id        uuid not null,
  estado_anterior  text,
  estado_nuevo     text not null,
  usuario_id       uuid references usuarios (id),
  dispositivo_id   uuid,
  motivo           text,
  origen           text not null default 'api' check (origen in ('api','sync','sistema')),
  primary key (id, ocurrido_en),
  foreign key (tenant_id, pedido_id) references pedidos (tenant_id, id),
  check (estado_nuevo not in ('cancelado','devuelto_parcial','devuelto_total') or motivo is not null)
) partition by range (ocurrido_en);
create index ix_historial_pedido on pedido_historial (tenant_id, pedido_id, ocurrido_en);

-- -----------------------------------------------------------------------------
-- 8. Delivery
-- -----------------------------------------------------------------------------
create table zonas_delivery (
  id                   uuid primary key default uuid_v7(),
  tenant_id            uuid not null references tenants (id),
  sucursal_id          uuid not null,
  nombre               text not null,
  poligono             jsonb not null,                                 -- GeoJSON (o PostGIS mas adelante)
  costo_envio          numeric(12,2) not null default 0 check (costo_envio >= 0),
  pedido_minimo        numeric(12,2) not null default 0 check (pedido_minimo >= 0),
  tiempo_estimado_min  integer check (tiempo_estimado_min > 0),
  activa               boolean not null default true,
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);

create table entregas (
  id                     uuid primary key default uuid_v7(),
  tenant_id              uuid not null references tenants (id),
  pedido_id              uuid not null,
  repartidor_id          uuid references usuarios (id),
  estado                 text not null default 'pendiente'
                           check (estado in ('pendiente','asignado','recogido','entregado','fallido','devuelto_a_local')),
  eta                    timestamptz,
  asignado_en            timestamptz,
  recogido_en            timestamptz,
  entregado_en           timestamptz,
  efectivo_por_cobrar    numeric(12,2) not null default 0 check (efectivo_por_cobrar >= 0),
  efectivo_liquidado_en  timestamptz,
  liquidado_por          uuid references usuarios (id),
  motivo_fallo           text,
  ultima_lat             numeric(9,6),
  ultima_lng             numeric(9,6),
  ultima_ubicacion_en    timestamptz,
  creado_en              timestamptz not null default now(),
  actualizado_en         timestamptz not null default now(),
  unique (tenant_id, pedido_id),
  foreign key (tenant_id, pedido_id) references pedidos (tenant_id, id),
  check (estado <> 'fallido' or motivo_fallo is not null),
  check (estado not in ('asignado','recogido','entregado') or repartidor_id is not null)
);
create index ix_entregas_repartidor
  on entregas (tenant_id, repartidor_id) where estado in ('asignado','recogido');

-- -----------------------------------------------------------------------------
-- 9. Pagos
-- -----------------------------------------------------------------------------
create table pagos (
  id                   uuid primary key default uuid_v7(),            -- UUIDv7 del cliente
  tenant_id            uuid not null references tenants (id),
  pedido_id            uuid not null,
  turno_caja_id        uuid,
  dispositivo_id       uuid,
  metodo               text not null
                         check (metodo in ('efectivo','tarjeta_datafono','transferencia','pasarela','otro')),
  monto                numeric(12,2) not null check (monto > 0),
  propina              numeric(12,2) not null default 0 check (propina >= 0 and propina <= monto),
  estado               text not null default 'pendiente'
                         check (estado in ('pendiente','autorizado','capturado','rechazado',
                                           'reembolsado_parcial','reembolsado')),
  pasarela             text,
  referencia_ext       text,
  llave_idempotencia   text,
  ultimos4             char(4) check (ultimos4 ~ '^[0-9]{4}$'),
  marca_tarjeta        text,
  lote_datafono        text,
  conciliacion         text not null default 'no_aplica'
                         check (conciliacion in ('no_aplica','por_conciliar','conciliado','discrepancia')),
  conciliado_en        timestamptz,
  conciliado_con       text,
  registrado_offline   boolean not null default false,
  creado_en            timestamptz not null default now(),
  actualizado_en       timestamptz not null default now(),
  unique (tenant_id, id),
  foreign key (tenant_id, pedido_id)      references pedidos (tenant_id, id),
  foreign key (tenant_id, turno_caja_id)  references turnos_caja (tenant_id, id),
  foreign key (tenant_id, dispositivo_id) references dispositivos (tenant_id, id),
  check (substr(id::text, 15, 1) = '7')
);
create unique index ux_pagos_ref_ext on pagos (pasarela, referencia_ext) where referencia_ext is not null;
create unique index ux_pagos_idempotencia on pagos (tenant_id, llave_idempotencia) where llave_idempotencia is not null;
create index ix_pagos_pedido on pagos (tenant_id, pedido_id);
create index ix_pagos_turno on pagos (tenant_id, turno_caja_id) where turno_caja_id is not null;
create index ix_pagos_conciliar on pagos (tenant_id, conciliacion) where conciliacion in ('por_conciliar','discrepancia');

create table reembolsos (
  id              uuid primary key default uuid_v7(),
  tenant_id       uuid not null references tenants (id),
  pago_id         uuid not null,
  monto           numeric(12,2) not null check (monto > 0),
  motivo          text not null,
  autorizado_por  uuid not null references usuarios (id),
  estado          text not null default 'pendiente' check (estado in ('pendiente','completado','fallido')),
  referencia_ext  text,
  creado_en       timestamptz not null default now(),
  actualizado_en  timestamptz not null default now(),
  foreign key (tenant_id, pago_id) references pagos (tenant_id, id)
);
create index ix_reembolsos_pago on reembolsos (tenant_id, pago_id);

-- tenant_id es obligatorio: el endpoint del webhook incluye el tenant en la URL.
create table pagos_webhooks (
  id               uuid primary key default uuid_v7(),
  tenant_id        uuid not null references tenants (id),
  pasarela         text not null,
  id_evento_ext    text not null,
  payload          jsonb not null,
  firma_valida     boolean not null,
  recibido_en      timestamptz not null default now(),
  procesado_en     timestamptz,
  unique (pasarela, id_evento_ext)
);

-- -----------------------------------------------------------------------------
-- 10. Facturacion electronica SRI
-- -----------------------------------------------------------------------------
create table certificados_firma (
  id             uuid primary key default uuid_v7(),
  tenant_id      uuid not null references tenants (id),
  secreto_ref    text not null,                  -- referencia a KMS / gestor de secretos (nunca el .p12)
  huella         text not null,
  sujeto         text,
  emisor         text,
  valido_desde   timestamptz not null,
  caduca_en      timestamptz not null,
  estado         text not null default 'activo'
                   check (estado in ('activo','por_vencer','vencido','reemplazado')),
  creado_en      timestamptz not null default now(),
  actualizado_en timestamptz not null default now(),
  unique (tenant_id, id),
  check (caduca_en > valido_desde)
);
create unique index ux_certificado_vigente
  on certificados_firma (tenant_id) where estado in ('activo','por_vencer');

-- Contador transaccional de secuenciales de comprobantes. Lo asigna SOLO el servidor.
create table secuenciales (
  tenant_id         uuid not null references tenants (id),
  sucursal_id       uuid not null,
  tipo_comprobante  text not null check (tipo_comprobante in ('01','04')),
  ambiente          text not null check (ambiente in ('pruebas','produccion')),
  ultimo_valor      integer not null default 0 check (ultimo_valor between 0 and 999999999),
  primary key (tenant_id, sucursal_id, tipo_comprobante, ambiente),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);

create table comprobantes (
  id                           uuid primary key default uuid_v7(),
  tenant_id                    uuid not null references tenants (id),
  sucursal_id                  uuid not null,
  pedido_id                    uuid not null,
  tipo                         char(2) not null check (tipo in ('01','04')),
  comprobante_modificado_id    uuid,
  motivo_modificacion          text,
  ambiente                     text not null check (ambiente in ('pruebas','produccion')),
  secuencial                   char(9) not null check (secuencial ~ '^[0-9]{9}$'),
  clave_acceso                 char(49) not null unique check (clave_acceso ~ '^[0-9]{49}$'),
  tipo_emision                 text not null default 'normal' check (tipo_emision in ('normal','contingencia')),
  estado_sri                   text not null default 'generado'
                                 check (estado_sri in ('generado','firmado','enviado','autorizado','rechazado',
                                                       'no_autorizado','requiere_revision',
                                                       'bloqueada_certificado','anulado')),
  fecha_emision                date not null,
  receptor_tipo_identificacion text not null check (receptor_tipo_identificacion in ('04','05','06','07')),
  receptor_identificacion      text not null,
  receptor_razon_social        text not null,
  receptor_email               text,
  subtotal_sin_impuestos       numeric(12,2) not null check (subtotal_sin_impuestos >= 0),
  total_iva                    numeric(12,2) not null check (total_iva >= 0),
  propina                      numeric(12,2) not null default 0 check (propina >= 0),
  importe_total                numeric(12,2) not null check (importe_total >= 0),
  numero_autorizacion          text,
  fecha_autorizacion           timestamptz,
  intentos                     integer not null default 0 check (intentos >= 0),
  proximo_intento_en           timestamptz,
  ultimo_error                 text,
  xml_url                      text,
  ride_url                     text,
  mensaje_sri                  text,
  creado_en                    timestamptz not null default now(),
  actualizado_en               timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, sucursal_id, tipo, ambiente, secuencial),
  foreign key (tenant_id, sucursal_id)              references sucursales (tenant_id, id),
  foreign key (tenant_id, pedido_id)                references pedidos (tenant_id, id),
  foreign key (tenant_id, comprobante_modificado_id) references comprobantes (tenant_id, id),
  check (tipo <> '04' or (comprobante_modificado_id is not null and motivo_modificacion is not null)),
  -- Identidad del comprobante: lo que se cobra es la base (items + envio) mas el IVA mas la propina.
  check (importe_total = subtotal_sin_impuestos + total_iva + propina),
  check (estado_sri <> 'autorizado' or (numero_autorizacion is not null and fecha_autorizacion is not null))
);
create index ix_comprobantes_pedido on comprobantes (tenant_id, pedido_id);
-- Usado por el scheduler (rol que recorre varios tenants).
create index ix_comprobantes_pendientes
  on comprobantes (proximo_intento_en)
  where estado_sri in ('generado','firmado','enviado');
-- Un pedido tiene como maximo una factura vigente (el reintento de un evento no puede duplicarla).
create unique index ux_comprobante_factura_pedido
  on comprobantes (tenant_id, pedido_id) where tipo = '01' and estado_sri <> 'anulado';
-- Una factura tiene como maximo una nota de credito vigente (una devolucion total no se puede acreditar dos veces).
create unique index ux_comprobante_nota_credito_factura
  on comprobantes (tenant_id, comprobante_modificado_id) where tipo = '04' and estado_sri <> 'anulado';

create table comprobante_detalles (
  id                uuid primary key default uuid_v7(),
  tenant_id         uuid not null references tenants (id),
  comprobante_id    uuid not null,
  linea             integer not null check (linea > 0),
  codigo            text not null,
  descripcion       text not null,
  cantidad          numeric(14,6) not null check (cantidad > 0),
  precio_unitario   numeric(14,6) not null check (precio_unitario >= 0),
  descuento         numeric(12,2) not null default 0 check (descuento >= 0),
  base_imponible    numeric(12,2) not null check (base_imponible >= 0),
  codigo_iva_sri    text not null,
  tarifa_iva        numeric(5,2) not null check (tarifa_iva >= 0),
  valor_iva         numeric(12,2) not null check (valor_iva >= 0),
  unique (comprobante_id, linea),
  foreign key (tenant_id, comprobante_id) references comprobantes (tenant_id, id)
);

create table comprobante_pagos (
  id               uuid primary key default uuid_v7(),
  tenant_id        uuid not null references tenants (id),
  comprobante_id   uuid not null,
  forma_pago_sri   text not null,
  monto            numeric(12,2) not null check (monto > 0),
  foreign key (tenant_id, comprobante_id) references comprobantes (tenant_id, id)
);

create table comprobante_eventos (
  id              uuid not null default uuid_v7(),
  ocurrido_en     timestamptz not null default now(),
  tenant_id       uuid not null references tenants (id),
  comprobante_id  uuid not null,
  estado          text not null,
  respuesta       text,
  primary key (id, ocurrido_en),
  foreign key (tenant_id, comprobante_id) references comprobantes (tenant_id, id)
) partition by range (ocurrido_en);
create index ix_comp_eventos on comprobante_eventos (tenant_id, comprobante_id, ocurrido_en);

-- -----------------------------------------------------------------------------
-- 11. Eventos, sincronizacion y auditoria
-- -----------------------------------------------------------------------------
-- Outbox: se escribe en la MISMA transaccion que el cambio de negocio.
-- Particionada por semana (ver app.mantener_particiones).
create table outbox (
  event_id         uuid not null default uuid_v7(),
  creado_en        timestamptz not null default now(),
  tenant_id        uuid not null references tenants (id),
  tipo             text not null,
  version_esquema  integer not null default 1,
  agregado_tipo    text not null,
  agregado_id      uuid not null,
  payload          jsonb not null default '{}'::jsonb,
  publicado_en     timestamptz,
  intentos         integer not null default 0 check (intentos >= 0),
  ultimo_error     text,
  proximo_intento_en timestamptz not null default now(),   -- reintento con espera creciente
  primary key (event_id, creado_en)
) partition by range (creado_en);
create index ix_outbox_pendientes on outbox (creado_en) where publicado_en is null;

-- Deduplicacion del lado consumidor (entrega "al menos una vez").
create table eventos_procesados (
  consumidor    text not null,
  event_id      uuid not null,
  tenant_id     uuid not null references tenants (id),
  procesado_en  timestamptz not null default now(),
  primary key (consumidor, event_id)
);
create index ix_eventos_procesados_fecha on eventos_procesados (procesado_en);

-- Acciones recibidas por /v1/sync. Particionada por la fecha codificada en el UUIDv7
-- (determinista: un reintento cae siempre en la misma particion, asi la PK deduplica).
-- Insertar SIEMPRE con app.registrar_sync_accion().
create table sync_acciones (
  accion_id          uuid not null,
  fecha_particion    date not null,
  tenant_id          uuid not null references tenants (id),
  dispositivo_id     uuid not null,
  usuario_id         uuid not null references usuarios (id),
  orden_local        bigint not null,
  tipo               text not null,
  payload            jsonb not null,
  version_base       integer,
  reloj_dispositivo  timestamptz,
  recibido_en        timestamptz not null default now(),
  resultado          text not null default 'aplicada'
                       check (resultado in ('aplicada','duplicada','fusionada','rechazada')),
  motivo             text,
  agregado_id        uuid,
  primary key (accion_id, fecha_particion),
  foreign key (tenant_id, dispositivo_id) references dispositivos (tenant_id, id),
  check (substr(accion_id::text, 15, 1) = '7'),
  check (fecha_particion = app.fecha_de_uuid_v7(accion_id))
) partition by range (fecha_particion);
create index ix_sync_dispositivo on sync_acciones (tenant_id, dispositivo_id, orden_local);

create table conflictos_sync (
  id            uuid primary key default uuid_v7(),
  tenant_id     uuid not null references tenants (id),
  tipo          text not null
                  check (tipo in ('mesa_en_conflicto','pago_duplicado','item_en_pedido_cerrado','usuario_revocado')),
  referencias   jsonb not null default '{}'::jsonb,
  estado        text not null default 'abierto' check (estado in ('abierto','resuelto')),
  resuelto_por  uuid references usuarios (id),
  resolucion    text,
  resuelto_en   timestamptz,
  creado_en     timestamptz not null default now(),
  check (estado <> 'resuelto' or (resuelto_por is not null and resuelto_en is not null))
);
create index ix_conflictos_abiertos on conflictos_sync (tenant_id) where estado = 'abierto';

-- Auditoria inmutable, particionada por mes. Retencion minima sugerida: 5 anos.
create table auditoria (
  id              uuid not null default uuid_v7(),
  ocurrido_en     timestamptz not null default now(),
  tenant_id       uuid not null references tenants (id),
  usuario_id      uuid references usuarios (id),
  dispositivo_id  uuid,
  accion          text not null,
  entidad         text not null,
  entidad_id      uuid,
  antes           jsonb,
  despues         jsonb,
  autorizado_por  uuid references usuarios (id),
  ip              inet,
  primary key (id, ocurrido_en)
) partition by range (ocurrido_en);
create index ix_auditoria_entidad on auditoria (tenant_id, entidad, entidad_id, ocurrido_en);
create index ix_auditoria_usuario on auditoria (tenant_id, usuario_id, ocurrido_en);

-- -----------------------------------------------------------------------------
-- 12. Plataforma (SaaS)
-- -----------------------------------------------------------------------------
create table suscripciones (
  id                     uuid primary key default uuid_v7(),
  tenant_id              uuid not null unique references tenants (id),
  plan_id                uuid not null references planes (id),
  estado                 text not null default 'prueba'
                           check (estado in ('prueba','activa','morosa','cancelada')),
  periodo_actual_inicio  date not null,
  periodo_actual_fin     date not null,
  proveedor_cobro        text,
  id_externo             text,
  creado_en              timestamptz not null default now(),
  actualizado_en         timestamptz not null default now(),
  check (periodo_actual_fin > periodo_actual_inicio)
);

create table uso_tenant (
  tenant_id         uuid not null references tenants (id),
  periodo           date not null check (extract(day from periodo) = 1),   -- primer dia del mes
  pedidos           integer not null default 0 check (pedidos >= 0),
  usuarios_activos  integer not null default 0 check (usuarios_activos >= 0),
  comprobantes      integer not null default 0 check (comprobantes >= 0),
  primary key (tenant_id, periodo)
);

-- -----------------------------------------------------------------------------
-- 13. Notificaciones
-- -----------------------------------------------------------------------------
create table notificaciones (
  id            uuid primary key default uuid_v7(),
  tenant_id     uuid not null references tenants (id),
  tipo          text not null,
  canal         text not null check (canal in ('email','push','whatsapp')),
  destinatario  text not null,
  plantilla     text not null,
  datos         jsonb not null default '{}'::jsonb,
  estado        text not null default 'pendiente' check (estado in ('pendiente','enviada','fallida')),
  intentos      integer not null default 0 check (intentos >= 0),
  enviado_en    timestamptz,
  creado_en     timestamptz not null default now()
);
create index ix_notificaciones_pendientes on notificaciones (creado_en) where estado = 'pendiente';

-- Cola de trabajos (ADR-015). Se encola EN LA MISMA transaccion del cambio de negocio. Un worker reclama
-- trabajos con FOR UPDATE SKIP LOCKED (varias instancias sin duplicar), los ejecuta dentro del contexto de su
-- tenant y los marca como completados en esa misma transaccion. 'fallido' hace de cola de trabajos muertos
-- (dead-letter): nunca se descarta un trabajo en silencio.
--   * bloqueado_hasta / bloqueado_por = "lease": si el worker muere, otro lo reclama al vencer.
--   * completar exige seguir siendo el dueno del lease (evita que dos workers completen el mismo trabajo).
--   * clave_unica = idempotencia del encolado.
-- Los completados se acumulan: pendiente una purga por antiguedad (ver Backlog).
create table jobs (
  id                  uuid primary key default uuid_v7(),
  tenant_id           uuid not null references tenants (id),
  cola                text not null,
  tipo                text not null,
  payload             jsonb not null default '{}'::jsonb,
  clave_unica         text,
  estado              text not null default 'pendiente'
                        check (estado in ('pendiente','en_proceso','completado','fallido')),
  intentos            integer not null default 0 check (intentos >= 0),
  max_intentos        integer not null default 5 check (max_intentos > 0),
  proximo_intento_en  timestamptz not null default now(),
  bloqueado_hasta     timestamptz,
  bloqueado_por       text,
  ultimo_error        text,
  creado_en           timestamptz not null default now(),
  actualizado_en      timestamptz not null default now(),
  completado_en       timestamptz,
  check (estado <> 'en_proceso' or (bloqueado_hasta is not null and bloqueado_por is not null)),
  check (estado <> 'completado' or completado_en is not null)
);
create unique index ux_jobs_clave on jobs (tenant_id, cola, clave_unica) where clave_unica is not null;
create index ix_jobs_pendientes on jobs (proximo_intento_en) where estado = 'pendiente';
create index ix_jobs_en_proceso on jobs (bloqueado_hasta) where estado = 'en_proceso';
create index ix_jobs_fallidos on jobs (tenant_id, creado_en) where estado = 'fallido';

-- -----------------------------------------------------------------------------
-- 14. Configuracion por tenant
-- -----------------------------------------------------------------------------
create table configuracion_tenant (
  tenant_id       uuid not null references tenants (id),
  sucursal_id     uuid,
  clave           text not null,
  valor           jsonb not null,
  actualizado_en  timestamptz not null default now(),
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id)
);
create unique index ux_configuracion
  on configuracion_tenant (tenant_id, coalesce(sucursal_id, '00000000-0000-0000-0000-000000000000'::uuid), clave);

create table impresoras (
  id           uuid primary key default uuid_v7(),
  tenant_id    uuid not null references tenants (id),
  sucursal_id  uuid not null,
  nombre       text not null,
  tipo         text not null check (tipo in ('termica_red','termica_bluetooth')),
  direccion    text not null,
  rol          text not null check (rol in ('caja','cocina','bar')),
  estacion_id  uuid,
  activa       boolean not null default true,
  foreign key (tenant_id, sucursal_id) references sucursales (tenant_id, id),
  foreign key (tenant_id, estacion_id) references estaciones_cocina (tenant_id, id)
);

-- -----------------------------------------------------------------------------
-- 15. Funciones de negocio
-- -----------------------------------------------------------------------------
-- Siguiente secuencial de comprobante. Se llama dentro de la transaccion que crea
-- el comprobante: si hace rollback, el contador tambien (sin huecos por errores).
create or replace function app.siguiente_secuencial(p_sucursal uuid, p_tipo text, p_ambiente text)
returns char(9) language plpgsql as $$
declare
  v_valor integer;
begin
  insert into secuenciales (tenant_id, sucursal_id, tipo_comprobante, ambiente, ultimo_valor)
  values (app.current_tenant(), p_sucursal, p_tipo, p_ambiente, 1)
  on conflict (tenant_id, sucursal_id, tipo_comprobante, ambiente)
  do update set ultimo_valor = secuenciales.ultimo_valor + 1
  returning ultimo_valor into v_valor;
  return lpad(v_valor::text, 9, '0');
end $$;

-- Numero definitivo de pedido por sucursal y dia operativo.
create or replace function app.siguiente_numero_pedido(p_sucursal uuid, p_fecha date)
returns integer language plpgsql as $$
declare
  v_valor integer;
begin
  insert into contadores_pedido (tenant_id, sucursal_id, fecha_operativa, ultimo_valor)
  values (app.current_tenant(), p_sucursal, p_fecha, 1)
  on conflict (tenant_id, sucursal_id, fecha_operativa)
  do update set ultimo_valor = contadores_pedido.ultimo_valor + 1
  returning ultimo_valor into v_valor;
  return v_valor;
end $$;

-- Clave de acceso de 49 digitos (formato del SRI; VERIFICAR contra la ficha tecnica vigente):
-- fecha ddmmaaaa(8) + tipo(2) + RUC(13) + ambiente(1: 1 pruebas, 2 produccion) +
-- serie(6 = estab+punto) + secuencial(9) + codigo numerico(8) + tipo emision(1) + digito verificador(1).
-- El codigo numerico se deriva del secuencial: es DETERMINISTA, asi un reintento
-- produce la misma clave.
create or replace function app.generar_clave_acceso(
  p_fecha date, p_tipo text, p_ruc text, p_ambiente text,
  p_estab text, p_punto text, p_secuencial text, p_tipo_emision text default '1'
) returns char(49) language plpgsql immutable as $$
declare
  v_base   text;
  v_suma   integer := 0;
  v_peso   integer := 2;
  v_dv     integer;
  i        integer;
begin
  v_base := to_char(p_fecha, 'DDMMYYYY') || p_tipo || p_ruc
         || (case p_ambiente when 'pruebas' then '1' else '2' end)
         || p_estab || p_punto || p_secuencial
         || right(p_secuencial, 8) || p_tipo_emision;
  if length(v_base) <> 48 or v_base !~ '^[0-9]{48}$' then
    raise exception 'Clave de acceso invalida: la base debe tener 48 digitos numericos (%)', v_base;
  end if;
  for i in reverse 48..1 loop
    v_suma := v_suma + substr(v_base, i, 1)::integer * v_peso;
    v_peso := case when v_peso = 7 then 2 else v_peso + 1 end;
  end loop;
  v_dv := 11 - (v_suma % 11);
  if v_dv = 11 then v_dv := 0; elsif v_dv = 10 then v_dv := 1; end if;
  return v_base || v_dv::text;
end $$;

-- Publica un evento en el outbox (dentro de la transaccion del cambio de negocio).
create or replace function app.emitir_evento(
  p_tipo text, p_agregado_tipo text, p_agregado_id uuid,
  p_payload jsonb default '{}'::jsonb, p_version_esquema integer default 1
) returns uuid language plpgsql as $$
declare
  v_id uuid := uuid_v7();
begin
  insert into outbox (event_id, tenant_id, tipo, version_esquema, agregado_tipo, agregado_id, payload)
  values (v_id, app.current_tenant(), p_tipo, p_version_esquema, p_agregado_tipo, p_agregado_id, p_payload);
  return v_id;
end $$;

-- Registro idempotente de una accion de sync. Devuelve TRUE si es nueva y FALSE si
-- ya existia (duplicado): el endpoint responde "duplicada" sin reprocesar.
create or replace function app.registrar_sync_accion(
  p_accion_id uuid, p_dispositivo_id uuid, p_usuario_id uuid, p_orden_local bigint,
  p_tipo text, p_payload jsonb, p_version_base integer, p_reloj timestamptz
) returns boolean language plpgsql as $$
declare
  v_filas integer;
begin
  insert into sync_acciones (accion_id, fecha_particion, tenant_id, dispositivo_id, usuario_id,
                             orden_local, tipo, payload, version_base, reloj_dispositivo)
  values (p_accion_id, app.fecha_de_uuid_v7(p_accion_id), app.current_tenant(), p_dispositivo_id,
          p_usuario_id, p_orden_local, p_tipo, p_payload, p_version_base, p_reloj)
  on conflict (accion_id, fecha_particion) do nothing;
  get diagnostics v_filas = row_count;
  return v_filas = 1;
end $$;

-- La suma de pagos activos de un pedido no puede superar su total.
-- La suma COBRADA NETA de un pedido (lo cobrado menos lo ya reembolsado) no puede superar su total. Un pago
-- reembolsado parcialmente solo cuenta por la parte que sigue cobrada; asi, tras reembolsar se puede volver a cobrar.
create or replace function app.validar_suma_pagos() returns trigger
language plpgsql as $$
declare
  v_total  numeric(12,2);
  v_otros  numeric(12,2);
  v_propio numeric(12,2);
begin
  if new.estado in ('rechazado','reembolsado') then
    return new;
  end if;
  select total into v_total
    from pedidos where tenant_id = new.tenant_id and id = new.pedido_id for update;
  select coalesce(sum(p.monto - coalesce((select sum(r.monto) from reembolsos r
                                           where r.tenant_id = p.tenant_id and r.pago_id = p.id
                                             and r.estado in ('pendiente','completado')), 0)), 0)
    into v_otros
    from pagos p
   where p.tenant_id = new.tenant_id and p.pedido_id = new.pedido_id and p.id <> new.id
     and p.estado in ('pendiente','autorizado','capturado','reembolsado_parcial');
  v_propio := new.monto - coalesce((select sum(r.monto) from reembolsos r
                                     where r.tenant_id = new.tenant_id and r.pago_id = new.id
                                       and r.estado in ('pendiente','completado')), 0);
  if v_otros + v_propio > v_total then
    raise exception 'La suma de pagos (%) excede el total del pedido (%)', v_otros + v_propio, v_total
      using errcode = '23514', constraint = 'pagos_suma_excede_total';
  end if;
  return new;
end $$;

create trigger trg_pagos_suma
  before insert or update of monto, estado on pagos
  for each row execute function app.validar_suma_pagos();

-- La suma de reembolsos no puede superar el monto del pago.
create or replace function app.validar_suma_reembolsos() returns trigger
language plpgsql as $$
declare
  v_monto numeric(12,2);
  v_otros numeric(12,2);
begin
  if new.estado = 'fallido' then
    return new;
  end if;
  select monto into v_monto
    from pagos where tenant_id = new.tenant_id and id = new.pago_id for update;
  select coalesce(sum(monto), 0) into v_otros
    from reembolsos
   where tenant_id = new.tenant_id and pago_id = new.pago_id and id <> new.id
     and estado in ('pendiente','completado');
  if v_otros + new.monto > v_monto then
    raise exception 'La suma de reembolsos (%) excede el pago (%)', v_otros + new.monto, v_monto
      using errcode = '23514', constraint = 'reembolsos_suma_excede_pago';
  end if;
  return new;
end $$;

create trigger trg_reembolsos_suma
  before insert or update of monto, estado on reembolsos
  for each row execute function app.validar_suma_reembolsos();

-- Particiones: crea (si no existen) las del periodo actual y las siguientes, mas una
-- particion DEFAULT por tabla para que ningun insert falle. Ejecutar de forma
-- periodica (job "mantenimiento") o reemplazar por pg_partman.
create or replace function app.crear_particion(
  p_tabla regclass, p_desde date, p_hasta date, p_es_fecha boolean default false
) returns void language plpgsql as $$
declare
  v_nombre text;
  v_desde  text;
  v_hasta  text;
begin
  select relname into v_nombre from pg_class where oid = p_tabla;
  v_nombre := format('%s_p%s', v_nombre, to_char(p_desde, 'YYYYMMDD'));
  if p_es_fecha then
    v_desde := p_desde::text;
    v_hasta := p_hasta::text;
  else
    v_desde := p_desde::text || ' 00:00:00+00';
    v_hasta := p_hasta::text || ' 00:00:00+00';
  end if;
  execute format('create table if not exists public.%I partition of %s for values from (%L) to (%L)',
                 v_nombre, p_tabla, v_desde, v_hasta);
end $$;

create or replace function app.mantener_particiones(p_meses integer default 3, p_semanas integer default 8)
returns void language plpgsql as $$
declare
  t text;
  d date;
  i integer;
begin
  foreach t in array array['pedido_historial','comprobante_eventos','auditoria','outbox','sync_acciones'] loop
    execute format('create table if not exists public.%I partition of public.%I default',
                   t || '_default', t);
  end loop;

  -- Mensuales
  foreach t in array array['pedido_historial','comprobante_eventos','auditoria'] loop
    for i in 0..p_meses loop
      d := (date_trunc('month', now() at time zone 'UTC') + make_interval(months => i))::date;
      perform app.crear_particion(('public.' || t)::regclass, d, (d + interval '1 month')::date);
    end loop;
  end loop;

  -- sync_acciones: mensual por fecha
  for i in 0..p_meses loop
    d := (date_trunc('month', now() at time zone 'UTC') + make_interval(months => i))::date;
    perform app.crear_particion('public.sync_acciones'::regclass, d, (d + interval '1 month')::date, true);
  end loop;

  -- outbox: semanal
  for i in 0..p_semanas loop
    d := (date_trunc('week', now() at time zone 'UTC') + make_interval(weeks => i))::date;
    perform app.crear_particion('public.outbox'::regclass, d, d + 7);
  end loop;
end $$;

-- -----------------------------------------------------------------------------
-- 16. Funciones de autenticacion (previas al contexto de tenant)
--     Pertenecen a app_auth (BYPASSRLS) y devuelven solo lo necesario.
-- -----------------------------------------------------------------------------
create or replace function app.buscar_usuario_login(p_email text)
returns table (id uuid, email text, nombre text, password_hash text, mfa_secreto text, activo boolean, bloqueado boolean)
language sql stable security definer set search_path = public, pg_temp as $$
  select u.id, u.email, u.nombre, u.password_hash, u.mfa_secreto, u.activo,
         (u.login_bloqueado_hasta is not null and u.login_bloqueado_hasta > now()) as bloqueado
    from usuarios u where u.email = lower(p_email)
$$;

-- Registra un intento de login fallido y, al llegar al maximo, bloquea la cuenta. Devuelve el fin del bloqueo
-- (null si no esta bloqueada). El reloj es el de la base de datos.
create or replace function app.registrar_login_fallido(p_usuario_id uuid, p_max_intentos integer, p_bloqueo_minutos integer)
returns timestamptz language sql volatile security definer set search_path = public, pg_temp as $$
  update usuarios
     set login_intentos = login_intentos + 1,
         login_bloqueado_hasta = case when login_intentos + 1 >= p_max_intentos
                                      then now() + make_interval(mins => p_bloqueo_minutos)
                                      else login_bloqueado_hasta end
   where id = p_usuario_id
  returning login_bloqueado_hasta
$$;

-- Un login correcto reinicia el contador y levanta el bloqueo.
create or replace function app.registrar_login_exitoso(p_usuario_id uuid)
returns void language sql volatile security definer set search_path = public, pg_temp as $$
  update usuarios set login_intentos = 0, login_bloqueado_hasta = null where id = p_usuario_id
$$;

create or replace function app.crear_usuario(p_email text, p_nombre text, p_password_hash text)
returns uuid language sql volatile security definer set search_path = public, pg_temp as $$
  insert into usuarios (email, nombre, password_hash)
  values (lower(p_email), p_nombre, p_password_hash)
  returning id
$$;

create or replace function app.membresias_de_usuario(p_usuario_id uuid)
returns table (membresia_id uuid, tenant_id uuid, sucursal_id uuid, rol text, estado text)
language sql stable security definer set search_path = public, pg_temp as $$
  select m.id, m.tenant_id, m.sucursal_id, m.rol, m.estado
    from membresias m
   where m.usuario_id = p_usuario_id and m.estado = 'activa'
$$;

grant usage on schema public to app_auth;
grant usage, create on schema app to app_auth;
alter function app.buscar_usuario_login(text)   owner to app_auth;
alter function app.crear_usuario(text, text, text) owner to app_auth;
alter function app.membresias_de_usuario(uuid)  owner to app_auth;
alter function app.registrar_login_fallido(uuid, integer, integer) owner to app_auth;
alter function app.registrar_login_exitoso(uuid) owner to app_auth;
revoke create on schema app from app_auth;

grant select, insert, update on usuarios, membresias to app_auth;

revoke all on function app.buscar_usuario_login(text)      from public;
revoke all on function app.crear_usuario(text, text, text) from public;
revoke all on function app.membresias_de_usuario(uuid)     from public;
revoke all on function app.registrar_login_fallido(uuid, integer, integer) from public;
revoke all on function app.registrar_login_exitoso(uuid)   from public;
grant execute on function app.buscar_usuario_login(text)      to app_backend;
grant execute on function app.crear_usuario(text, text, text) to app_backend;
grant execute on function app.membresias_de_usuario(uuid)     to app_backend;
grant execute on function app.registrar_login_fallido(uuid, integer, integer) to app_backend;
grant execute on function app.registrar_login_exitoso(uuid)   to app_backend;

-- -----------------------------------------------------------------------------
-- 17. Datos semilla (antes de activar RLS)
-- -----------------------------------------------------------------------------
insert into planes (codigo, nombre, max_sucursales, max_usuarios, max_dispositivos, max_pedidos_mes,
                    permite_dominio_propio, precio_mensual) values
  ('basico',     'Basico',      1,  10,   3,   2000, false,  0),
  ('pro',        'Pro',         5,  50,  15,  20000, true,   0),
  ('enterprise', 'Enterprise', 50, 500, 150,   null, true,   0);   -- precios por definir

-- VERIFICAR codigos y porcentajes vigentes con la ficha tecnica del SRI.
insert into tarifas_iva (tenant_id, codigo_sri, nombre, porcentaje, vigente_desde) values
  (null, '0', 'IVA 0%',           0, date '2000-01-01'),
  (null, '4', 'IVA 15%',         15, date '2024-04-01'),
  (null, '6', 'No objeto de IVA', 0, date '2000-01-01'),
  (null, '7', 'Exento de IVA',    0, date '2000-01-01');

-- -----------------------------------------------------------------------------
-- 18. Triggers genericos
-- -----------------------------------------------------------------------------
do $$
declare
  r record;
begin
  -- actualizado_en
  for r in
    select c.relname
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind in ('r','p') and not c.relispartition
       and exists (select 1 from pg_attribute a
                    where a.attrelid = c.oid and a.attname = 'actualizado_en' and not a.attisdropped)
  loop
    execute format('create trigger trg_actualizado_en before update on %I
                    for each row execute function app.set_actualizado_en()', r.relname);
  end loop;

  -- version (concurrencia optimista)
  for r in
    select c.relname
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind in ('r','p') and not c.relispartition
       and exists (select 1 from pg_attribute a
                    where a.attrelid = c.oid and a.attname = 'version' and a.atttypid = 'int4'::regtype
                      and not a.attisdropped)
  loop
    execute format('create trigger trg_version before update on %I
                    for each row execute function app.incrementar_version()', r.relname);
  end loop;
end $$;

-- Tablas de solo insercion
create trigger trg_inmutable before update or delete on pedido_historial
  for each row execute function app.bloquear_modificacion();
create trigger trg_inmutable before update or delete on comprobante_eventos
  for each row execute function app.bloquear_modificacion();
create trigger trg_inmutable before update or delete on auditoria
  for each row execute function app.bloquear_modificacion();
-- sync_acciones: el contenido original es inmutable; solo se puede completar el
-- resultado (resultado, motivo, agregado_id) tras procesar la accion. Nunca se borra.
create or replace function app.proteger_sync_acciones() returns trigger
language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'sync_acciones no admite DELETE' using errcode = '42501';
  end if;
  if new.accion_id <> old.accion_id
     or new.fecha_particion <> old.fecha_particion
     or new.tenant_id <> old.tenant_id
     or new.dispositivo_id <> old.dispositivo_id
     or new.usuario_id <> old.usuario_id
     or new.orden_local <> old.orden_local
     or new.tipo <> old.tipo
     or new.payload <> old.payload
     or new.version_base is distinct from old.version_base
     or new.reloj_dispositivo is distinct from old.reloj_dispositivo
     or new.recibido_en <> old.recibido_en then
    raise exception 'Solo se pueden actualizar resultado, motivo y agregado_id en sync_acciones'
      using errcode = '42501';
  end if;
  return new;
end $$;

create trigger trg_proteger_sync before update or delete on sync_acciones
  for each row execute function app.proteger_sync_acciones();

-- -----------------------------------------------------------------------------
-- 19. Particiones iniciales
-- -----------------------------------------------------------------------------
select app.mantener_particiones();

-- -----------------------------------------------------------------------------
-- 20. Row-Level Security
-- -----------------------------------------------------------------------------
-- Politica generica para toda tabla con tenant_id (excepto catalogos mixtos).
do $$
declare
  r record;
begin
  for r in
    select c.relname
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind in ('r','p') and not c.relispartition
       and exists (select 1 from pg_attribute a
                    where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
       and c.relname not in ('tarifas_iva')
  loop
    execute format('alter table %I enable row level security', r.relname);
    execute format('alter table %I force row level security', r.relname);
    execute format('drop policy if exists aislamiento_tenant on %I', r.relname);
    execute format('create policy aislamiento_tenant on %I
                    using (tenant_id = app.current_tenant())
                    with check (tenant_id = app.current_tenant())', r.relname);
  end loop;
end $$;

-- tenants: la clave es id.
alter table tenants enable row level security;
alter table tenants force row level security;
create policy aislamiento_tenant on tenants
  using (id = app.current_tenant()) with check (id = app.current_tenant());

-- tarifas_iva: lectura de globales + propias; escritura solo propias.
alter table tarifas_iva enable row level security;
alter table tarifas_iva force row level security;
create policy tarifas_lectura on tarifas_iva for select
  using (tenant_id is null or tenant_id = app.current_tenant());
create policy tarifas_insert on tarifas_iva for insert
  with check (tenant_id = app.current_tenant());
create policy tarifas_update on tarifas_iva for update
  using (tenant_id = app.current_tenant()) with check (tenant_id = app.current_tenant());
create policy tarifas_delete on tarifas_iva for delete
  using (tenant_id = app.current_tenant());

-- usuarios: visibles solo si tienen membresia en el tenant actual.
-- El login y el alta pasan por funciones de app_auth (seccion 16).
alter table usuarios enable row level security;
alter table usuarios force row level security;
create policy usuario_visible on usuarios for select
  using (exists (select 1 from membresias m
                  where m.usuario_id = usuarios.id and m.tenant_id = app.current_tenant()));
create policy usuario_update on usuarios for update
  using (exists (select 1 from membresias m
                  where m.usuario_id = usuarios.id and m.tenant_id = app.current_tenant()))
  with check (exists (select 1 from membresias m
                  where m.usuario_id = usuarios.id and m.tenant_id = app.current_tenant()));

-- planes: catalogo global de solo lectura (sin RLS).

-- -----------------------------------------------------------------------------
-- 21. Privilegios
-- -----------------------------------------------------------------------------
grant usage on schema public, app to app_backend, app_scheduler, app_reportes;

-- Aplicacion: CRUD en general, con restricciones en tablas especiales.
grant select, insert, update, delete on all tables in schema public to app_backend;
revoke insert, delete on usuarios from app_backend;                       -- alta via app.crear_usuario()
revoke insert, update, delete on planes from app_backend;
revoke update, delete on pedido_historial, comprobante_eventos, auditoria, sync_acciones from app_backend;
grant update (resultado, motivo, agregado_id) on sync_acciones to app_backend;   -- completar resultado
revoke update, delete on outbox from app_backend;                          -- el relay publica
revoke truncate on all tables in schema public from app_backend;

-- Scheduler / relay: recorre varios tenants con acceso minimo.
grant select on outbox, comprobantes, certificados_firma, dispositivos, notificaciones to app_scheduler;
grant update (publicado_en, intentos, ultimo_error, proximo_intento_en) on outbox to app_scheduler;
-- Worker de la cola de trabajos: reclama y reprograma trabajos de todos los tenants (BYPASSRLS), sin ver nada mas.
grant select on jobs to app_scheduler;
grant update (estado, intentos, proximo_intento_en, bloqueado_hasta, bloqueado_por, ultimo_error) on jobs to app_scheduler;
grant update (estado) on certificados_firma to app_scheduler;
grant update (estado, intentos, enviado_en) on notificaciones to app_scheduler;

-- Reportes de plataforma: solo lectura de metricas.
grant select on tenants, suscripciones, uso_tenant, planes to app_reportes;

commit;
