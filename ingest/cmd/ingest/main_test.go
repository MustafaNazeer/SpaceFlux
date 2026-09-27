package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/config"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/gpfeed"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/health"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/poller"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/swpcfeed"
)

func TestOneHaltedFeedLeavesOthersRunning(t *testing.T) {
	halt := errors.New("HTTP 404: gone")
	var bFetches atomic.Int64
	fast := func(ctx context.Context, d time.Duration) error { return poller.SleepContext(ctx, time.Millisecond) }
	noop := func(context.Context, []byte) error { return nil }
	pollers := map[string]*poller.Poller{
		"a": {Fetch: func(context.Context) ([]byte, error) { return nil, halt }, Process: noop,
			IsHalt: func(err error) bool { return errors.Is(err, halt) }, IsBadBody: func(error) bool { return false }, Sleep: fast},
		"b": {Fetch: func(context.Context) ([]byte, error) { bFetches.Add(1); return []byte("[]"), nil }, Process: noop,
			IsHalt: func(error) bool { return false }, IsBadBody: func(error) bool { return false }, Sleep: fast},
	}
	state := health.New(func(context.Context) error { return nil }, "a", "b")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		runPollers(ctx, slog.New(slog.NewTextHandler(io.Discard, nil)), state, pollers)
		close(done)
	}()

	deadline := time.Now().Add(5 * time.Second)
	var body map[string]string
	for time.Now().Before(deadline) {
		rec := httptest.NewRecorder()
		state.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/readyz", nil))
		json.Unmarshal(rec.Body.Bytes(), &body)
		if body["a"] != "running" && bFetches.Load() > 5 {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	before := bFetches.Load()
	time.Sleep(20 * time.Millisecond)
	if body["a"] != "halted: poller halted: HTTP 404: gone" || body["b"] != "running" || bFetches.Load() <= before {
		t.Fatalf("readyz = %v, b fetches %d then %d: b must keep polling after a halts", body, before, bFetches.Load())
	}

	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("runPollers did not return after cancellation")
	}
}

func TestSWPCBackoffNeverBelowOneMinute(t *testing.T) {
	cfg, err := config.Load(func(k string) string {
		return map[string]string{"KAFKA_BROKERS": "k:9092", "SWPC_INTERVAL": "5m"}[k]
	})
	if err != nil {
		t.Fatal(err)
	}
	p := swpcBackoff(cfg)
	if d := p.Delay(0, func() float64 { return 0.5 }); d != 90*time.Second {
		t.Fatalf("first retry at rnd 0.5 = %v, want 90s: first retries spread over [1m, 2m) so products do not retry in step", d)
	}
	for attempt := range 20 {
		for _, r := range []float64{0, 0.5, 0.999} {
			if d := p.Delay(attempt, func() float64 { return r }); d < time.Minute || d > cfg.SWPCInterval {
				t.Fatalf("attempt %d rnd %v: delay %v outside [1m, %v]", attempt, r, d, cfg.SWPCInterval)
			}
		}
	}
}

func TestComposeProvisionsEveryTopicTheCodePublishes(t *testing.T) {
	b, err := os.ReadFile("../../../deploy/compose.yaml")
	if err != nil {
		t.Fatal(err)
	}
	var line string
	for _, l := range strings.Split(string(b), "\n") {
		if strings.Contains(l, "for t in ") {
			line = l
		}
	}
	have := strings.Fields(strings.TrimSuffix(strings.SplitN(line, "for t in ", 2)[1], "; do"))
	for _, topic := range []string{gpfeed.TopicRawGP, gpfeed.TopicRawGPDLQ, swpcfeed.TopicRawSWPC, swpcfeed.TopicRawSWPCDLQ} {
		if !slices.Contains(have, topic) {
			t.Fatalf("compose topics service creates %v, missing %s", have, topic)
		}
	}
}
