// Package health serves liveness and readiness. A halted feed fails readiness
// only: liveness stays green so an orchestrator never restarts ingest into a
// fresh round of CelesTrak requests (ADR 0004).
package health

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

type State struct {
	ping           func(context.Context) error
	mu             sync.Mutex
	feeds          []string
	halted         map[string]error
	publishFails   map[string]int
	lastPublishErr map[string]error
}

func New(ping func(context.Context) error, feeds ...string) *State {
	return &State{ping: ping, feeds: feeds, halted: map[string]error{}, publishFails: map[string]int{}, lastPublishErr: map[string]error{}}
}

func (s *State) SetHalted(feed string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.halted[feed] = err
}

func (s *State) PublishFailed(feed string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.publishFails[feed]++
	s.lastPublishErr[feed] = err
}

func (s *State) PublishSucceeded(feed string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.publishFails, feed)
	delete(s.lastPublishErr, feed)
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
	body := map[string]string{"kafka": "ok", "publish": "ok"}
	code := http.StatusOK
	if err := s.ping(ctx); err != nil {
		body["kafka"] = err.Error()
		code = http.StatusServiceUnavailable
	}
	s.mu.Lock()
	for _, f := range s.feeds {
		body[f] = "running"
		if err := s.halted[f]; err != nil {
			body[f] = "halted: " + err.Error()
			code = http.StatusServiceUnavailable
		}
	}
	var failing []string
	for _, f := range s.feeds {
		if n := s.publishFails[f]; n > 0 {
			failing = append(failing, fmt.Sprintf("%s: %d consecutive failures: %v", f, n, s.lastPublishErr[f]))
		}
	}
	if len(failing) > 0 {
		body["publish"] = strings.Join(failing, "; ")
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
