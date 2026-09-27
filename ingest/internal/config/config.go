// Package config reads ingest settings from the environment.
package config

import (
	"errors"
	"fmt"
	"net/url"
	"slices"
	"strings"
	"time"
)

const (
	// MinCelesTrakInterval is CelesTrak's documented GP update cadence (ADR 0004).
	MinCelesTrakInterval = 2 * time.Hour
	// MinSWPCInterval is the NWS appropriate use notice's retry spacing (ADR 0005).
	MinSWPCInterval = time.Minute
)

type Config struct {
	KafkaBrokers      []string
	CelesTrakBaseURL  string
	CelesTrakGroup    string
	CelesTrakInterval time.Duration
	SWPCBaseURL       string
	SWPCInterval      time.Duration
	UserAgent         string
	HTTPAddr          string
	SchemasDir        string
	Feeds             []string
}

var knownFeeds = []string{"celestrak", "swpc"}

func (c Config) FeedEnabled(name string) bool {
	return slices.Contains(c.Feeds, name)
}

func Load(getenv func(string) string) (Config, error) {
	get := func(k, def string) string {
		if v := strings.TrimSpace(getenv(k)); v != "" {
			return v
		}
		return def
	}
	c := Config{
		CelesTrakBaseURL: get("CELESTRAK_BASE_URL", "https://celestrak.org"),
		CelesTrakGroup:   get("CELESTRAK_GROUP", "stations"),
		UserAgent:        get("INGEST_USER_AGENT", "SpaceFlux-ingest (https://github.com/MustafaNazeer/SpaceFlux)"),
		HTTPAddr:         get("INGEST_HTTP_ADDR", "127.0.0.1:8080"),
		SchemasDir:       get("SCHEMAS_DIR", "schemas"),
	}

	// CelesTrak counts responses from any other domain against its firewall
	// limit, and events require an https source_url (ADR 0004).
	if u, err := url.Parse(c.CelesTrakBaseURL); err != nil || u.Scheme != "https" || u.Host != "celestrak.org" || (u.Path != "" && u.Path != "/") {
		return Config{}, fmt.Errorf("CELESTRAK_BASE_URL %q: must be https://celestrak.org", c.CelesTrakBaseURL)
	}
	c.CelesTrakBaseURL = "https://celestrak.org"

	swpc := get("SWPC_BASE_URL", "https://services.swpc.noaa.gov")
	if u, err := url.Parse(swpc); err != nil || u.Scheme != "https" || u.Host != "services.swpc.noaa.gov" || (u.Path != "" && u.Path != "/") {
		return Config{}, fmt.Errorf("SWPC_BASE_URL %q: must be https://services.swpc.noaa.gov", swpc)
	}
	c.SWPCBaseURL = "https://services.swpc.noaa.gov"

	for _, b := range strings.Split(getenv("KAFKA_BROKERS"), ",") {
		if b = strings.TrimSpace(b); b != "" {
			c.KafkaBrokers = append(c.KafkaBrokers, b)
		}
	}
	if len(c.KafkaBrokers) == 0 {
		return Config{}, errors.New("KAFKA_BROKERS is required")
	}

	d, err := time.ParseDuration(get("CELESTRAK_INTERVAL", "2h10m"))
	if err != nil {
		return Config{}, fmt.Errorf("CELESTRAK_INTERVAL: %w", err)
	}
	if d < MinCelesTrakInterval {
		return Config{}, fmt.Errorf("CELESTRAK_INTERVAL %v: must be at least %v, CelesTrak's update cadence", d, MinCelesTrakInterval)
	}
	c.CelesTrakInterval = d

	d, err = time.ParseDuration(get("SWPC_INTERVAL", "5m"))
	if err != nil {
		return Config{}, fmt.Errorf("SWPC_INTERVAL: %w", err)
	}
	if d < MinSWPCInterval {
		return Config{}, fmt.Errorf("SWPC_INTERVAL %v: must be at least %v between requests", d, MinSWPCInterval)
	}
	c.SWPCInterval = d

	// INGEST_FEEDS lets a local run skip CelesTrak, whose policy allows one
	// download per two hours even across restarts.
	for _, f := range strings.Split(get("INGEST_FEEDS", strings.Join(knownFeeds, ",")), ",") {
		if f = strings.TrimSpace(f); f == "" {
			continue
		}
		if !slices.Contains(knownFeeds, f) {
			return Config{}, fmt.Errorf("INGEST_FEEDS: unknown feed %q, want some of %v", f, knownFeeds)
		}
		c.Feeds = append(c.Feeds, f)
	}
	if len(c.Feeds) == 0 {
		return Config{}, errors.New("INGEST_FEEDS names no feed")
	}
	return c, nil
}
