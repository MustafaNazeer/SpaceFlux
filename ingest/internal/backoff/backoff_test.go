package backoff

import (
	"testing"
	"time"
)

func TestDelayFullJitter(t *testing.T) {
	p := Policy{Base: time.Second, Max: time.Minute}
	tests := []struct {
		name    string
		attempt int
		rnd     float64
		want    time.Duration
	}{
		{"first attempt upper bound", 0, 0.999999, 999999 * time.Microsecond},
		{"first attempt zero draw", 0, 0, 0},
		{"doubles each attempt", 3, 0.5, 4 * time.Second},
		{"capped at max", 10, 0.5, 30 * time.Second},
		{"huge attempt does not overflow", 200, 0.5, 30 * time.Second},
		{"negative attempt treated as first", -1, 0.5, 500 * time.Millisecond},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := p.Delay(tc.attempt, func() float64 { return tc.rnd })
			if got != tc.want {
				t.Fatalf("Delay(%d, %v) = %v, want %v", tc.attempt, tc.rnd, got, tc.want)
			}
		})
	}
}

func TestDelayStaysWithinBounds(t *testing.T) {
	p := Policy{Base: 500 * time.Millisecond, Max: 20 * time.Second}
	for attempt := 0; attempt < 64; attempt++ {
		d := p.Delay(attempt, nil)
		if d < 0 || d >= p.Max {
			t.Fatalf("attempt %d: delay %v outside [0, %v)", attempt, d, p.Max)
		}
	}
}

// Jitter spreads over [Min, ceiling) so pollers retrying a shared outage do not
// all land on the floor together.
func TestDelayNeverBelowMin(t *testing.T) {
	p := Policy{Min: time.Minute, Base: time.Minute, Max: 5 * time.Minute}
	tests := []struct {
		attempt int
		rnd     float64
		want    time.Duration
	}{
		{0, 0, time.Minute},
		{0, 0.5, time.Minute},
		{2, 0, time.Minute},
		{2, 0.5, 150 * time.Second},
		{9, 0.9, 276 * time.Second},
	}
	for _, tc := range tests {
		if got := p.Delay(tc.attempt, func() float64 { return tc.rnd }); got != tc.want {
			t.Fatalf("Delay(%d, %v) = %v, want %v", tc.attempt, tc.rnd, got, tc.want)
		}
	}
}
