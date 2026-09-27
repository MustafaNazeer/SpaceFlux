// Package config reads ingest settings from the environment.
package config

import (
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"
)

// MinCelesTrakInterval is CelesTrak's documented GP update cadence (ADR 0004).
const MinCelesTrakInterval = 2 * time.Hour

type Config struct {
	KafkaBrokers      []string
	CelesTrakBaseURL  string
	CelesTrakGroup    string
	CelesTrakInterval time.Duration
	UserAgent         string
	HTTPAddr          string
	SchemasDir        string
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
	return c, nil
}
