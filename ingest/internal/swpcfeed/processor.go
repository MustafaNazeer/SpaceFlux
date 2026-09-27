// Package swpcfeed turns NOAA SWPC JSON products into raw.swpc events (ADR
// 0005), sending anything that cannot be decoded or validated to raw.swpc.dlq.
package swpcfeed

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"
	"unicode/utf8"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/dedupe"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/events"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
)

const (
	TopicRawSWPC    = "raw.swpc"
	TopicRawSWPCDLQ = "raw.swpc.dlq"
	// maxRecordBytes bounds one SWPC record; real records are under 1 KiB
	// except alert messages, which are a few KiB.
	maxRecordBytes = 512 << 10
)

// Product is one polled SWPC file. The ID is the Kafka key for every record.
type Product struct {
	ID       string
	Path     string
	identity []string
	// allowEmpty marks products where an empty list is a normal state rather
	// than a sign the feed broke.
	allowEmpty bool
}

var Products = []Product{
	{ID: "swpc.kp", Path: "/products/noaa-planetary-k-index.json", identity: []string{"time_tag", "Kp"}},
	{ID: "swpc.goes.xrays", Path: "/json/goes/primary/xrays-6-hour.json", identity: []string{"time_tag", "satellite", "energy"}},
	{ID: "swpc.goes.protons", Path: "/json/goes/primary/integral-protons-6-hour.json", identity: []string{"time_tag", "satellite", "energy"}},
	{ID: "swpc.alerts", Path: "/products/alerts.json", identity: []string{"product_id", "issue_datetime"}, allowEmpty: true},
}

func ProductByID(id string) (Product, bool) {
	for _, p := range Products {
		if p.ID == id {
			return p, true
		}
	}
	return Product{}, false
}

type Processor struct {
	Product    Product
	SourceURL  string
	Events     *schema.Validator
	DeadLetter *schema.Validator
	Publisher  events.Publisher
	Now        func() time.Time
	window     *dedupe.Window[string]
	// lastBadBody is the hash of the last body dead lettered whole, so an
	// unchanged broken file is not dead lettered on every poll.
	lastBadBody string
}

func NewProcessor(p Product, baseURL string, ev, dl *schema.Validator, pub events.Publisher, now func() time.Time) *Processor {
	return &Processor{
		Product:    p,
		SourceURL:  baseURL + p.Path,
		Events:     ev,
		DeadLetter: dl,
		Publisher:  pub,
		Now:        now,
		window:     dedupe.NewWindow[string](),
	}
}

// Process publishes records absent from the previous successful response and
// dead letters bad ones in one batch. A returned error means nothing was
// published and the window is unchanged, so the caller retries the same body.
func (p *Processor) Process(ctx context.Context, body []byte, fetchedAt time.Time) error {
	var records []json.RawMessage
	var msgs []events.Message
	var seen []string
	inBatch := map[string]bool{}

	add := func(stage, reason string, payload []byte) error {
		m, err := p.deadLetter(stage, reason, payload, len(payload), false)
		msgs = append(msgs, m)
		return err
	}
	// A bad record stays in the sliding window for hours; dead letter it only
	// when it first appears, tracked by a hash of its bytes.
	addRecord := func(reason string, rec []byte) error {
		sum := sha256.Sum256(rec)
		id := "invalid:" + hex.EncodeToString(sum[:])
		seen = append(seen, id)
		if inBatch[id] || !p.window.IsNew(id) {
			return nil
		}
		inBatch[id] = true
		return add("validate", reason, rec)
	}

	decodeErr := json.Unmarshal(body, &records)
	if decodeErr == nil && records == nil {
		decodeErr = errors.New("response is JSON null, not a list")
	}
	if decodeErr == nil && len(records) == 0 && p.Product.allowEmpty {
		// Keep the window: a transient empty list must not cause every record
		// to be republished when the list returns.
		p.lastBadBody = ""
		return nil
	}
	if decodeErr != nil || len(records) == 0 {
		sum := sha256.Sum256(body)
		h := hex.EncodeToString(sum[:])
		if h == p.lastBadBody {
			return nil
		}
		reason := "response contained no records"
		if decodeErr != nil {
			reason = "decode response: " + decodeErr.Error()
		}
		if err := add("decode", reason, body); err != nil {
			return err
		}
		if err := p.Publisher.Publish(ctx, msgs); err != nil {
			return fmt.Errorf("publish dead letter: %w", err)
		}
		p.lastBadBody = h
		return nil
	}
	p.lastBadBody = ""

	for _, rec := range records {
		if !utf8.Valid(rec) {
			if err := addRecord("record is not valid UTF-8", rec); err != nil {
				return err
			}
			continue
		}
		if len(rec) > maxRecordBytes {
			if err := addRecord(fmt.Sprintf("record of %d bytes exceeds the %d byte limit", len(rec), maxRecordBytes), rec); err != nil {
				return err
			}
			continue
		}
		value, err := p.envelope(rec, fetchedAt)
		if err == nil {
			err = p.Events.Validate(value)
		}
		var id string
		if err == nil {
			id, err = identity(rec, p.Product.identity)
		}
		if err != nil {
			if err := addRecord(err.Error(), rec); err != nil {
				return err
			}
			continue
		}
		seen = append(seen, id)
		if inBatch[id] || !p.window.IsNew(id) {
			continue
		}
		inBatch[id] = true
		msgs = append(msgs, events.Message{Topic: TopicRawSWPC, Key: []byte(p.Product.ID), Value: value})
	}

	if len(msgs) > 0 {
		if err := p.Publisher.Publish(ctx, msgs); err != nil {
			return fmt.Errorf("publish %d messages: %w", len(msgs), err)
		}
	}
	p.window.Commit(seen)
	return nil
}

// RejectBody dead letters a response whose body failed after a 200 status.
func (p *Processor) RejectBody(ctx context.Context, reason string, prefix []byte, total int) error {
	m, err := p.deadLetter("fetch", reason, prefix, total, true)
	if err != nil {
		return err
	}
	if err := p.Publisher.Publish(ctx, []events.Message{m}); err != nil {
		return fmt.Errorf("publish dead letter: %w", err)
	}
	return nil
}

func (p *Processor) deadLetter(stage, reason string, payload []byte, total int, incomplete bool) (events.Message, error) {
	return events.DeadLetter(p.DeadLetter, p.Now(), TopicRawSWPC, p.SourceURL, []byte(p.Product.ID), stage, reason, payload, total, incomplete)
}

// envelope splices the record in verbatim: encoding it as a json.RawMessage
// would compact the whitespace SWPC's files contain.
func (p *Processor) envelope(rec json.RawMessage, fetchedAt time.Time) ([]byte, error) {
	head, err := events.MarshalNoEscape(struct {
		SchemaVersion int    `json:"schema_version"`
		Source        string `json:"source"`
		Product       string `json:"product"`
		FetchedAt     string `json:"fetched_at"`
		SourceURL     string `json:"source_url"`
	}{1, "swpc", p.Product.ID, fetchedAt.UTC().Format(time.RFC3339Nano), p.SourceURL})
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	buf.Write(head[:len(head)-1])
	buf.WriteString(`,"record":`)
	buf.Write(bytes.TrimSpace(rec))
	buf.WriteByte('}')
	return buf.Bytes(), nil
}

// identity joins the identity fields as they appear in the record. Values are
// compared as sent, since SWPC keeps each file's formats fixed.
func identity(rec []byte, fields []string) (string, error) {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(rec, &m); err != nil {
		return "", err
	}
	var buf bytes.Buffer
	for i, f := range fields {
		v, ok := m[f]
		if !ok {
			return "", errors.New("missing identity field " + f)
		}
		if i > 0 {
			buf.WriteByte('|')
		}
		buf.Write(v)
	}
	return buf.String(), nil
}
