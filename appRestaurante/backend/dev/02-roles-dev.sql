-- SOLO DESARROLLO. En produccion las credenciales las crea Operaciones y se guardan
-- en el gestor de secretos; nunca se versionan.
--
-- api_login pertenece a app_backend: no es dueno de las tablas ni tiene BYPASSRLS,
-- igual que la aplicacion en produccion (ADR-002).
create role api_login login password 'api' in role app_backend;
