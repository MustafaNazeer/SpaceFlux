// Package poller schedules one feed: fetch on an interval, retry transient
// failures with backoff, and stop for good when the fetcher reports a halt.
package poller

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/backoff"
)

var ErrHalted = errors.New("poller halted")

type Poller struct {
	Interval time.Duration
	Backoff  backoff.Policy
	Fetch    func(context.Context) ([]byte, error)
	// Process decodes, validates, dedupes and publishes a body. An error means
	// publishing failed and the same body is retried; bad data goes to the DLQ
	// inside Process and is not an error here.
	Process func(context.Context, []byte) error
	// Reject dead letters a fetch error for which IsBadBody is true. An error
	// means the dead letter was not published and Reject is retried.
	Reject    func(context.Context, error) error
	IsHalt    func(error) bool
	IsBadBody func(error) bool
	Sleep     func(context.Context, time.Duration) error
	Rand      func() float64
}

// Run blocks until ctx is cancelled or the fetcher reports a halt, in which
// case the returned error wraps both ErrHalted and the cause. After a
// successful cycle, or a bad body, it waits the full Interval: only failures
// that never reached the provider are retried early.
func (p *Poller) Run(ctx context.Context) error {
	sleep := p.Sleep
	if sleep == nil {
		sleep = SleepContext
	}
	attempt := 0
	var pending func(context.Context) error
	for {
		if err := ctx.Err(); err != nil {
			return err
		}

		if pending == nil {
			body, err := p.Fetch(ctx)
			switch {
			case ctx.Err() != nil:
				return ctx.Err()
			case err != nil && p.IsHalt(err):
				return fmt.Errorf("%w: %w", ErrHalted, err)
			case err != nil && p.IsBadBody(err):
				pending = func(ctx context.Context) error { return p.Reject(ctx, err) }
			case err != nil:
				if err := sleep(ctx, p.Backoff.Delay(attempt, p.Rand)); err != nil {
					return err
				}
				attempt++
				continue
			default:
				pending = func(ctx context.Context) error { return p.Process(ctx, body) }
			}
		}

		if err := pending(ctx); err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if err := sleep(ctx, p.Backoff.Delay(attempt, p.Rand)); err != nil {
				return err
			}
			attempt++
			continue
		}

		pending = nil
		attempt = 0
		if err := sleep(ctx, p.Interval); err != nil {
			return err
		}
	}
}

func SleepContext(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
