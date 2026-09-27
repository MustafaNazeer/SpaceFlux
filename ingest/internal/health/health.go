// Package health serves liveness and readiness. A halted feed fails readiness
// only: liveness stays green so an orchestrator never restarts ingest into a
// fresh round of CelesTrak requests (ADR 0004).
package health

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

type State struct {
	ping           func(context.Context) error
	mu             sync.Mutex
	halted         error
	publishFails   int
	lastPublishErr error
}

func New(ping func(context.Context) error) *State {
	return &State{ping: ping}
}

func (s *State) SetHalted(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.halted = err
}

func (s *State) PublishFailed(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.publishFails++
	s.lastPublishErr = err
}

func (s *State) PublishSucceeded() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.publishFails = 0
	s.lastPublishErr = nil
}

func (s *State) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "alive"})
	})
	mux.HandleFunc("GET /readyz", s.ready)
	return mux
}

func (s *State) ready(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	body := map[string]string{"kafka": "ok", "celestrak": "running", "publish": "ok"}
	code := http.StatusOK
	if err := s.ping(ctx); err != nil {
		body["kafka"] = err.Error()
		code = http.StatusServiceUnavailable
	}
	s.mu.Lock()
	if s.halted != nil {
		body["celestrak"] = "halted: " + s.halted.Error()
		code = http.StatusServiceUnavailable
	}
	if s.publishFails > 0 {
		body["publish"] = fmt.Sprintf("%d consecutive failures: %v", s.publishFails, s.lastPublishErr)
		code = http.StatusServiceUnavailable
	}
	s.mu.Unlock()
	writeJSON(w, code, body)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}
