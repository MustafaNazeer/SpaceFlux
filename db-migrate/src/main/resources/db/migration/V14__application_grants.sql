-- The host part is written here, never taken from a placeholder; db-migrate checks both user names
-- against ^[a-z][a-z0-9_]{0,31}$ before Flyway starts.
-- alert_seq and received_at are left to the database.
GRANT SELECT, INSERT (event_id, kind, schema_version, rules_version, produced_at, source_partition, source_offset, payload)
    ON alert_event TO '${consumer_user}'@'%';
GRANT SELECT, INSERT ON space_weather_event TO '${consumer_user}'@'%';
GRANT SELECT, INSERT, UPDATE ON space_weather_series TO '${consumer_user}'@'%';
GRANT SELECT, INSERT ON close_approach TO '${consumer_user}'@'%';
GRANT SELECT, INSERT ON screening_run TO '${consumer_user}'@'%';
GRANT SELECT, INSERT ON screening_run_approach TO '${consumer_user}'@'%';
GRANT SELECT, INSERT ON screening_run_suppressed TO '${consumer_user}'@'%';
GRANT SELECT, INSERT ON screening_run_rejected TO '${consumer_user}'@'%';
GRANT SELECT, INSERT ON screening_run_not_screened TO '${consumer_user}'@'%';
GRANT SELECT, INSERT, UPDATE ON catalog_object TO '${consumer_user}'@'%';

GRANT SELECT ON alert_event TO '${api_user}'@'%';
GRANT SELECT ON space_weather_event TO '${api_user}'@'%';
GRANT SELECT ON space_weather_series TO '${api_user}'@'%';
GRANT SELECT ON close_approach TO '${api_user}'@'%';
GRANT SELECT ON screening_run TO '${api_user}'@'%';
GRANT SELECT ON screening_run_approach TO '${api_user}'@'%';
GRANT SELECT ON screening_run_suppressed TO '${api_user}'@'%';
GRANT SELECT ON screening_run_rejected TO '${api_user}'@'%';
GRANT SELECT ON screening_run_not_screened TO '${api_user}'@'%';
GRANT SELECT ON catalog_object TO '${api_user}'@'%';
GRANT SELECT ON watchlist_object TO '${api_user}'@'%';
GRANT SELECT, INSERT (event_id, action, principal, note) ON alert_acknowledgement TO '${api_user}'@'%';
