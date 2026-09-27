# ADR 0006: Kp record identity includes the Kp value

* **Status:** accepted
* **Date:** 2026-09-27
* **Supersedes:** the `swpc.kp` identity in [ADR 0005](0005-swpc-polling-and-error-handling.md), decision 4. The rest of ADR 0005 stands.

## Context

ADR 0005 deduplicates each SWPC product against the previous successful response and identifies a Kp record by `time_tag` alone. Its consequences note that a value revised under the same identity would not be republished, and defers the question until revisions are observed.

SWPC does not document whether planetary Kp values are revised after they first appear. One comparison of the live file against a recorded fixture matched on all 61 overlapping records, which shows only that no revision happened in that interval, not that none ever does. [docs/risk/space-weather-scales.md](../risk/space-weather-scales.md) therefore treats Kp values as revisable.

The G scale is driven by Kp alone. If a revision moves a 3 hour interval across a G threshold and the revised record is never published, the storm level the risk engine holds for that interval stays wrong for as long as the record is in the file.

## Decision

1. The `swpc.kp` identity becomes (`time_tag`, `Kp`). A record whose Kp value differs from the previous response for the same `time_tag` is published again. The value is compared as sent, like every other identity field.
2. `a_running` and `station_count` stay out of the identity. They do not feed the G scale, and including them would republish records for changes the risk engine does not use.
3. Consumers treat `time_tag` as the key of a 3 hour interval and the latest record for that `time_tag` on the partition as its current value. Records of one product share one partition (ADR 0005, decision 1), so the latest in partition order is the latest SWPC published.
4. The other products keep their ADR 0005 identities.

## Alternatives considered

* **Keep `time_tag` alone.** Rejected because a revision across a threshold would leave the G level wrong with nothing on the topic to show it.
* **Identity on the whole record.** Publishes on any field change, including `station_count`, which is noise for the G scale.
* **Republish the full file every poll.** Removes the problem but repeats about 60 unchanged records every 5 minutes for a signal that has not been observed to occur.

## Consequences

* A revision appears on `raw.swpc` as a second record with the same `time_tag`, so consumers must not assume one record per interval.
* How a storm alert changes when its interval is revised (raised, lowered, or withdrawn) is part of the storm rules in the risk engine, not this decision.
