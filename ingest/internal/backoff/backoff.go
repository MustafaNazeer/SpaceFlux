// Package backoff computes retry delays using exponential backoff with full jitter.
package backoff

import (
	"math/rand/v2"
	"time"
)

type Policy struct {
	Base time.Duration
	Max  time.Duration
}

// Delay returns a random duration in [0, min(Max, Base*2^attempt)).
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
	return time.Duration(rnd() * float64(ceiling))
}
