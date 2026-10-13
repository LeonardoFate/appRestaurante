-- =============================================================================
-- tests_schema_v2.sql - pruebas del esquema (101 comprobaciones)
--
-- EJECUTAR SOLO SOBRE UNA BASE RECIEN CREADA con schema_restaurantes_v2.sql (por ejemplo:
-- "docker compose down -v && docker compose up -d" en appRestaurante/backend). NO es repetible: usa RUC, correos
-- y conteos fijos, asi que sobre una base con datos (por ejemplo, despues de las pruebas Java) varias fallan por
-- datos duplicados, no por defectos del esquema. (Las pruebas Java si pueden repetirse sobre una base con datos.)
-- Cada comprobacion imprime PASS o FAIL.
-- =============================================================================
\set ON_ERROR_STOP 0
\pset pager off

create or replace function public.t_ok(p_label text, p_cond boolean) returns void language plpgsql as $$
begin
  raise notice '% : %', case when coalesce(p_cond,false) then 'PASS' else 'FAIL' end, p_label;
end $$;

-- Espera que el SQL falle (opcionalmente con un fragmento del mensaje / sqlstate)
create or replace function public.t_err(p_label text, p_sql text, p_frag text default null) returns void language plpgsql as $$
begin
  begin
    execute p_sql;
    raise notice 'FAIL : % (no fallo)', p_label;
  exception when others then
    if p_frag is null or sqlerrm ilike '%'||p_frag||'%' or sqlstate = p_frag then
      raise notice 'PASS : % [%]', p_label, left(sqlerrm, 70);
    else
      raise notice 'FAIL : % (error inesperado: % / %)', p_label, sqlstate, sqlerrm;
    end if;
  end;
end $$;

create or replace function public.t_run(p_label text, p_sql text) returns void language plpgsql as $$
begin
  execute p_sql;
  raise notice 'PASS : %', p_label;
exception when others then
  raise notice 'FAIL : % (% / %)', p_label, sqlstate, sqlerrm;
end $$;

\echo === 1. Funciones base ===
select t_ok('uuid_v7 version=7', substr(uuid_v7()::text,15,1)='7');
select t_ok('uuid_v7 variante RFC (8,9,a,b)', substr(uuid_v7()::text,20,1) in ('8','9','a','b'));
select t_ok('uuid_v7 ordenable en el tiempo', (select a < b from (select uuid_v7() a, (select pg_sleep(0.01)) s, uuid_v7() b) q) is not false);
select t_ok('fecha_de_uuid_v7 = hoy UTC', app.fecha_de_uuid_v7(uuid_v7()) = (now() at time zone 'UTC')::date);
select t_ok('clave de acceso 49 digitos', length(app.generar_clave_acceso(date '2026-10-11','01','1790012345001','pruebas','001','001','000000123')) = 49);
select t_ok('clave de acceso determinista', app.generar_clave_acceso(date '2026-10-11','01','1790012345001','pruebas','001','001','000000123') = app.generar_clave_acceso(date '2026-10-11','01','1790012345001','pruebas','001','001','000000123'));
select app.generar_clave_acceso(date '2026-10-11','01','1790012345001','pruebas','001','001','000000123') as clave_ejemplo;

select uuid_v7() as ta \gset
select uuid_v7() as tb \gset
select uuid_v7() as accion \gset

\echo === 2. Alta de tenants (rol app_backend) ===
set role app_backend;
select set_config('app.tenant_id', :'ta', false);
select t_run('crear tenant A (id = contexto)', format($f$insert into tenants(id,ruc,razon_social,slug,plan_id) select %L,'1790000000001','Resto A','resto-a',id from planes where codigo='basico'$f$, :'ta'));
select t_err('tenant A no puede crear tenant con otro id', format($f$insert into tenants(id,ruc,razon_social,slug,plan_id) select %L,'1790000000002','X','x-rest',id from planes where codigo='basico'$f$, :'tb'), 'row-level security');
select set_config('app.tenant_id', :'tb', false);
select t_run('crear tenant B', format($f$insert into tenants(id,ruc,razon_social,slug,plan_id) select %L,'1790000000003','Resto B','resto-b',id from planes where codigo='basico'$f$, :'tb'));

\echo === 3. Datos base por tenant ===
select set_config('app.tenant_id', :'ta', false);
insert into sucursales(id,tenant_id,nombre,cod_establecimiento,cod_punto_emision,direccion) values (uuid_v7(), :'ta', 'Matriz A','001','001','Calle 1') returning id as suc_a \gset
insert into areas(id,tenant_id,sucursal_id,nombre) values (uuid_v7(), :'ta', :'suc_a', 'Salon') returning id as area_a \gset
insert into mesas(id,tenant_id,sucursal_id,area_id,codigo) values (uuid_v7(), :'ta', :'suc_a', :'area_a', 'M1') returning id as mesa_a \gset
select set_config('app.tenant_id', :'tb', false);
insert into sucursales(id,tenant_id,nombre,cod_establecimiento,cod_punto_emision,direccion) values (uuid_v7(), :'tb', 'Matriz B','001','001','Calle 2') returning id as suc_b \gset
insert into areas(id,tenant_id,sucursal_id,nombre) values (uuid_v7(), :'tb', :'suc_b', 'Salon') returning id as area_b \gset
insert into mesas(id,tenant_id,sucursal_id,area_id,codigo) values (uuid_v7(), :'tb', :'suc_b', :'area_b', 'M1') returning id as mesa_b \gset
select t_ok('mesa_a y mesa_b creadas', :'mesa_a' <> :'mesa_b');

\echo === 4. Aislamiento (RLS) ===
select set_config('app.tenant_id', :'tb', false);
select t_ok('B ve solo su sucursal', (select count(*) from sucursales) = 1);
select t_ok('B no ve la sucursal de A', (select count(*) from sucursales where id = :'suc_a') = 0);
select t_ok('B ve solo su tenant', (select count(*) from tenants) = 1);
select t_err('B no puede insertar fila con tenant_id de A', format($f$insert into sucursales(id,tenant_id,nombre,cod_establecimiento,cod_punto_emision,direccion) values (uuid_v7(), %L,'Hack','009','009','x')$f$, :'ta'), 'row-level security');
select t_run('B UPDATE sobre fila de A afecta 0 filas', format($f$do $d$ declare n int; begin update sucursales set nombre='hack' where id=%L; get diagnostics n = row_count; if n <> 0 then raise exception 'afecto %%', n; end if; end $d$$f$, :'suc_a'));
select t_run('B DELETE sobre fila de A afecta 0 filas', format($f$do $d$ declare n int; begin delete from sucursales where id=%L; get diagnostics n = row_count; if n <> 0 then raise exception 'afecto %%', n; end if; end $d$$f$, :'suc_a'));
select t_err('FK compuesta: B no puede referenciar mesa de A', format($f$insert into pedidos(id,tenant_id,sucursal_id,canal,mesa_id,fecha_operativa) values (uuid_v7(), %L, %L, 'salon', %L, current_date)$f$, :'tb', :'suc_b', :'mesa_a'), 'foreign key');
select t_err('FK compuesta: B no puede usar sucursal de A', format($f$insert into mesas(id,tenant_id,sucursal_id,codigo) values (uuid_v7(), %L, %L, 'Z')$f$, :'tb', :'suc_a'), 'foreign key');
select set_config('app.tenant_id', '', false);
select t_err('sin tenant en contexto: falla (fail-closed)', 'select count(*) from sucursales');
select t_err('app_backend no puede saltarse RLS con SET row_security=off', 'set row_security = off; select count(*) from sucursales');
reset role;
select t_ok('superusuario ve ambas (control)', (select count(*) from sucursales) = 2);
set role app_backend;

\echo === 5. Pedidos, items, pagos ===
select set_config('app.tenant_id', :'ta', false);
select uuid_v7() as ped \gset
select t_err('pedido salon sin mesa rechazado', format($f$insert into pedidos(id,tenant_id,sucursal_id,canal,fecha_operativa) values (uuid_v7(),%L,%L,'salon',current_date)$f$, :'ta', :'suc_a'), 'check');
select t_err('pedido con id no v7 rechazado', format($f$insert into pedidos(id,tenant_id,sucursal_id,canal,mesa_id,fecha_operativa) values (gen_random_uuid(),%L,%L,'salon',%L,current_date)$f$, :'ta', :'suc_a', :'mesa_a'), 'check');
select t_err('total incoherente rechazado', format($f$insert into pedidos(id,tenant_id,sucursal_id,canal,mesa_id,fecha_operativa,subtotal,iva,total) values (uuid_v7(),%L,%L,'salon',%L,current_date,10,1.5,99)$f$, :'ta', :'suc_a', :'mesa_a'), 'check');
select t_err('estado de delivery en pedido de salon rechazado', format($f$insert into pedidos(id,tenant_id,sucursal_id,canal,mesa_id,fecha_operativa,estado) values (uuid_v7(),%L,%L,'salon',%L,current_date,'en_camino')$f$, :'ta', :'suc_a', :'mesa_a'), 'check');
select t_run('pedido valido', format($f$insert into pedidos(id,tenant_id,sucursal_id,canal,mesa_id,fecha_operativa,subtotal,iva,total) values (%L,%L,%L,'salon',%L,current_date,10,1.5,11.5)$f$, :'ped', :'ta', :'suc_a', :'mesa_a'));
select t_ok('version inicial 1', (select version from pedidos where id = :'ped') = 1);
update pedidos set estado = 'confirmado' where id = :'ped';
select t_ok('trigger incrementa version a 2', (select version from pedidos where id = :'ped') = 2);
select t_ok('actualizado_en >= creado/recibido', (select actualizado_en >= recibido_en from pedidos where id = :'ped'));
select t_ok('numero pedido 1', app.siguiente_numero_pedido(:'suc_a', current_date) = 1);
select t_ok('numero pedido 2', app.siguiente_numero_pedido(:'suc_a', current_date) = 2);

select uuid_v7() as pago1 \gset
select uuid_v7() as pago2 \gset
select t_run('pago 1 (8.00) ok', format($f$insert into pagos(id,tenant_id,pedido_id,metodo,monto,estado) values (%L,%L,%L,'efectivo',8.00,'capturado')$f$, :'pago1', :'ta', :'ped'));
select t_err('pago 2 (4.00) excede total 11.50', format($f$insert into pagos(id,tenant_id,pedido_id,metodo,monto,estado) values (%L,%L,%L,'efectivo',4.00,'capturado')$f$, :'pago2', :'ta', :'ped'), 'excede');
select t_run('pago 2 (3.50) completa el total', format($f$insert into pagos(id,tenant_id,pedido_id,metodo,monto,estado) values (%L,%L,%L,'tarjeta_datafono',3.50,'capturado')$f$, :'pago2', :'ta', :'ped'));
select t_run('reembolso parcial 2.00', format($f$insert into reembolsos(tenant_id,pago_id,monto,motivo,autorizado_por,estado) select %L,%L,2.00,'error',u.id,'completado' from (select (app.crear_usuario('sup@a.com','Sup','x'))  id) u$f$, :'ta', :'pago1'));
select id as sup from app.buscar_usuario_login('sup@a.com') \gset
select t_err('reembolso que excede el pago', format($f$insert into reembolsos(tenant_id,pago_id,monto,motivo,autorizado_por,estado) values (%L,%L,7.00,'x',%L,'completado')$f$, :'ta', :'pago1', :'sup'), 'excede');
select uuid_v7() as ped2 \gset
insert into pedidos(id,tenant_id,sucursal_id,canal,mesa_id,fecha_operativa,subtotal,total) values (:'ped2',:'ta',:'suc_a','salon',:'mesa_a',current_date,100,100);
select t_run('tras un reembolso parcial de 2.00 se puede cobrar de nuevo esos 2.00 (la suma es NETA)', format($f$insert into pagos(id,tenant_id,pedido_id,metodo,monto,estado) values (uuid_v7(),%L,%L,'efectivo',2.00,'capturado')$f$, :'ta', :'ped'));
select t_err('pero no mas que el neto: 0.01 de mas se rechaza', format($f$insert into pagos(id,tenant_id,pedido_id,metodo,monto,estado) values (uuid_v7(),%L,%L,'efectivo',0.01,'capturado')$f$, :'ta', :'ped'), 'excede');
select t_run('actualizar el estado de un pago reembolsado parcialmente no rompe la suma neta', format($f$update pagos set estado = 'reembolsado_parcial' where id = %L$f$, :'pago1'));
select t_err('pago duplicado misma referencia pasarela', format($f$insert into pagos(id,tenant_id,pedido_id,metodo,monto,pasarela,referencia_ext) values (uuid_v7(),%L,%L,'pasarela',1,'payphone','R1'),(uuid_v7(),%L,%L,'pasarela',1,'payphone','R1')$f$, :'ta', :'ped2', :'ta', :'ped2'), 'duplicate');

\echo === 6. Historial, auditoria, outbox (solo insercion) ===
select t_run('insertar historial', format($f$insert into pedido_historial(tenant_id,pedido_id,estado_anterior,estado_nuevo) values (%L,%L,'creado','confirmado')$f$, :'ta', :'ped'));
select t_err('historial: UPDATE denegado', 'update pedido_historial set motivo = ''x''', 'permission');
select t_err('historial: DELETE denegado', 'delete from pedido_historial', 'permission');
select t_err('cancelar sin motivo rechazado', format($f$insert into pedido_historial(tenant_id,pedido_id,estado_nuevo) values (%L,%L,'cancelado')$f$, :'ta', :'ped'), 'check');
reset role;
select t_err('historial inmutable incluso para el dueno (trigger)', 'update pedido_historial set motivo = ''x''', 'solo insercion');
set role app_backend;
select set_config('app.tenant_id', :'ta', false);
select t_run('auditoria insert', format($f$insert into auditoria(tenant_id,accion,entidad) values (%L,'pago.anular','pagos')$f$, :'ta'));
select t_err('auditoria UPDATE denegado', 'update auditoria set accion = ''x''', 'permission');
select t_ok('emitir_evento devuelve id', app.emitir_evento('PedidoPagado','pedido', :'ped'::uuid, '{"x":1}'::jsonb) is not null);
select t_err('app_backend no puede actualizar outbox', 'update outbox set publicado_en = now()', 'permission');

\echo === 7. Secuencial SRI y comprobantes ===
select t_ok('secuencial 1', app.siguiente_secuencial(:'suc_a','01','pruebas') = '000000001');
select t_ok('secuencial 2', app.siguiente_secuencial(:'suc_a','01','pruebas') = '000000002');
select t_ok('secuencial independiente por ambiente', app.siguiente_secuencial(:'suc_a','01','produccion') = '000000001');
select t_run('secuencial: rollback no deja huecos', format($f$do $d$ declare s text; begin begin perform app.siguiente_secuencial(%L,'04','pruebas'); raise exception 'rollback'; exception when others then null; end; s := app.siguiente_secuencial(%L,'04','pruebas'); if s <> '000000001' then raise exception 'hueco %%', s; end if; end $d$$f$, :'suc_a', :'suc_a'));
select app.siguiente_secuencial(:'suc_a','01','pruebas') as sec \gset
select t_run('insertar factura', format($f$insert into comprobantes(tenant_id,sucursal_id,pedido_id,tipo,ambiente,secuencial,clave_acceso,fecha_emision,receptor_tipo_identificacion,receptor_identificacion,receptor_razon_social,subtotal_sin_impuestos,total_iva,importe_total) values (%L,%L,%L,'01','pruebas',%L,app.generar_clave_acceso(current_date,'01','1790000000001','pruebas','001','001',%L),current_date,'07','9999999999999','CONSUMIDOR FINAL',10,1.5,11.5)$f$, :'ta', :'suc_a', :'ped', :'sec', :'sec'));
select t_err('secuencial duplicado rechazado', format($f$insert into comprobantes(tenant_id,sucursal_id,pedido_id,tipo,ambiente,secuencial,clave_acceso,fecha_emision,receptor_tipo_identificacion,receptor_identificacion,receptor_razon_social,subtotal_sin_impuestos,total_iva,importe_total) values (%L,%L,%L,'01','pruebas',%L,'%s',current_date,'07','9999999999999','CF',1,0,1)$f$, :'ta', :'suc_a', :'ped', :'sec', repeat('1',49)), 'duplicate');
select t_err('nota de credito sin factura de referencia', format($f$insert into comprobantes(tenant_id,sucursal_id,pedido_id,tipo,ambiente,secuencial,clave_acceso,fecha_emision,receptor_tipo_identificacion,receptor_identificacion,receptor_razon_social,subtotal_sin_impuestos,total_iva,importe_total) values (%L,%L,%L,'04','pruebas','000000009','%s',current_date,'07','9999999999999','CF',1,0,1)$f$, :'ta', :'suc_a', :'ped', repeat('2',49)), 'check');
select id as factura_id from comprobantes where tipo = '01' limit 1 \gset
select t_run('una nota de credito vinculada a su factura', format($f$insert into comprobantes(tenant_id,sucursal_id,pedido_id,tipo,comprobante_modificado_id,motivo_modificacion,ambiente,secuencial,clave_acceso,fecha_emision,receptor_tipo_identificacion,receptor_identificacion,receptor_razon_social,subtotal_sin_impuestos,total_iva,importe_total) values (%L,%L,%L,'04',%L,'Devolucion total','pruebas','000000001','%s',current_date,'07','9999999999999','CF',10,1.5,11.5)$f$, :'ta', :'suc_a', :'ped', :'factura_id', repeat('3',49)));
select t_err('una factura no admite dos notas de credito vigentes', format($f$insert into comprobantes(tenant_id,sucursal_id,pedido_id,tipo,comprobante_modificado_id,motivo_modificacion,ambiente,secuencial,clave_acceso,fecha_emision,receptor_tipo_identificacion,receptor_identificacion,receptor_razon_social,subtotal_sin_impuestos,total_iva,importe_total) values (%L,%L,%L,'04',%L,'Otra','pruebas','000000002','%s',current_date,'07','9999999999999','CF',10,1.5,11.5)$f$, :'ta', :'suc_a', :'ped', :'factura_id', repeat('4',49)), 'duplicate');
select t_err('autorizado sin numero de autorizacion', format($f$update comprobantes set estado_sri='autorizado' where tenant_id=%L$f$, :'ta'), 'check');

\echo === 8. Sync idempotente ===
select uuid_v7() as disp \gset
insert into dispositivos(id,tenant_id,sucursal_id,tipo,nombre) values (:'disp', :'ta', :'suc_a', 'pos', 'POS 1');
select app.crear_usuario('mesero@a.com','Mesero','hash') as usr \gset
select t_ok('registrar accion nueva = true', app.registrar_sync_accion(:'accion', :'disp', :'usr', 1, 'crear_pedido', '{}'::jsonb, null, now()));
select t_ok('registrar accion repetida = false', not app.registrar_sync_accion(:'accion', :'disp', :'usr', 1, 'crear_pedido', '{}'::jsonb, null, now()));
select t_run('completar resultado permitido', format($f$update sync_acciones set resultado='fusionada', motivo='ok' where accion_id=%L$f$, :'accion'));
select t_err('payload inmutable para app_backend (permiso por columna)', format($f$update sync_acciones set payload='{"a":1}' where accion_id=%L$f$, :'accion'), 'permission');
reset role;
select t_err('payload inmutable incluso para el dueno (trigger)', format($f$update sync_acciones set payload='{"a":1}' where accion_id=%L$f$, :'accion'), 'solo se pueden');
select t_err('DELETE bloqueado incluso para el dueno (trigger)', 'delete from sync_acciones', 'no admite');
set role app_backend;
select set_config('app.tenant_id', :'ta', false);
select t_err('DELETE denegado en sync', 'delete from sync_acciones', 'permission');
select t_err('accion_id no v7 rechazado', format($f$insert into sync_acciones(accion_id,fecha_particion,tenant_id,dispositivo_id,usuario_id,orden_local,tipo,payload) values (gen_random_uuid(),current_date,%L,%L,%L,2,'x','{}')$f$, :'ta', :'disp', :'usr'), 'check');

\echo === 9. Turnos de caja ===
select t_run('abrir turno', format($f$insert into turnos_caja(tenant_id,sucursal_id,usuario_id,dispositivo_id,monto_apertura) values (%L,%L,%L,%L,50)$f$, :'ta', :'suc_a', :'usr', :'disp'));
select t_err('segundo turno abierto mismo cajero/dispositivo', format($f$insert into turnos_caja(tenant_id,sucursal_id,usuario_id,dispositivo_id,monto_apertura) values (%L,%L,%L,%L,10)$f$, :'ta', :'suc_a', :'usr', :'disp'), 'duplicate');

\echo === 10. Usuarios y funciones de auth ===
select t_ok('buscar_usuario_login encuentra', (select count(*) from app.buscar_usuario_login('MESERO@a.com')) = 1);
select t_ok('usuario nuevo no esta bloqueado', (select not bloqueado from app.buscar_usuario_login('mesero@a.com')));
select count(*) from (select app.registrar_login_fallido(:'usr', 5, 15) from generate_series(1, 4)) x \gset
select t_ok('4 fallos no bloquean la cuenta', (select not bloqueado from app.buscar_usuario_login('mesero@a.com')));
select app.registrar_login_fallido(:'usr', 5, 15) as fin_bloqueo \gset
select t_ok('el 5o fallo bloquea y devuelve el fin del bloqueo', :'fin_bloqueo' <> '');
select t_ok('la cuenta figura bloqueada', (select bloqueado from app.buscar_usuario_login('mesero@a.com')));
select app.registrar_login_exitoso(:'usr');
select t_ok('un login correcto levanta el bloqueo', (select not bloqueado from app.buscar_usuario_login('mesero@a.com')));
select t_run('sin membresia en el tenant no se puede tocar el contador de login de un usuario (RLS: 0 filas)', format($f$do $d$ declare n int; begin update usuarios set login_intentos = 0 where id = %L; get diagnostics n = row_count; if n <> 0 then raise exception 'afecto %%', n; end if; end $d$$f$, :'usr'));
select t_err('email duplicado rechazado', $$select app.crear_usuario('mesero@a.com','Otro','h')$$, 'duplicate');
select t_ok('usuario sin membresia no visible por RLS', (select count(*) from usuarios) = 0);
insert into membresias(tenant_id,usuario_id,rol) values (:'ta', :'usr', 'mesero');
select t_ok('usuario con membresia visible en su tenant', (select count(*) from usuarios where id = :'usr') = 1);
select set_config('app.tenant_id', :'tb', false);
select t_ok('...pero invisible para el tenant B', (select count(*) from usuarios where id = :'usr') = 0);
select t_ok('membresias_de_usuario cruza tenants', (select count(*) from app.membresias_de_usuario(:'usr')) = 1);
select t_err('app_backend no puede insertar usuarios directo', $$insert into usuarios(email,nombre) values ('x@x.com','x')$$, 'permission');

\echo === 10b. Cola de trabajos (jobs) ===
select set_config('app.tenant_id', :'ta', false);
select t_run('encolar un job', format($f$insert into jobs(tenant_id, cola, tipo, clave_unica) values (%L, 'sri', 'sri.emitir', 'k1')$f$, :'ta'));
select t_err('clave_unica duplicada en la misma cola y tenant rechazada', format($f$insert into jobs(tenant_id, cola, tipo, clave_unica) values (%L, 'sri', 'sri.emitir', 'k1')$f$, :'ta'), 'duplicate');
select t_run('la misma clave_unica en otra cola si se permite', format($f$insert into jobs(tenant_id, cola, tipo, clave_unica) values (%L, 'notificaciones', 'x', 'k1')$f$, :'ta'));
select t_err('un job en_proceso exige lease', format($f$insert into jobs(tenant_id, cola, tipo, estado) values (%L, 'sri', 'x', 'en_proceso')$f$, :'ta'), 'check');
select t_err('un job completado exige completado_en', format($f$insert into jobs(tenant_id, cola, tipo, estado) values (%L, 'sri', 'x', 'completado')$f$, :'ta'), 'check');
select t_ok('el tenant A ve sus jobs', (select count(*) from jobs) = 2);
select set_config('app.tenant_id', :'tb', false);
select t_ok('el tenant B no ve los jobs de A', (select count(*) from jobs) = 0);
select t_err('el tenant B no puede crear jobs para A', format($f$insert into jobs(tenant_id, cola, tipo) values (%L, 'sri', 'x')$f$, :'ta'), 'row-level security');

\echo === 11. Scheduler (cross-tenant, minimo privilegio) ===
reset role;
set role app_scheduler;
select t_ok('scheduler ve los jobs de todos los tenants', (select count(*) from jobs) >= 2);
select t_run('scheduler reclama un job (lease)', $$update jobs set estado = 'en_proceso', intentos = intentos + 1, bloqueado_hasta = now() + interval '1 minute', bloqueado_por = 'w1' where id = (select id from jobs where estado = 'pendiente' order by creado_en limit 1 for update skip locked)$$);
select t_run('scheduler reprograma un job fallido', $$update jobs set estado = 'pendiente', proximo_intento_en = now() + interval '10 seconds', ultimo_error = 'x', bloqueado_hasta = null, bloqueado_por = null where bloqueado_por = 'w1'$$);
select t_err('scheduler no puede modificar el payload de un job (solo el estado y la reprogramacion)', $$update jobs set payload = '{}'::jsonb$$, 'permission');
select t_err('scheduler no puede borrar jobs', 'delete from jobs', 'permission');
select t_err('scheduler no puede insertar jobs', $$insert into jobs(tenant_id, cola, tipo) select id, 'x', 'x' from tenants limit 1$$, 'permission');
select t_run('scheduler reprograma un evento del outbox', $$update outbox set proximo_intento_en = now() + interval '1 minute' where false$$);
select t_ok('scheduler ve outbox pendiente sin tenant', (select count(*) from outbox where publicado_en is null) >= 1);
select t_run('scheduler marca publicado', $$update outbox set publicado_en = now() where publicado_en is null$$);
select t_err('scheduler no puede leer pedidos', 'select * from pedidos', 'permission');
select t_err('scheduler no puede borrar outbox', 'delete from outbox', 'permission');
reset role;

\echo === 12. Particiones ===
select t_ok('hay particiones de auditoria + default', (select count(*) from pg_inherits where inhparent = 'auditoria'::regclass) >= 5);
select t_ok('hay particiones de outbox semanales', (select count(*) from pg_inherits where inhparent = 'outbox'::regclass) >= 9);
select t_run('mantener_particiones es idempotente', 'select app.mantener_particiones()');
select t_ok('fila de sync cae en particion mensual (no default)', (select count(*) from only sync_acciones_default) = 0);

\echo === 13. RLS habilitado y forzado en todas las tablas con tenant_id ===
select t_ok('tablas con tenant_id sin RLS forzado: ' || coalesce(string_agg(relname, ','), 'ninguna'), count(*) = 0)
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where n.nspname='public' and c.relkind in ('r','p') and not c.relispartition
  and exists (select 1 from pg_attribute a where a.attrelid=c.oid and a.attname='tenant_id' and not a.attisdropped)
  and not (c.relrowsecurity and c.relforcerowsecurity) and c.relname <> 'tarifas_iva';
