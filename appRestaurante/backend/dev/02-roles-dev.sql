-- SOLO DESARROLLO. En produccion las credenciales las crea Operaciones y se guardan
-- en el gestor de secretos; nunca se versionan.
--
-- api_login pertenece a app_backend: no es dueno de las tablas ni tiene BYPASSRLS,
-- igual que la aplicacion en produccion (ADR-002).
create role api_login login password 'api' in role app_backend;

-- sched_login recorre varios restaurantes (relay del outbox y cola de trabajos), por eso necesita BYPASSRLS, pero
-- solo con los privilegios minimos de app_scheduler (outbox y jobs). Lo usa el datasource "scheduler".
--
-- IMPORTANTE: BYPASSRLS es un ATRIBUTO del rol y NO se hereda por pertenecer a otro rol (a diferencia de los
-- privilegios sobre tablas). Por eso se declara aqui en el propio login; si faltara, RLS se le aplicaria y
-- fallaria con 'unrecognized configuration parameter "app.tenant_id"'.
create role sched_login login password 'sched' bypassrls in role app_scheduler;
