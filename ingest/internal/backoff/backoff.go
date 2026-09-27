// Package backoff computes retry delays using exponential backoff with full jitter.
package backoff

import (
	"math/rand/v2"
	"time"
)

type Policy struct {
	// Min is a floor on every delay, for providers that ask for a minimum
	// spacing between retries.
	Min  time.Duration
	Base time.Duration
	Max  time.Duration
}

// Delay returns a random duration in [Min, ceiling), where ceiling is
// min(Max, Base*2^attempt); it returns Min when the ceiling is not above it.
// rnd must return a value in [0, 1); nil uses math/rand/v2.
func (p Policy) Delay(attempt int, rnd func() float64) time.Duration {
	if rnd == nil {
		rnd = rand.Float64
	}
	if attempt < 0 {
		attempt = 0
	}
	ceiling := p.Max
	if attempt < 62 {
		if d := p.Base << attempt; d > 0 && d < p.Max && d>>attempt == p.Base {
			ceiling = d
		}
	}
	if ceiling <= p.Min {
		return p.Min
	}
	return p.Min + time.Duration(rnd()*float64(ceiling-p.Min))
}
