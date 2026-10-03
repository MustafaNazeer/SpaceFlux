CREATE TABLE screening_run_suppressed (
    run_alert_seq BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    watchlist_number INT UNSIGNED NOT NULL,
    other_number INT UNSIGNED NOT NULL,
    watchlist_name VARCHAR(64) NULL,
    other_name VARCHAR(64) NULL,
    mechanism VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    detail TEXT NOT NULL,
    min_separation_m DOUBLE NOT NULL,
    max_separation_m DOUBLE NOT NULL,
    min_separation_at DATETIME(6) NOT NULL,
    stack_entry_may_be_stale BOOLEAN NOT NULL,
    PRIMARY KEY (run_alert_seq, position),
    KEY ix_screening_run_suppressed_watchlist (watchlist_number, run_alert_seq),
    KEY ix_screening_run_suppressed_other (other_number, run_alert_seq),
    CONSTRAINT fk_screening_run_suppressed_run FOREIGN KEY (run_alert_seq) REFERENCES screening_run (alert_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
