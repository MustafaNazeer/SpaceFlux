// Package dedupe suppresses republishing a GP record whose feed identity
// (NORAD catalog ID plus epoch) has already been published.
package dedupe

import "sync"

type Tracker struct {
	mu     sync.Mutex
	latest map[int64]string
}

func NewTracker() *Tracker {
	return &Tracker{latest: make(map[int64]string)}
}

func (t *Tracker) IsDuplicate(id int64, epoch string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	e, ok := t.latest[id]
	return ok && e == epoch
}

// Record is called only after a successful publish, so a failed publish is retried next poll.
func (t *Tracker) Record(id int64, epoch string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.latest[id] = epoch
}
