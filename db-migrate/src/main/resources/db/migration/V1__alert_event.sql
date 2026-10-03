CREATE TABLE alert_event (
    alert_seq BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    kind VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    schema_version SMALLINT UNSIGNED NOT NULL,
    rules_version INT UNSIGNED NOT NULL,
    produced_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    source_partition INT NOT NULL,
    source_offset BIGINT NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    PRIMARY KEY (alert_seq),
    UNIQUE KEY uk_alert_event_event_id (event_id),
    KEY ix_alert_event_kind (kind, alert_seq),
    CONSTRAINT ck_alert_event_kind CHECK (kind IN ('space_weather_level', 'close_approach', 'screening_run')),
    CONSTRAINT ck_alert_event_rules_version CHECK (rules_version >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
