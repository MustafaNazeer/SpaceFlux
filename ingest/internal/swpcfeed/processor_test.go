package swpcfeed

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/events"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
)

const (
	testdata   = "../../testdata/swpc/"
	schemasDir = "../../../schemas/"
)

var fetchedAt = time.Date(2026, 9, 27, 16, 30, 37, 0, time.UTC)

type fakePublisher struct {
	msgs []events.Message
	err  error
}

func (f *fakePublisher) Publish(ctx context.Context, msgs []events.Message) error {
	if f.err != nil {
		return f.err
	}
	f.msgs = append(f.msgs, msgs...)
	return nil
}

func (f *fakePublisher) byTopic(topic string) []events.Message {
	var out []events.Message
	for _, m := range f.msgs {
		if m.Topic == topic {
			out = append(out, m)
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

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(testdata + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func newProcessor(t *testing.T, id string, pub events.Publisher) *Processor {
	t.Helper()
	prod, ok := ProductByID(id)
	if !ok {
		t.Fatalf("unknown product %s", id)
	}
	return NewProcessor(prod, "https://services.swpc.noaa.gov", mustLoad(t, "raw.swpc/v1.schema.json"), mustLoad(t, "dlq/v1.schema.json"), pub, func() time.Time { return fetchedAt })
}

func TestProductsMatchChosenFiles(t *testing.T) {
	want := map[string]string{
		"swpc.kp":           "/products/noaa-planetary-k-index.json",
		"swpc.goes.xrays":   "/json/goes/primary/xrays-6-hour.json",
		"swpc.goes.protons": "/json/goes/primary/integral-protons-6-hour.json",
		"swpc.alerts":       "/products/alerts.json",
	}
	if len(Products) != len(want) {
		t.Fatalf("%d products, want %d", len(Products), len(want))
	}
	for _, p := range Products {
		if want[p.ID] != p.Path {
			t.Fatalf("product %s path %q, want %q", p.ID, p.Path, want[p.ID])
		}
	}
}

func TestEveryRealRecordPublishesKeyedByProduct(t *testing.T) {
	tests := []struct {
		id, file string
		want     int
	}{
		{"swpc.kp", "kp.json", 61},
		{"swpc.goes.xrays", "goes-xrays-6-hour.json", 716},
		{"swpc.goes.protons", "goes-integral-protons-6-hour.json", 568},
		{"swpc.alerts", "alerts.json", 68},
	}
	for _, tc := range tests {
		t.Run(tc.id, func(t *testing.T) {
			pub := &fakePublisher{}
			p := newProcessor(t, tc.id, pub)
			body := fixture(t, tc.file)
			if err := p.Process(context.Background(), body, fetchedAt); err != nil {
				t.Fatalf("Process: %v", err)
			}
			evs := pub.byTopic(TopicRawSWPC)
			if len(evs) != tc.want || len(pub.byTopic(TopicRawSWPCDLQ)) != 0 {
				t.Fatalf("raw.swpc=%d dlq=%d, want %d and 0", len(evs), len(pub.byTopic(TopicRawSWPCDLQ)), tc.want)
			}
			for _, m := range evs {
				if string(m.Key) != tc.id {
					t.Fatalf("key %q, want %q", m.Key, tc.id)
				}
				if err := p.Events.Validate(m.Value); err != nil {
					t.Fatalf("event fails schema: %v", err)
				}
				var env struct {
					Product   string          `json:"product"`
					SourceURL string          `json:"source_url"`
					FetchedAt string          `json:"fetched_at"`
					Record    json.RawMessage `json:"record"`
				}
				json.Unmarshal(m.Value, &env)
				if env.Product != tc.id || env.FetchedAt != "2026-09-27T16:30:37Z" || !strings.HasSuffix(env.SourceURL, p.Product.Path) || !strings.Contains(string(body), string(env.Record)) {
					t.Fatalf("envelope %s", m.Value)
				}
			}
		})
	}
}

func TestSlidingWindowPublishesOnlyNewRecords(t *testing.T) {
	var recs []json.RawMessage
	full := fixture(t, "kp.json")
	if err := json.Unmarshal(full, &recs); err != nil {
		t.Fatal(err)
	}
	older, _ := json.Marshal(recs[:len(recs)-1])

	pub := &fakePublisher{}
	p := newProcessor(t, "swpc.kp", pub)
	for _, body := range [][]byte{older, full, full} {
		if err := p.Process(context.Background(), body, fetchedAt); err != nil {
			t.Fatal(err)
		}
	}
	evs := pub.byTopic(TopicRawSWPC)
	if len(evs) != 61 {
		t.Fatalf("published %d, want 60 then 1 then 0", len(evs))
	}
	if !strings.Contains(string(evs[60].Value), string(recs[len(recs)-1])) {
		t.Fatalf("last published event is not the newly appeared record")
	}
}

func TestInvalidRecordDeadLetteredOthersPublish(t *testing.T) {
	tests := []struct {
		id, file, wantReason string
		good                 int
	}{
		{"swpc.kp", "derived/kp-kp-string.json", "Kp", 60},
		{"swpc.kp", "derived/kp-missing-time-tag.json", "time_tag", 60},
		{"swpc.goes.xrays", "derived/goes-xrays-6-hour-missing-satellite.json", "satellite", 715},
	}
	for _, tc := range tests {
		t.Run(tc.file, func(t *testing.T) {
			pub := &fakePublisher{}
			p := newProcessor(t, tc.id, pub)
			if err := p.Process(context.Background(), fixture(t, tc.file), fetchedAt); err != nil {
				t.Fatal(err)
			}
			dlq := pub.byTopic(TopicRawSWPCDLQ)
			if len(pub.byTopic(TopicRawSWPC)) != tc.good || len(dlq) != 1 {
				t.Fatalf("raw.swpc=%d dlq=%d", len(pub.byTopic(TopicRawSWPC)), len(dlq))
			}
			var d struct {
				Stage, Reason, SourceTopic string `json:"-"`
			}
			var raw map[string]any
			json.Unmarshal(dlq[0].Value, &raw)
			d.Stage, d.Reason, d.SourceTopic = raw["stage"].(string), raw["reason"].(string), raw["source_topic"].(string)
			if string(dlq[0].Key) != tc.id || d.Stage != "validate" || d.SourceTopic != TopicRawSWPC || !strings.Contains(d.Reason, tc.wantReason) {
				t.Fatalf("dead letter key=%s %+v", dlq[0].Key, d)
			}
		})
	}
}

func TestUndecodableBodyDeadLetteredWithProductKey(t *testing.T) {
	for _, tc := range []struct{ id, file string }{
		{"swpc.alerts", "derived/alerts-truncated.json"},
		{"swpc.kp", "derived/swpc-empty-array.json"},
		{"swpc.goes.xrays", "derived/swpc-empty-array.json"},
	} {
		pub := &fakePublisher{}
		p := newProcessor(t, tc.id, pub)
		for range 3 {
			if err := p.Process(context.Background(), fixture(t, tc.file), fetchedAt); err != nil {
				t.Fatal(err)
			}
		}
		dlq := pub.byTopic(TopicRawSWPCDLQ)
		if len(dlq) != 1 || string(dlq[0].Key) != tc.id || !strings.Contains(string(dlq[0].Value), `"stage":"decode"`) {
			t.Fatalf("%s %s: %d dead letters across three identical polls, want 1 keyed by product", tc.id, tc.file, len(dlq))
		}
	}
}

func TestEmptyAlertsListIsNotAnError(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, "swpc.alerts", pub)
	if err := p.Process(context.Background(), fixture(t, "derived/swpc-empty-array.json"), fetchedAt); err != nil {
		t.Fatal(err)
	}
	if len(pub.msgs) != 0 {
		t.Fatalf("published %d messages for an empty alerts list, want none", len(pub.msgs))
	}
}

func TestPublishFailureKeepsWindowSoRetryRepublishes(t *testing.T) {
	pub := &fakePublisher{err: errors.New("broker down")}
	p := newProcessor(t, "swpc.kp", pub)
	body := fixture(t, "kp.json")
	if err := p.Process(context.Background(), body, fetchedAt); err == nil {
		t.Fatal("expected publish error")
	}
	pub.err = nil
	if err := p.Process(context.Background(), body, fetchedAt); err != nil {
		t.Fatal(err)
	}
	if n := len(pub.byTopic(TopicRawSWPC)); n != 61 {
		t.Fatalf("retry published %d, want 61", n)
	}
}

func TestRejectBodyDeadLettersAtFetchStage(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, "swpc.goes.xrays", pub)
	if err := p.RejectBody(context.Background(), "unexpected EOF", []byte(`[{"time_tag":`), 13); err != nil {
		t.Fatal(err)
	}
	dlq := pub.byTopic(TopicRawSWPCDLQ)
	if len(dlq) != 1 || string(dlq[0].Key) != "swpc.goes.xrays" || !strings.Contains(string(dlq[0].Value), `"stage":"fetch"`) || !strings.Contains(string(dlq[0].Value), `"payload_truncated":true`) {
		t.Fatalf("dlq = %s", dlq)
	}
}

func TestSameBadRecordDeadLetteredOncePerAppearance(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, "swpc.kp", pub)
	body := fixture(t, "derived/kp-kp-string.json")
	for range 3 {
		if err := p.Process(context.Background(), body, fetchedAt); err != nil {
			t.Fatal(err)
		}
	}
	if n := len(pub.byTopic(TopicRawSWPCDLQ)); n != 1 {
		t.Fatalf("dead lettered %d times across three polls of the same window, want 1", n)
	}
}

func TestGOESPrimarySwitchPublishesBothSatellites(t *testing.T) {
	a := `{"time_tag": "2026-09-27T10:29:00Z", "satellite": 18, "flux": 1e-7, "observed_flux": 1e-7, "electron_correction": 0, "electron_contaminaton": false, "energy": "0.1-0.8nm"}`
	b := strings.Replace(a, `"satellite": 18`, `"satellite": 19`, 1)
	pub := &fakePublisher{}
	p := newProcessor(t, "swpc.goes.xrays", pub)
	if err := p.Process(context.Background(), []byte("["+a+"]"), fetchedAt); err != nil {
		t.Fatal(err)
	}
	if err := p.Process(context.Background(), []byte("["+b+"]"), fetchedAt); err != nil {
		t.Fatal(err)
	}
	if n := len(pub.byTopic(TopicRawSWPC)); n != 2 {
		t.Fatalf("published %d, want 2: satellite is part of the identity", n)
	}
}

func TestDuplicateIdentityWithinOneResponsePublishedOnce(t *testing.T) {
	rec := `{"time_tag": "2026-09-20T00:00:00", "Kp": 2.33, "a_running": 9, "station_count": 8}`
	pub := &fakePublisher{}
	if err := newProcessor(t, "swpc.kp", pub).Process(context.Background(), []byte("["+rec+","+rec+"]"), fetchedAt); err != nil {
		t.Fatal(err)
	}
	if n := len(pub.byTopic(TopicRawSWPC)); n != 1 {
		t.Fatalf("published %d, want 1", n)
	}
}

func TestBadBodyBetweenGoodOnesRepublishesNothing(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, "swpc.kp", pub)
	good := fixture(t, "kp.json")
	for _, body := range [][]byte{good, good[:200], good} {
		if err := p.Process(context.Background(), body, fetchedAt); err != nil {
			t.Fatal(err)
		}
	}
	if n := len(pub.byTopic(TopicRawSWPC)); n != 61 {
		t.Fatalf("published %d events, want 61: a bad body must not reset the window", n)
	}
}

func TestInvalidUTF8RecordDeadLettered(t *testing.T) {
	bad := "{\"time_tag\": \"2026-09-20T00:00:00\", \"Kp\": 2.33, \"note\": \"\xff\xfe\"}"
	good := `{"time_tag": "2026-09-20T03:00:00", "Kp": 1.0}`
	pub := &fakePublisher{}
	if err := newProcessor(t, "swpc.kp", pub).Process(context.Background(), []byte("["+bad+","+good+"]"), fetchedAt); err != nil {
		t.Fatal(err)
	}
	if len(pub.byTopic(TopicRawSWPC)) != 1 || len(pub.byTopic(TopicRawSWPCDLQ)) != 1 {
		t.Fatalf("raw.swpc=%d dlq=%d, want 1 and 1", len(pub.byTopic(TopicRawSWPC)), len(pub.byTopic(TopicRawSWPCDLQ)))
	}
	var d struct {
		Reason, Payload string
		Encoding        string `json:"payload_encoding"`
	}
	if err := json.Unmarshal(pub.byTopic(TopicRawSWPCDLQ)[0].Value, &d); err != nil {
		t.Fatalf("dead letter is not valid JSON: %v", err)
	}
	raw, err := base64.StdEncoding.DecodeString(d.Payload)
	if !strings.Contains(d.Reason, "UTF-8") || d.Encoding != "base64" || err != nil || string(raw) != bad {
		t.Fatalf("dead letter reason=%q encoding=%s: want the original bytes back from base64", d.Reason, d.Encoding)
	}
}

func TestEmptyAlertsKeepsWindowAndNullIsDeadLettered(t *testing.T) {
	pub := &fakePublisher{}
	p := newProcessor(t, "swpc.alerts", pub)
	full := fixture(t, "alerts.json")
	for _, body := range [][]byte{full, []byte("[]"), full} {
		if err := p.Process(context.Background(), body, fetchedAt); err != nil {
			t.Fatal(err)
		}
	}
	if n := len(pub.byTopic(TopicRawSWPC)); n != 68 {
		t.Fatalf("published %d alerts, want 68: a transient empty list must not reset the window", n)
	}
	if err := p.Process(context.Background(), []byte("null"), fetchedAt); err != nil {
		t.Fatal(err)
	}
	if n := len(pub.byTopic(TopicRawSWPCDLQ)); n != 1 {
		t.Fatalf("null body produced %d dead letters, want 1: only a literal empty list is accepted", n)
	}
}
