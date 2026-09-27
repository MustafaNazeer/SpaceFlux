// Package gpfeed turns a CelesTrak GP response into raw.gp events, sending
// anything that cannot be decoded or validated to raw.gp.dlq.
package gpfeed

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/dedupe"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
)

const (
	TopicRawGP    = "raw.gp"
	TopicRawGPDLQ = "raw.gp.dlq"
	service       = "ingest"
	epochLayout   = "2006-01-02T15:04:05.999999999"

	// kafkaMaxRecordBytes is franz-go's default producer batch limit, which a
	// single record may not exceed (kgo.ProducerBatchMaxBytes, 1000012).
	kafkaMaxRecordBytes = 1000012
	// maxEventBytes bounds one raw.gp record, far above a real GP record
	// (about 420 bytes) and well under kafkaMaxRecordBytes.
	maxEventBytes = 512 << 10
	// maxDLQPayload bounds the payload copied into a dead letter. JSON escaping
	// can grow text up to sixfold, so a dead letter over maxDLQRecord falls back
	// to base64, which grows it by only a third.
	maxDLQPayload = 256 << 10
	maxDLQRecord  = 900_000
	maxDLQReason  = 4 << 10
)

type Message struct {
	Topic string
	Key   []byte
	Value []byte
}

type Publisher interface {
	Publish(ctx context.Context, msgs []Message) error
}

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

type deadLetter struct {
	SchemaVersion   int    `json:"schema_version"`
	SourceTopic     string `json:"source_topic"`
	Service         string `json:"service"`
	Stage           string `json:"stage"`
	Reason          string `json:"reason"`
	FailedAt        string `json:"failed_at"`
	SourceURL       string `json:"source_url,omitempty"`
	Payload         string `json:"payload"`
	PayloadEncoding string `json:"payload_encoding"`
	PayloadBytes    int    `json:"payload_bytes"`
	PayloadTrunc    bool   `json:"payload_truncated,omitempty"`
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

	add := func(key []byte, stage, reason string, payload []byte) error {
		m, err := p.deadLetter(key, stage, reason, payload, len(payload), false)
		msgs = append(msgs, m)
		return err
	}

	if err := json.Unmarshal(body, &records); err != nil {
		if err := add(nil, "decode", "decode response: "+err.Error(), body); err != nil {
			return err
		}
	} else if len(records) == 0 {
		if err := add(nil, "decode", "response contained no records", body); err != nil {
			return err
		}
	}

	for _, rec := range records {
		key := bestEffortKey(rec)
		if len(rec) > maxEventBytes {
			if err := add(key, "validate", fmt.Sprintf("record of %d bytes exceeds the %d byte limit", len(rec), maxEventBytes), rec); err != nil {
				return err
			}
			continue
		}
		value, err := p.envelope(rec, fetchedAt)
		if err == nil {
			err = p.Events.Validate(value)
		}
		var rid recordID
		if err == nil {
			rid, err = identity(rec)
		}
		if err != nil {
			if err := add(key, "validate", err.Error(), rec); err != nil {
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
	m, err := p.deadLetter(nil, "fetch", reason, prefix, total, true)
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
	return marshalNoEscape(envelope{
		SchemaVersion: 1,
		Source:        "celestrak",
		FetchedAt:     fetchedAt.UTC().Format(time.RFC3339Nano),
		SourceURL:     p.SourceURL,
		GP:            rec,
	})
}

// deadLetter builds a dead letter validated against its own schema, so a
// broken dead letter surfaces as an error rather than being published.
// incomplete marks payloads that are partial even when total == len(payload).
func (p *Processor) deadLetter(key []byte, stage, reason string, payload []byte, total int, incomplete bool) (Message, error) {
	if len(payload) > maxDLQPayload {
		cut := maxDLQPayload
		for cut > maxDLQPayload-utf8.UTFMax && !utf8.RuneStart(payload[cut]) {
			cut--
		}
		payload = payload[:cut]
	}
	if len(reason) > maxDLQReason {
		reason = strings.ToValidUTF8(reason[:maxDLQReason], "")
	}
	d := deadLetter{
		SchemaVersion:   1,
		SourceTopic:     TopicRawGP,
		Service:         service,
		Stage:           stage,
		Reason:          reason,
		FailedAt:        p.Now().UTC().Format(time.RFC3339Nano),
		SourceURL:       p.SourceURL,
		Payload:         string(payload),
		PayloadEncoding: "utf-8",
		PayloadBytes:    total,
		PayloadTrunc:    incomplete || len(payload) < total,
	}
	value, err := marshalNoEscape(d)
	if err == nil && (!utf8.Valid(payload) || len(value) > maxDLQRecord) {
		d.Payload = base64.StdEncoding.EncodeToString(payload)
		d.PayloadEncoding = "base64"
		value, err = marshalNoEscape(d)
	}
	if err == nil {
		err = p.DeadLetter.Validate(value)
	}
	if err != nil {
		return Message{}, fmt.Errorf("build dead letter: %w", err)
	}
	return Message{Topic: TopicRawGPDLQ, Key: key, Value: value}, nil
}

func marshalNoEscape(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
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
