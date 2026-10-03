-- A copy of risk-engine/src/main/resources/screening/watchlist.json under the risk engine's rules version.
-- MigrationIntegrationTest fails the build when the rows differ from the file or the version from RULES_VERSION.
INSERT INTO watchlist_object (catalog_number, name, rules_version) VALUES (25544, 'ISS (ZARYA)', 1);
