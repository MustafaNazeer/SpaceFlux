-- The recent alerts list shows a space weather event when it enters, changes or leaves a level, restatements and
-- revisions included, and never a refresh. listed marks those rows so the list reads them from their own index
-- instead of passing every refresh and "none" event on the way. VIRTUAL: the value lives only in the index, and
-- the consumer's INSERT, which names its columns, cannot and need not set it.
ALTER TABLE space_weather_event
    ADD COLUMN listed BOOLEAN AS (trigger_kind <> 'refresh' AND (state = 'level' OR previous_state <=> 'level'))
        VIRTUAL NOT NULL,
    ADD KEY ix_space_weather_event_listed (listed, alert_seq);

-- An object's approaches are paged newest first by (time_of_closest_approach, alert_seq). InnoDB already appends
-- the primary key to these two indexes; naming it states the order the query relies on.
ALTER TABLE close_approach
    DROP KEY ix_close_approach_watchlist,
    ADD KEY ix_close_approach_watchlist (watchlist_number, time_of_closest_approach, alert_seq),
    DROP KEY ix_close_approach_other,
    ADD KEY ix_close_approach_other (other_number, time_of_closest_approach, alert_seq);
