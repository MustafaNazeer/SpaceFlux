package gpfeed

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/dedupe"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
)

const (
	testdata   = "../../testdata/celestrak/"
	schemasDir = "../../../schemas/"
	sourceURL  = "https://celestrak.org/NORAD/elements/gp.php?FORMAT=JSON&GROUP=stations"
)

var fetchedAt = time.Date(2026, 9, 27, 8, 57, 39, 0, time.UTC)

type fakePublisher struct {
	batches [][]Message
	err     error
}

func (f *fakePublisher) Publish(ctx context.Context, msgs []Message) error {
	if f.err != nil {
		return f.err
	}
	f.batches = append(f.batches, msgs)
	return nil
}

func (f *fakePublisher) byTopic(topic string) []Message {
	var out []Message
	for _, b := range f.batches {
		for _, m := range b {
			if m.Topic == topic {
				out = append(out, m)
			}
		}
	}
	return out
}

func mustLoad(t *testing.T, name string) *schema.Validator {
	t.Helper()
	v, err := schema.Load(schemasDir + name)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func newProcessor(t *testing.T, pub Publisher) *Processor {
	t.Helper()
	return &Processor{
		Events:     mustLoad(t, "raw.gp/v1.schema.json"),
		DeadLetter: mustLoad(t, "dlq/v1.schema.json"),
		Tracker:    dedupe.NewTracker(),
		Publisher:  pub,
		SourceURL:  sourceURL,
		Now:        func() time.Time { return fetchedAt.Add(time.Second) },
	}
}

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(testdata + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

type dlqEvent struct {
	SourceTopic     string `json:"source_topic"`
	Service         string `json:"service"`
	Stage           string `json:"stage"`
	Check           string `json:"check"`
	Reason          string `json:"reason"`
	Payload         string `json:"payload"`
	PayloadEncoding string `json:"payload_encoding"`
	PayloadBytes    int    `json:"payload_bytes"`
	PayloadTrunc    bool   `json:"payload_truncated"`
	SourceURL       string `json:"source_url"`
}

func decodeDLQ(t *testing.T, m Message) dlqEvent {
	t.Helper()
	var e dlqEvent
	if err := json.Unmarshal(m.Value, &e); err != nil {
		t.Fatal(err)
	}
	return e
}

func TestPublishesEveryRecordOfRealResponse(t *testing.T) {
	body := fixture(t, "gp-stations.json")
	pub := &fakePublisher{}
	p := newProcessor(t, pub)
	if err := p.Process(context.Background(), body, fetchedAt); err != nil {
		t.Fatalf("Process: %v", err)
	}
	events := pub.byTopic(TopicRawGP)
	if len(events) != 22 || len(pub.byTopic(TopicRawGPDLQ)) != 0 {
		t.Fatalf("raw.gp=%d dlq=%d, want 22 and 0", len(events), len(pub.byTopic(TopicRawGPDLQ)))
	}
	var iss *Message
	for i, m := range events {
		if err := p.Events.Validate(m.Value); err != nil {
			t.Fatalf("event %d fails its own schema: %v", i, err)
		}
		if string(m.Key) == "25544" {
			iss = &events[i]
		}
	}
	if iss == nil {
		t.Fatal("no event keyed 25544")
	}
	var env struct {
		SchemaVersion int             `json:"schema_version"`
		Source        string          `json:"source"`
		FetchedAt     string          `json:"fetched_at"`
		SourceURL     string          `json:"source_url"`
		GP            json.RawMessage `json:"gp"`
	}
	if err := json.Unmarshal(iss.Value, &env); err != nil {
		t.Fatal(err)
	}
	if env.SchemaVersion != 1 || env.Source != "celestrak" || env.FetchedAt != "2026-09-27T08:57:39Z" || env.SourceURL != sourceURL {
		t.Fatalf("envelope = %+v", env)
	}
	if !strings.Contains(string(body), string(env.GP)) || !strings.Contains(string(env.GP), `"OBJECT_NAME":"ISS (ZARYA)"`) {
		t.Fatalf("gp record is not the verbatim bytes from the response: %s", env.GP)
	}
}

func TestUnchangedResponseIsNotRepublished(t *testing.T) {
	body := fixture(t, "gp-stations.json")
	pub := &fakePublisher{}
	p := newProcessor(t, pub)
	for _, at := range []time.Time{fetchedAt, fetchedAt.Add(2 * time.Hour)} {
		if err := p.Process(context.Background(), body, at); err != nil {
			t.Fatalf("Process: %v", err)
		}
	}
	if n := len(pub.byTopic(TopicRawGP)); n != 22 {
		t.Fatalf("published %d events across two identical responses, want 22", n)
	}
}

func TestEpochComparedAsTimeNotText(t *testing.T) {
	tr := dedupe.NewTracker()
	ka, err := identity([]byte(`{"NORAD_CAT_ID":25544,"EPOCH":"2026-09-27T04:10:50.460096"}`))
	if err != nil {
		t.Fatal(err)
	}
	kb, err := identity([]byte(`{"NORAD_CAT_ID":25544,"EPOCH":"2026-09-27T04:10:50.4600960"}`))
	if err != nil {
		t.Fatal(err)
	}
	tr.Record(ka.id, ka.epoch)
	if !tr.IsDuplicate(kb.id, kb.epoch) {
		t.Fatalf("epochs %q and %q differ only in trailing zeros but were not deduplicated", ka.epoch, kb.epoch)
	}
}

func TestInvalidRecordGoesToDLQAndOthersPublish(t *testing.T) {
	tests := []struct {
		fixture, wantKey, wantReason string
	}{
		{"derived/gp-stations-missing-norad-cat-id.json", "", "NORAD_CAT_ID"},
		{"derived/gp-stations-mean-motion-string.json", "25544", "MEAN_MOTION"},
	}
	for _, tc := range tests {
		t.Run(tc.fixture, func(t *testing.T) {
			pub := &fakePublisher{}
			p := newProcessor(t, pub)
			if err := p.Process(context.Background(), fixture(t, tc.fixture), fetchedAt); err != nil {
				t.Fatalf("Process: %v", err)
			}
			dlq := pub.byTopic(TopicRawGPDLQ)
			if len(pub.byTopic(TopicRawGP)) != 21 || len(dlq) != 1 {
				t.Fatalf("raw.gp=%d dlq=%d, want 21 and 1", len(pub.byTopic(TopicRawGP)), len(dlq))
			}
			if string(dlq[0].Key) != tc.wantKey {
				t.Fatalf("dlq key = %q, want %q", dlq[0].Key, tc.wantKey)
			}
			if err := p.DeadLetter.Validate(dlq[0].Value); err != nil {
				t.Fatalf("dlq event fails dlq schema: %v", err)
			}
			e := decodeDLQ(t, dlq[0])
			if e.Stage != "validate" || e.Check != "schema" || e.SourceTopic != TopicRawGP || e.Service != "ingest" || !strings.Contains(e.Reason, tc.wantReason) {
				t.Fatalf("dlq event = %+v", e)
			}
			if !strings.Contains(e.Payload, `"OBJECT_NAME":"ISS (ZARYA)"`) || strings.Contains(e.Payload, "CSS (TIANHE)") {
				t.Fatalf("dlq payload should be the single failing record, got %q", e.Payload)
			}
		})
	}
}

func TestUndecodableBodyGoesToDLQWhole(t *testing.T) {
	tests := []struct{ name, fixture, wantReason string }{
		{"truncated", "derived/gp-catnr-25544-truncated.json", "decode"},
		{"empty array", "derived/gp-empty-array.json", "no records"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			body := fixture(t, tc.fixture)
			pub := &fakePublisher{}
			p := newProcessor(t, pub)
			if err := p.Process(context.Background(), body, fetchedAt); err != nil {
				t.Fatalf("Process: %v", err)
			}
			dlq := pub.byTopic(TopicRawGPDLQ)
			if len(dlq) != 1 || len(pub.byTopic(TopicRawGP)) != 0 {
				t.Fatalf("dlq=%d raw.gp=%d, want 1 and 0", len(dlq), len(pub.byTopic(TopicRawGP)))
			}
			if err := p.DeadLetter.Validate(dlq[0].Value); err != nil {
				t.Fatalf("dlq event fails dlq schema: %v", err)
			}
			e := decodeDLQ(t, dlq[0])
			if e.Stage != "decode" || e.Payload != string(body) || e.PayloadBytes != len(body) || e.PayloadEncoding != "utf-8" || e.SourceURL != sourceURL {
				t.Fatalf("dlq event = %+v", e)
			}
			if !strings.Contains(e.Reason, tc.wantReason) {
				t.Fatalf("reason = %q, want mention of %q", e.Reason, tc.wantReason)
			}
		})
	}
}

func TestNonUTF8BodyIsBase64InDLQ(t *testing.T) {
	body := []byte{0xff, 0xfe, '[', '{'}
	pub := &fakePublisher{}
	p := newProcessor(t, pub)
	if err := p.Process(context.Background(), body, fetchedAt); err != nil {
		t.Fatalf("Process: %v", err)
	}
	e := decodeDLQ(t, pub.byTopic(TopicRawGPDLQ)[0])
	got, err := base64.StdEncoding.DecodeString(e.Payload)
	if e.PayloadEncoding != "base64" || err != nil || string(got) != string(body) {
		t.Fatalf("dlq event = %+v", e)
	}
}

func TestPublishFailureIsReturnedAndNothingMarkedSeen(t *testing.T) {
	body := fixture(t, "gp-stations.json")
	pub := &fakePublisher{err: errors.New("broker down")}
	p := newProcessor(t, pub)
	if err := p.Process(context.Background(), body, fetchedAt); err == nil {
		t.Fatal("Process returned nil despite publish failure")
	}
	pub.err = nil
	if err := p.Process(context.Background(), body, fetchedAt); err != nil {
		t.Fatalf("retry: %v", err)
	}
	if n := len(pub.byTopic(TopicRawGP)); n != 22 {
		t.Fatalf("retry published %d, want all 22", n)
	}
}

// issRecord returns the ISS record from the recorded response, byte for byte.
func issRecord(t *testing.T) string {
	t.Helper()
	var recs []json.RawMessage
	if err := json.Unmarshal(fixture(t, "gp-stations.json"), &recs); err != nil {
		t.Fatal(err)
	}
	for _, r := range recs {
		if strings.Contains(string(r), `"NORAD_CAT_ID":25544,`) {
			return string(r)
		}
	}
	t.Fatal("ISS record not in fixture")
	return ""
}

func processOne(t *testing.T, body string) *fakePublisher {
	t.Helper()
	pub := &fakePublisher{}
	if err := newProcessor(t, pub).Process(context.Background(), []byte(body), fetchedAt); err != nil {
		t.Fatalf("Process: %v", err)
	}
	return pub
}

func TestRecordBytesKeptVerbatimWithoutHTMLEscaping(t *testing.T) {
	rec := strings.Replace(issRecord(t), `"ISS (ZARYA)"`, `"R&D <x>"`, 1)
	pub := processOne(t, "["+rec+"]")
	events := pub.byTopic(TopicRawGP)
	if len(events) != 1 || !strings.Contains(string(events[0].Value), rec) {
		t.Fatalf("event does not contain the record verbatim: %s", events)
	}
}

func TestDuplicateWithinOneResponsePublishedOnce(t *testing.T) {
	rec := issRecord(t)
	pub := processOne(t, "["+rec+","+rec+"]")
	if n := len(pub.byTopic(TopicRawGP)); n != 1 {
		t.Fatalf("published %d copies of one element set, want 1", n)
	}
}

func TestImpossibleEpochGoesToDLQ(t *testing.T) {
	rec := strings.Replace(issRecord(t), `"EPOCH":"2026-09-27T04:10:50.460096"`, `"EPOCH":"2026-13-45T00:00:00"`, 1)
	pub := processOne(t, "["+rec+"]")
	dlq := pub.byTopic(TopicRawGPDLQ)
	if len(dlq) != 1 || len(pub.byTopic(TopicRawGP)) != 0 {
		t.Fatalf("dlq=%d raw.gp=%d, want 1 and 0", len(dlq), len(pub.byTopic(TopicRawGP)))
	}
	if e := decodeDLQ(t, dlq[0]); e.Stage != "validate" || !strings.Contains(e.Reason, "EPOCH") {
		t.Fatalf("dlq event = %+v", e)
	}
}

func TestOversizedRecordGoesToDLQTruncated(t *testing.T) {
	rec := strings.Replace(issRecord(t), `"ISS (ZARYA)"`, `"`+strings.Repeat("A", maxEventBytes)+`"`, 1)
	pub := processOne(t, "["+rec+"]")
	dlq := pub.byTopic(TopicRawGPDLQ)
	if len(dlq) != 1 || len(pub.byTopic(TopicRawGP)) != 0 {
		t.Fatalf("dlq=%d raw.gp=%d, want 1 and 0", len(dlq), len(pub.byTopic(TopicRawGP)))
	}
	e := decodeDLQ(t, dlq[0])
	if !strings.Contains(e.Reason, "exceeds") || !e.PayloadTrunc || e.PayloadBytes != len(rec) || len(e.Payload) != maxDLQPayload {
		t.Fatalf("reason=%q truncated=%v bytes=%d payload=%d", e.Reason, e.PayloadTrunc, e.PayloadBytes, len(e.Payload))
	}
	if len(dlq[0].Value) >= kafkaMaxRecordBytes {
		t.Fatalf("dlq record is %d bytes, over the broker default", len(dlq[0].Value))
	}
}

func TestLargeUndecodableBodyIsTruncatedInDLQ(t *testing.T) {
	body := "[" + strings.Repeat("x", 3<<20)
	pub := processOne(t, body)
	dlq := pub.byTopic(TopicRawGPDLQ)
	e := decodeDLQ(t, dlq[0])
	if !e.PayloadTrunc || e.PayloadBytes != len(body) || len(e.Payload) != maxDLQPayload || e.Payload != body[:maxDLQPayload] {
		t.Fatalf("truncated=%v bytes=%d payload=%d", e.PayloadTrunc, e.PayloadBytes, len(e.Payload))
	}
	if len(dlq[0].Value) >= kafkaMaxRecordBytes {
		t.Fatalf("dlq record is %d bytes", len(dlq[0].Value))
	}
}

func TestRejectBodyDeadLettersPrefixAtFetchStage(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, pub)
	prefix := []byte(`[{"OBJECT_NAME":`)
	if err := p.RejectBody(context.Background(), "unexpected EOF", prefix, len(prefix)); err != nil {
		t.Fatalf("RejectBody: %v", err)
	}
	dlq := pub.byTopic(TopicRawGPDLQ)
	if len(dlq) != 1 {
		t.Fatalf("dlq = %d", len(dlq))
	}
	if err := p.DeadLetter.Validate(dlq[0].Value); err != nil {
		t.Fatal(err)
	}
	e := decodeDLQ(t, dlq[0])
	if e.Stage != "fetch" || e.Payload != string(prefix) || e.Reason != "unexpected EOF" || dlq[0].Key != nil {
		t.Fatalf("dlq event = %+v", e)
	}
}

func TestRejectBodyReportsTrueSizeWhenPrefixShorter(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, pub)
	if err := p.RejectBody(context.Background(), "too large", []byte("[{"), 9<<20); err != nil {
		t.Fatal(err)
	}
	e := decodeDLQ(t, pub.byTopic(TopicRawGPDLQ)[0])
	if !e.PayloadTrunc || e.PayloadBytes != 9<<20 {
		t.Fatalf("dlq event = %+v", e)
	}
}

func TestEscapeHeavyPayloadStillFitsOneKafkaRecord(t *testing.T) {
	for _, fill := range []string{"<", "\x01"} {
		body := "[" + strings.Repeat(fill, 3<<20)
		pub := processOne(t, body)
		dlq := pub.byTopic(TopicRawGPDLQ)
		if len(dlq) != 1 {
			t.Fatalf("fill %q: dlq = %d", fill, len(dlq))
		}
		if len(dlq[0].Value) >= kafkaMaxRecordBytes {
			t.Fatalf("fill %q: dead letter is %d bytes, over the %d byte record limit", fill, len(dlq[0].Value), kafkaMaxRecordBytes)
		}
		e := decodeDLQ(t, dlq[0])
		if e.PayloadBytes != len(body) || !e.PayloadTrunc {
			t.Fatalf("fill %q: dlq event = bytes %d truncated %v", fill, e.PayloadBytes, e.PayloadTrunc)
		}
	}
}

func TestTruncationKeepsWholeRunes(t *testing.T) {
	body := "[" + strings.Repeat("é", 2<<20)
	pub := processOne(t, body)
	e := decodeDLQ(t, pub.byTopic(TopicRawGPDLQ)[0])
	if e.PayloadEncoding != "utf-8" || !strings.HasPrefix(body, e.Payload) || len(e.Payload) > maxDLQPayload {
		t.Fatalf("encoding=%s payload %d bytes, want a utf-8 prefix of at most %d", e.PayloadEncoding, len(e.Payload), maxDLQPayload)
	}
}

func TestRejectBodyAlwaysMarksPayloadTruncated(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, pub)
	prefix := []byte(`[{"OBJECT_NAME":`)
	if err := p.RejectBody(context.Background(), "unexpected EOF", prefix, len(prefix)); err != nil {
		t.Fatal(err)
	}
	if e := decodeDLQ(t, pub.byTopic(TopicRawGPDLQ)[0]); !e.PayloadTrunc {
		t.Fatalf("a body that failed in transit is incomplete by definition: %+v", e)
	}
}

func TestInvalidUTF8RecordGoesToDLQ(t *testing.T) {
	rec := strings.Replace(issRecord(t), `"ISS (ZARYA)"`, "\"ISS \xff\"", 1)
	pub := processOne(t, "["+rec+"]")
	dlq := pub.byTopic(TopicRawGPDLQ)
	if len(dlq) != 1 || len(pub.byTopic(TopicRawGP)) != 0 {
		t.Fatalf("dlq=%d raw.gp=%d, want 1 and 0", len(dlq), len(pub.byTopic(TopicRawGP)))
	}
	if e := decodeDLQ(t, dlq[0]); !strings.Contains(e.Reason, "UTF-8") || e.PayloadEncoding != "base64" || e.Check != "" {
		t.Fatalf("dlq event = %+v", e)
	}
}
