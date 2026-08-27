-- Effective privileges of isc_pdp_app, measured on the catalogs — NOT read off
-- the GRANT statements. has_*_privilege() accounts for PUBLIC and role
-- inheritance, which is exactly where the TACHE-02 surprise lived (two
-- privileges inherited from PUBLIC appeared in no GRANT).
\set ON_ERROR_STOP on

\echo '--- table privileges on audit.entry (via has_table_privilege) ---'
SELECT p AS privilege, has_table_privilege('isc_pdp_app', 'audit.entry', p) AS effective
FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) p
ORDER BY p;

\echo '--- table privileges on audit.chain_head ---'
SELECT p AS privilege, has_table_privilege('isc_pdp_app', 'audit.chain_head', p) AS effective
FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE']) p
ORDER BY p;

\echo '--- sequence privileges on audit.entry_seq_seq ---'
SELECT p AS privilege, has_sequence_privilege('isc_pdp_app', 'audit.entry_seq_seq', p) AS effective
FROM unnest(ARRAY['USAGE','SELECT','UPDATE']) p
ORDER BY p;

\echo '--- schema privileges on audit ---'
SELECT p AS privilege, has_schema_privilege('isc_pdp_app', 'audit', p) AS effective
FROM unnest(ARRAY['USAGE','CREATE']) p
ORDER BY p;

\echo '--- EXECUTE on every function of schema audit (PUBLIC-inheritance included) ---'
SELECT n.nspname || '.' || p.proname || '(' || pg_get_function_arguments(p.oid) || ')' AS fn,
       p.prosecdef AS security_definer,
       has_function_privilege('isc_pdp_app', p.oid, 'EXECUTE') AS effective_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'audit'
ORDER BY fn;

\echo '--- raw ACLs on audit.entry (aclexplode: shows PUBLIC grants directly) ---'
SELECT COALESCE(g.rolname, 'PUBLIC') AS grantee, a.privilege_type, a.is_grantable
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN LATERAL aclexplode(c.relacl) a
LEFT JOIN pg_roles g ON g.oid = a.grantee
WHERE n.nspname = 'audit' AND c.relname = 'entry'
ORDER BY grantee, a.privilege_type;
