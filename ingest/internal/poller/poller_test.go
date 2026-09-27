package poller

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/backoff"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/celestrak"
)

var errNetwork = errors.New("dial tcp: connection refused")
var errBroker = errors.New("broker unavailable")

type fetchResult struct {
	body   []byte
	err    error
	cancel bool
}

// harness scripts fetch and process results and records every sleep.
type harness struct {
	fetches   []fetchResult
	processes []error
	fetchN    int
	processN  int
	processed [][]byte
	sleeps    []time.Duration
	rejected  []error
	rejects   []error
	rejectN   int
	stopAfter int
	cancel    context.CancelFunc
}

func (h *harness) fetch(ctx context.Context) ([]byte, error) {
	r := h.fetches[h.fetchN]
	h.fetchN++
	if r.cancel {
		h.cancel()
		return nil, ctx.Err()
	}
	return r.body, r.err
}

func (h *harness) process(ctx context.Context, body []byte) error {
	h.processed = append(h.processed, body)
	var err error
	if h.processN < len(h.processes) {
		err = h.processes[h.processN]
	}
	h.processN++
	return err
}

func (h *harness) reject(ctx context.Context, err error) error {
	h.rejected = append(h.rejected, err)
	var rerr error
	if h.rejectN < len(h.rejects) {
		rerr = h.rejects[h.rejectN]
	}
	h.rejectN++
	return rerr
}

func (h *harness) sleep(ctx context.Context, d time.Duration) error {
	h.sleeps = append(h.sleeps, d)
	if len(h.sleeps) >= h.stopAfter {
		h.cancel()
		return ctx.Err()
	}
	return nil
}

func run(t *testing.T, h *harness) error {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	h.cancel = cancel
	p := &Poller{
		Interval: 2 * time.Hour,
		Backoff:  backoff.Policy{Base: time.Second, Max: 2 * time.Hour},
		Fetch:    h.fetch,
		Process:  h.process,
		Reject:   h.reject,
		IsHalt:   celestrak.IsHalt,
		IsBadBody: func(err error) bool {
			var be *celestrak.BodyError
			return errors.As(err, &be)
		},
		Sleep: h.sleep,
		Rand:  func() float64 { return 0.5 },
	}
	return p.Run(ctx)
}

func TestSuccessWaitsFullInterval(t *testing.T) {
	h := &harness{fetches: []fetchResult{{body: []byte("a")}, {body: []byte("b")}}, stopAfter: 2}
	err := run(t, h)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("Run = %v, want context.Canceled", err)
	}
	if h.fetchN != 2 || len(h.processed) != 2 {
		t.Fatalf("fetches=%d processed=%d, want 2 and 2", h.fetchN, len(h.processed))
	}
	for i, d := range h.sleeps {
		if d != 2*time.Hour {
			t.Fatalf("sleep %d = %v, want 2h", i, d)
		}
	}
}

func TestHTTPErrorHaltsWithoutRetry(t *testing.T) {
	halt := &celestrak.StatusError{Code: 403, Body: "blocked"}
	h := &harness{fetches: []fetchResult{{err: halt}}, stopAfter: 99}
	err := run(t, h)
	if !errors.Is(err, ErrHalted) || !errors.Is(err, halt) {
		t.Fatalf("Run = %v, want ErrHalted wrapping the status error", err)
	}
	if h.fetchN != 1 || len(h.sleeps) != 0 || len(h.processed) != 0 {
		t.Fatalf("fetches=%d sleeps=%d processed=%d, want 1, 0, 0", h.fetchN, len(h.sleeps), len(h.processed))
	}
}

func TestNetworkErrorBacksOffThenRecovers(t *testing.T) {
	h := &harness{
		fetches:   []fetchResult{{err: errNetwork}, {err: errNetwork}, {body: []byte("ok")}},
		stopAfter: 3,
	}
	if err := run(t, h); !errors.Is(err, context.Canceled) {
		t.Fatalf("Run = %v", err)
	}
	want := []time.Duration{500 * time.Millisecond, time.Second, 2 * time.Hour}
	if len(h.sleeps) != len(want) {
		t.Fatalf("sleeps = %v, want %v", h.sleeps, want)
	}
	for i := range want {
		if h.sleeps[i] != want[i] {
			t.Fatalf("sleeps = %v, want %v", h.sleeps, want)
		}
	}
	if len(h.processed) != 1 {
		t.Fatalf("processed %d bodies, want 1", len(h.processed))
	}
}

func TestBackoffResetsAfterSuccess(t *testing.T) {
	h := &harness{
		fetches:   []fetchResult{{err: errNetwork}, {body: []byte("ok")}, {err: errNetwork}},
		stopAfter: 3,
	}
	run(t, h)
	if h.sleeps[2] != 500*time.Millisecond {
		t.Fatalf("sleeps = %v, want attempt counter reset after success", h.sleeps)
	}
}

func TestProcessFailureRetriesSameBodyWithoutRefetch(t *testing.T) {
	h := &harness{
		fetches:   []fetchResult{{body: []byte("payload")}},
		processes: []error{errBroker, errBroker, nil},
		stopAfter: 3,
	}
	run(t, h)
	if h.fetchN != 1 {
		t.Fatalf("fetched %d times, want 1: publish retries must not hit CelesTrak again", h.fetchN)
	}
	if len(h.processed) != 3 {
		t.Fatalf("processed %d times, want 3", len(h.processed))
	}
	for _, b := range h.processed {
		if string(b) != "payload" {
			t.Fatalf("processed %q, want the original body", b)
		}
	}
	want := []time.Duration{500 * time.Millisecond, time.Second, 2 * time.Hour}
	for i := range want {
		if h.sleeps[i] != want[i] {
			t.Fatalf("sleeps = %v, want %v", h.sleeps, want)
		}
	}
}

func TestCancelledContextStopsBeforeFetch(t *testing.T) {
	h := &harness{}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	p := &Poller{Interval: 2 * time.Hour, Fetch: h.fetch, Process: h.process, Reject: h.reject, IsHalt: celestrak.IsHalt, IsBadBody: func(error) bool { return false }, Sleep: h.sleep}
	if err := p.Run(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("Run = %v, want context.Canceled", err)
	}
	if h.fetchN != 0 {
		t.Fatal("fetched after cancellation")
	}
}

func TestCancellationDuringFetchIsNotRetried(t *testing.T) {
	h := &harness{fetches: []fetchResult{{cancel: true}}, stopAfter: 99}
	if err := run(t, h); !errors.Is(err, context.Canceled) {
		t.Fatalf("Run = %v, want context.Canceled", err)
	}
	if len(h.sleeps) != 0 {
		t.Fatalf("slept %v after cancellation", h.sleeps)
	}
}

func TestSleepContextHonorsCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	go cancel()
	start := time.Now()
	if err := SleepContext(ctx, time.Hour); !errors.Is(err, context.Canceled) {
		t.Fatalf("SleepContext = %v", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("SleepContext did not return promptly on cancel")
	}
}

func TestBadBodyIsRejectedThenWaitsFullInterval(t *testing.T) {
	bad := &celestrak.BodyError{Err: celestrak.ErrBodyTooLarge, Prefix: []byte("[{"), Total: 9 << 20}
	h := &harness{fetches: []fetchResult{{err: bad}, {body: []byte("ok")}}, stopAfter: 2}
	if err := run(t, h); !errors.Is(err, context.Canceled) {
		t.Fatalf("Run = %v", err)
	}
	if len(h.rejected) != 1 || !errors.Is(h.rejected[0], celestrak.ErrBodyTooLarge) {
		t.Fatalf("rejected = %v, want the body error once", h.rejected)
	}
	if h.sleeps[0] != 2*time.Hour {
		t.Fatalf("slept %v after a bad 200 body, want the full interval: a quick refetch breaks one download per update", h.sleeps[0])
	}
	if len(h.processed) != 1 || h.fetchN != 2 {
		t.Fatalf("processed=%d fetches=%d, want 1 and 2", len(h.processed), h.fetchN)
	}
}

func TestRejectFailureRetriesRejectWithoutRefetch(t *testing.T) {
	bad := &celestrak.BodyError{Err: errors.New("unexpected EOF")}
	h := &harness{fetches: []fetchResult{{err: bad}}, rejects: []error{errBroker, nil}, stopAfter: 2}
	if err := run(t, h); !errors.Is(err, context.Canceled) {
		t.Fatalf("Run = %v", err)
	}
	if h.fetchN != 1 || h.rejectN != 2 {
		t.Fatalf("fetches=%d rejects=%d, want 1 and 2", h.fetchN, h.rejectN)
	}
	if h.sleeps[0] != 500*time.Millisecond || h.sleeps[1] != 2*time.Hour {
		t.Fatalf("sleeps = %v", h.sleeps)
	}
}
