package health

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func get(t *testing.T, h http.Handler, path string) (int, map[string]any) {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	var body map[string]any
	json.Unmarshal(rec.Body.Bytes(), &body)
	return rec.Code, body
}

func TestReadyWhenBrokerUpAndFeedRunning(t *testing.T) {
	s := New(func(context.Context) error { return nil }, "celestrak", "swpc.kp")
	code, body := get(t, s.Handler(), "/readyz")
	if code != http.StatusOK || body["celestrak"] != "running" {
		t.Fatalf("readyz = %d %v", code, body)
	}
}

func TestNotReadyWhenBrokerDown(t *testing.T) {
	s := New(func(context.Context) error { return errors.New("dial refused") }, "celestrak")
	code, body := get(t, s.Handler(), "/readyz")
	if code != http.StatusServiceUnavailable || body["kafka"] != "dial refused" {
		t.Fatalf("readyz = %d %v", code, body)
	}
}

func TestHaltedFeedFailsReadinessButNotLiveness(t *testing.T) {
	s := New(func(context.Context) error { return nil }, "celestrak", "swpc.kp")
	s.SetHalted("celestrak", errors.New("HTTP 403: blocked"))

	code, body := get(t, s.Handler(), "/readyz")
	if code != http.StatusServiceUnavailable || body["celestrak"] != "halted: HTTP 403: blocked" || body["swpc.kp"] != "running" {
		t.Fatalf("readyz = %d %v", code, body)
	}
	if code, _ := get(t, s.Handler(), "/healthz"); code != http.StatusOK {
		t.Fatalf("healthz = %d, want 200 so the orchestrator does not restart into a request loop", code)
	}
}

func TestPublishFailureStreakFailsReadinessUntilSuccess(t *testing.T) {
	s := New(func(context.Context) error { return nil }, "celestrak", "swpc.kp")
	s.PublishFailed("celestrak", errors.New("record delivery timeout"))
	s.PublishFailed("celestrak", errors.New("record delivery timeout"))
	s.PublishSucceeded("swpc.kp")

	code, body := get(t, s.Handler(), "/readyz")
	if code != http.StatusServiceUnavailable || body["publish"] != "celestrak: 2 consecutive failures: record delivery timeout" {
		t.Fatalf("readyz = %d %v", code, body)
	}

	s.PublishSucceeded("celestrak")
	if code, body := get(t, s.Handler(), "/readyz"); code != http.StatusOK || body["publish"] != "ok" {
		t.Fatalf("readyz after success = %d %v", code, body)
	}
}

func TestResponsesAreNotSniffed(t *testing.T) {
	s := New(func(context.Context) error { return nil }, "celestrak", "swpc.kp")
	rec := httptest.NewRecorder()
	s.Handler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/readyz", nil))
	if rec.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatalf("headers = %v", rec.Header())
	}
}
