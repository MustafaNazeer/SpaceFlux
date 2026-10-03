CREATE TABLE screening_run_approach (
    run_alert_seq BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    approach_event_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (run_alert_seq, position),
    KEY ix_screening_run_approach_event (approach_event_id),
    CONSTRAINT fk_screening_run_approach_run FOREIGN KEY (run_alert_seq) REFERENCES screening_run (alert_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
