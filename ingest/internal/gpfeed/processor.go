// Package gpfeed turns a CelesTrak GP response into raw.gp events, sending
// anything that cannot be decoded or validated to raw.gp.dlq.
package gpfeed

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"time"
	"unicode/utf8"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/dedupe"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/events"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
)

const (
	TopicRawGP    = "raw.gp"
	TopicRawGPDLQ = "raw.gp.dlq"
	epochLayout   = "2006-01-02T15:04:05.999999999"

	kafkaMaxRecordBytes = events.KafkaMaxRecordBytes
	maxDLQPayload       = events.MaxDLQPayload
	// maxEventBytes bounds one raw.gp record, far above a real GP record
	// (about 420 bytes) and well under the Kafka record limit.
	maxEventBytes = 512 << 10
)

type (
	Message   = events.Message
	Publisher = events.Publisher
)

type Processor struct {
	Events     *schema.Validator
	DeadLetter *schema.Validator
	Tracker    *dedupe.Tracker
	Publisher  Publisher
	SourceURL  string
	Now        func() time.Time
}

type envelope struct {
	SchemaVersion int             `json:"schema_version"`
	Source        string          `json:"source"`
	FetchedAt     string          `json:"fetched_at"`
	SourceURL     string          `json:"source_url"`
	GP            json.RawMessage `json:"gp"`
}

type recordID struct {
	id    int64
	epoch string
}

// Process publishes new records and dead letters bad ones in a single batch.
// A returned error means the batch was not published and the caller should
// retry the same body; records are marked seen only after a successful publish.
func (p *Processor) Process(ctx context.Context, body []byte, fetchedAt time.Time) error {
	var records []json.RawMessage
	var msgs []Message
	var fresh []recordID
	inBatch := map[recordID]bool{}

	add := func(key []byte, stage, check, reason string, payload []byte) error {
		m, err := p.deadLetter(key, stage, check, reason, payload, len(payload), false)
		msgs = append(msgs, m)
		return err
	}

	if err := json.Unmarshal(body, &records); err != nil {
		if err := add(nil, "decode", "", "decode response: "+err.Error(), body); err != nil {
			return err
		}
	} else if len(records) == 0 {
		if err := add(nil, "decode", "", "response contained no records", body); err != nil {
			return err
		}
	}

	for _, rec := range records {
		key := bestEffortKey(rec)
		if !utf8.Valid(rec) {
			if err := add(key, "validate", "", "record is not valid UTF-8", rec); err != nil {
				return err
			}
			continue
		}
		if len(rec) > maxEventBytes {
			if err := add(key, "validate", "", fmt.Sprintf("record of %d bytes exceeds the %d byte limit", len(rec), maxEventBytes), rec); err != nil {
				return err
			}
			continue
		}
		check := ""
		value, err := p.envelope(rec, fetchedAt)
		if err == nil {
			if err = p.Events.Validate(value); err != nil {
				check = "schema"
			}
		}
		var rid recordID
		if err == nil {
			rid, err = identity(rec)
		}
		if err != nil {
			if err := add(key, "validate", check, err.Error(), rec); err != nil {
				return err
			}
			continue
		}
		if inBatch[rid] || p.Tracker.IsDuplicate(rid.id, rid.epoch) {
			continue
		}
		inBatch[rid] = true
		msgs = append(msgs, Message{Topic: TopicRawGP, Key: key, Value: value})
		fresh = append(fresh, rid)
	}

	if len(msgs) == 0 {
		return nil
	}
	if err := p.Publisher.Publish(ctx, msgs); err != nil {
		return fmt.Errorf("publish %d messages: %w", len(msgs), err)
	}
	for _, r := range fresh {
		p.Tracker.Record(r.id, r.epoch)
	}
	return nil
}

// RejectBody dead letters a response whose body failed after a 200 status.
// total is the number of bytes received, which may exceed len(prefix); the
// payload is always marked truncated because the body never arrived whole.
func (p *Processor) RejectBody(ctx context.Context, reason string, prefix []byte, total int) error {
	m, err := p.deadLetter(nil, "fetch", "", reason, prefix, total, true)
	if err != nil {
		return err
	}
	if err := p.Publisher.Publish(ctx, []Message{m}); err != nil {
		return fmt.Errorf("publish dead letter: %w", err)
	}
	return nil
}

// envelope wraps a record without re-encoding it, so the gp object carries
// CelesTrak's bytes unchanged (json.Marshal would HTML escape them).
func (p *Processor) envelope(rec json.RawMessage, fetchedAt time.Time) ([]byte, error) {
	return events.MarshalNoEscape(envelope{
		SchemaVersion: 1,
		Source:        "celestrak",
		FetchedAt:     fetchedAt.UTC().Format(time.RFC3339Nano),
		SourceURL:     p.SourceURL,
		GP:            rec,
	})
}

func (p *Processor) deadLetter(key []byte, stage, check, reason string, payload []byte, total int, incomplete bool) (Message, error) {
	return events.DeadLetter(p.DeadLetter, p.Now(), TopicRawGP, p.SourceURL, key, stage, check, reason, payload, total, incomplete)
}

// identity returns the dedupe key. EPOCH is normalized through time parsing so
// that representations differing only in fractional digits compare equal.
func identity(rec []byte) (recordID, error) {
	var r struct {
		ID    json.Number `json:"NORAD_CAT_ID"`
		Epoch string      `json:"EPOCH"`
	}
	dec := json.NewDecoder(bytes.NewReader(rec))
	dec.UseNumber()
	if err := dec.Decode(&r); err != nil {
		return recordID{}, err
	}
	id, err := strconv.ParseInt(r.ID.String(), 10, 64)
	if err != nil {
		return recordID{}, fmt.Errorf("NORAD_CAT_ID %q: %w", r.ID, err)
	}
	t, err := time.Parse(epochLayout, r.Epoch)
	if err != nil {
		return recordID{}, fmt.Errorf("EPOCH %q: %w", r.Epoch, err)
	}
	return recordID{id: id, epoch: t.Format(time.RFC3339Nano)}, nil
}

func bestEffortKey(rec []byte) []byte {
	var r struct {
		ID json.Number `json:"NORAD_CAT_ID"`
	}
	dec := json.NewDecoder(bytes.NewReader(rec))
	dec.UseNumber()
	if dec.Decode(&r) != nil || r.ID == "" {
		return nil
	}
	if _, err := strconv.ParseInt(r.ID.String(), 10, 64); err != nil {
		return nil
	}
	return []byte(r.ID.String())
}
