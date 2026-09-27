package dedupe

import "sync"

// Window deduplicates feeds that serve a sliding window of recent records: a
// record is new when it was not in the previous committed response. Memory is
// bounded by one response.
type Window[K comparable] struct {
	mu       sync.Mutex
	previous map[K]struct{}
}

func NewWindow[K comparable]() *Window[K] {
	return &Window[K]{previous: map[K]struct{}{}}
}

func (w *Window[K]) IsNew(k K) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	_, seen := w.previous[k]
	return !seen
}

// Commit replaces the window with every identity in a response, and is called
// only after that response was published successfully.
func (w *Window[K]) Commit(keys []K) {
	next := make(map[K]struct{}, len(keys))
	for _, k := range keys {
		next[k] = struct{}{}
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	w.previous = next
}
