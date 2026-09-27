// Command ingest polls CelesTrak GP data and NOAA SWPC products and publishes them to Kafka.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/backoff"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/celestrak"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/config"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/dedupe"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/feedhttp"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/gpfeed"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/health"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/kafkapub"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/poller"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/swpcfeed"
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
	load := func(parts ...string) (*schema.Validator, error) {
		return schema.Load(filepath.Join(append([]string{cfg.SchemasDir}, parts...)...))
	}
	gpEvents, err := load("raw.gp", "v1.schema.json")
	if err != nil {
		return err
	}
	swpcEvents, err := load("raw.swpc", "v1.schema.json")
	if err != nil {
		return err
	}
	deadLetters, err := load("dlq", "v1.schema.json")
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

	var feeds []string
	if cfg.FeedEnabled("celestrak") {
		feeds = append(feeds, "celestrak")
	}
	if cfg.FeedEnabled("swpc") {
		for _, p := range swpcfeed.Products {
			feeds = append(feeds, p.ID)
		}
	}
	state := health.New(pub.Ping, feeds...)
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

	pollers := map[string]*poller.Poller{}
	if cfg.FeedEnabled("celestrak") {
		pollers["celestrak"] = celestrakPoller(cfg, log, state, gpEvents, deadLetters, pub)
	}
	if cfg.FeedEnabled("swpc") {
		httpClient := feedhttp.NewClient(cfg.UserAgent, 60*time.Second)
		for _, prod := range swpcfeed.Products {
			pollers[prod.ID] = swpcPoller(cfg, log, state, prod, httpClient, swpcEvents, deadLetters, pub)
		}
	}

	runPollers(ctx, log, state, pollers)

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
	log.Info("shutting down")
	return nil
}

// runPollers runs every poller until ctx ends. A halted feed is reported on
// readiness while the others keep running (ADR 0004, ADR 0005).
func runPollers(ctx context.Context, log *slog.Logger, state *health.State, pollers map[string]*poller.Poller) {
	var wg sync.WaitGroup
	for name, p := range pollers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			err := p.Run(ctx)
			if errors.Is(err, poller.ErrHalted) {
				log.Error("feed halted; restart ingest after investigating", "feed", name, "err", err)
				state.SetHalted(name, err)
			}
		}()
	}
	<-ctx.Done()
	wg.Wait()
}

func isBadBody(err error) bool {
	var be *feedhttp.BodyError
	return errors.As(err, &be)
}

// rejecter dead letters a bad 200 body through reject, reporting publish health for feed.
func rejecter(log *slog.Logger, state *health.State, feed string, reject func(context.Context, string, []byte, int) error) func(context.Context, error) error {
	return func(ctx context.Context, fetchErr error) error {
		var be *feedhttp.BodyError
		errors.As(fetchErr, &be)
		log.Warn("bad response body after HTTP 200, dead lettering and waiting a full interval", "feed", feed, "err", fetchErr)
		err := reject(ctx, be.Error(), be.Prefix, be.Total)
		recordPublish(log, state, feed, err)
		return err
	}
}

func celestrakPoller(cfg config.Config, log *slog.Logger, state *health.State, ev, dl *schema.Validator, pub *kafkapub.Publisher) *poller.Poller {
	client := celestrak.NewClient(cfg.CelesTrakBaseURL, cfg.UserAgent, 60*time.Second)
	proc := &gpfeed.Processor{
		Events:     ev,
		DeadLetter: dl,
		Tracker:    dedupe.NewTracker(),
		Publisher:  pub,
		SourceURL:  client.URL(cfg.CelesTrakGroup),
		Now:        time.Now,
	}
	var fetchedAt time.Time
	return &poller.Poller{
		Interval: cfg.CelesTrakInterval,
		Backoff:  backoff.Policy{Base: 5 * time.Second, Max: cfg.CelesTrakInterval},
		Fetch: func(ctx context.Context) ([]byte, error) {
			log.Info("fetching", "feed", "celestrak", "url", proc.SourceURL)
			body, err := client.Fetch(ctx, cfg.CelesTrakGroup)
			fetchedAt = time.Now()
			if err != nil && !celestrak.IsHalt(err) && !isBadBody(err) && ctx.Err() == nil {
				log.Warn("fetch failed, will retry", "feed", "celestrak", "err", err)
			}
			return body, err
		},
		Process: func(ctx context.Context, body []byte) error {
			err := proc.Process(ctx, body, fetchedAt)
			recordPublish(log, state, "celestrak", err)
			if err == nil {
				log.Info("processed response", "feed", "celestrak", "bytes", len(body))
			}
			return err
		},
		Reject:    rejecter(log, state, "celestrak", proc.RejectBody),
		IsHalt:    celestrak.IsHalt,
		IsBadBody: isBadBody,
	}
}

func swpcPoller(cfg config.Config, log *slog.Logger, state *health.State, prod swpcfeed.Product, client *feedhttp.Client, ev, dl *schema.Validator, pub *kafkapub.Publisher) *poller.Poller {
	proc := swpcfeed.NewProcessor(prod, cfg.SWPCBaseURL, ev, dl, pub, time.Now)
	fetcher := swpcfeed.NewFetcher(client, cfg.SWPCBaseURL, prod)
	var fetchedAt time.Time
	return &poller.Poller{
		Interval: cfg.SWPCInterval,
		Backoff:  swpcBackoff(cfg),
		Fetch: func(ctx context.Context) ([]byte, error) {
			body, err := fetcher.Fetch(ctx)
			fetchedAt = time.Now()
			if errors.Is(err, poller.ErrUnchanged) {
				log.Info("not modified", "feed", prod.ID)
			}
			if err != nil && !errors.Is(err, poller.ErrUnchanged) && !swpcfeed.IsHalt(err) && !isBadBody(err) && ctx.Err() == nil {
				log.Warn("fetch failed, will retry", "feed", prod.ID, "err", err)
			}
			return body, err
		},
		Process: func(ctx context.Context, body []byte) error {
			err := proc.Process(ctx, body, fetchedAt)
			recordPublish(log, state, prod.ID, err)
			if err == nil {
				log.Info("processed response", "feed", prod.ID, "bytes", len(body))
			}
			return err
		},
		Reject:    rejecter(log, state, prod.ID, proc.RejectBody),
		IsHalt:    swpcfeed.IsHalt,
		IsBadBody: isBadBody,
	}
}

// swpcBackoff honors the NWS notice's one minute retry spacing (ADR 0005).
func swpcBackoff(cfg config.Config) backoff.Policy {
	return backoff.Policy{Min: config.MinSWPCInterval, Base: 2 * config.MinSWPCInterval, Max: cfg.SWPCInterval}
}

func recordPublish(log *slog.Logger, state *health.State, feed string, err error) {
	if err != nil {
		log.Warn("processing failed, will retry the same body", "feed", feed, "err", err)
		state.PublishFailed(feed, err)
		return
	}
	state.PublishSucceeded(feed)
}
