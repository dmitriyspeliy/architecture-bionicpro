\set ON_ERROR_STOP on

/*
 * Создаём отдельного пользователя для CDC.
 * Пароль передаётся через psql variable и в Git не хранится.
 */
SELECT format(
               'CREATE ROLE %I WITH LOGIN REPLICATION PASSWORD %L',
               :'cdc_user',
               :'cdc_password'
       )
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'cdc_user'
)
\gexec

/*
 * Синхронизируем пароль при повторном запуске init-контейнера.
 */
SELECT format(
               'ALTER ROLE %I WITH LOGIN REPLICATION PASSWORD %L',
               :'cdc_user',
               :'cdc_password'
       )
\gexec

SELECT format(
               'GRANT CONNECT ON DATABASE bionicpro_crm TO %I',
               :'cdc_user'
       )
\gexec

SELECT format(
               'GRANT USAGE ON SCHEMA public TO %I',
               :'cdc_user'
       )
\gexec

SELECT format(
               'GRANT SELECT ON TABLE public.crm_customer TO %I',
               :'cdc_user'
       )
\gexec

/*
 * Полный before-state нужен для корректной обработки UPDATE и DELETE.
 */
ALTER TABLE public.crm_customer
    REPLICA IDENTITY FULL;

/*
 * Publication создаём вручную и ограничиваем только CRM-таблицей.
 */
SELECT
    'CREATE PUBLICATION bionicpro_crm_publication
     FOR TABLE public.crm_customer'
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_publication
    WHERE pubname = 'bionicpro_crm_publication'
)
\gexec

SELECT
    'ALTER PUBLICATION bionicpro_crm_publication
     ADD TABLE public.crm_customer'
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_publication_tables
    WHERE pubname = 'bionicpro_crm_publication'
      AND schemaname = 'public'
      AND tablename = 'crm_customer'
)
\gexec