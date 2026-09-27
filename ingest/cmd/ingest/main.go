// Command ingest polls CelesTrak GP data and publishes it to Kafka.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/backoff"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/celestrak"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/config"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/dedupe"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/gpfeed"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/health"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/kafkapub"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/poller"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(log); err != nil {
		log.Error("ingest stopped", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}
	events, err := schema.Load(filepath.Join(cfg.SchemasDir, "raw.gp", "v1.schema.json"))
	if err != nil {
		return err
	}
	deadLetters, err := schema.Load(filepath.Join(cfg.SchemasDir, "dlq", "v1.schema.json"))
	if err != nil {
		return err
	}
	pub, err := kafkapub.New(cfg.KafkaBrokers, 60*time.Second)
	if err != nil {
		return err
	}
	defer pub.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	state := health.New(pub.Ping)
	srv := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           state.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}
	serveErr := make(chan error, 1)
	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serveErr <- err
			stop()
		}
	}()

	client := celestrak.NewClient(cfg.CelesTrakBaseURL, cfg.UserAgent, 60*time.Second)
	proc := &gpfeed.Processor{
		Events:     events,
		DeadLetter: deadLetters,
		Tracker:    dedupe.NewTracker(),
		Publisher:  pub,
		SourceURL:  client.URL(cfg.CelesTrakGroup),
		Now:        time.Now,
	}

	var fetchedAt time.Time
	p := &poller.Poller{
		Interval: cfg.CelesTrakInterval,
		Backoff:  backoff.Policy{Base: 5 * time.Second, Max: cfg.CelesTrakInterval},
		Fetch: func(ctx context.Context) ([]byte, error) {
			log.Info("fetching", "url", proc.SourceURL)
			body, err := client.Fetch(ctx, cfg.CelesTrakGroup)
			fetchedAt = time.Now()
			if err != nil && !celestrak.IsHalt(err) && ctx.Err() == nil {
				log.Warn("fetch failed, will retry", "err", err)
			}
			return body, err
		},
		Process: func(ctx context.Context, body []byte) error {
			err := proc.Process(ctx, body, fetchedAt)
			recordPublish(log, state, err)
			if err == nil {
				log.Info("processed response", "bytes", len(body))
			}
			return err
		},
		Reject: func(ctx context.Context, fetchErr error) error {
			var be *celestrak.BodyError
			errors.As(fetchErr, &be)
			log.Warn("bad response body after HTTP 200, dead lettering and waiting a full interval", "err", fetchErr)
			err := proc.RejectBody(ctx, be.Error(), be.Prefix, be.Total)
			recordPublish(log, state, err)
			return err
		},
		IsHalt: celestrak.IsHalt,
		IsBadBody: func(err error) bool {
			var be *celestrak.BodyError
			return errors.As(err, &be)
		},
	}

	err = p.Run(ctx)
	if errors.Is(err, poller.ErrHalted) {
		log.Error("celestrak polling halted; restart ingest after investigating", "err", err)
		state.SetHalted(err)
		<-ctx.Done()
	}

	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdown); err != nil {
		log.Warn("health server shutdown", "err", err)
	}
	select {
	case err := <-serveErr:
		return err
	default:
	}
	if ctx.Err() != nil {
		log.Info("shutting down")
		return nil
	}
	return err
}

func recordPublish(log *slog.Logger, state *health.State, err error) {
	if err != nil {
		log.Warn("processing failed, will retry the same body", "err", err)
		state.PublishFailed(err)
		return
	}
	state.PublishSucceeded()
}
