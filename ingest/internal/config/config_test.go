package config

import (
	"strings"
	"testing"
	"time"
)

func env(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestLoadDefaults(t *testing.T) {
	c, err := Load(env(map[string]string{"KAFKA_BROKERS": "localhost:9092"}))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if c.CelesTrakInterval != 2*time.Hour+10*time.Minute {
		t.Errorf("interval = %v, want 2h10m", c.CelesTrakInterval)
	}
	if c.CelesTrakBaseURL != "https://celestrak.org" {
		t.Errorf("base URL = %q", c.CelesTrakBaseURL)
	}
	if c.CelesTrakGroup != "stations" {
		t.Errorf("group = %q", c.CelesTrakGroup)
	}
	if len(c.KafkaBrokers) != 1 || c.KafkaBrokers[0] != "localhost:9092" {
		t.Errorf("brokers = %v", c.KafkaBrokers)
	}
	if c.HTTPAddr != "127.0.0.1:8080" {
		t.Errorf("http addr = %q", c.HTTPAddr)
	}
	if c.SchemasDir != "schemas" {
		t.Errorf("schemas dir = %q", c.SchemasDir)
	}
}

func TestLoadErrors(t *testing.T) {
	tests := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"interval below two hour floor", map[string]string{"KAFKA_BROKERS": "k:9092", "CELESTRAK_INTERVAL": "1h59m59s"}, "at least 2h"},
		{"interval not a duration", map[string]string{"KAFKA_BROKERS": "k:9092", "CELESTRAK_INTERVAL": "soon"}, "CELESTRAK_INTERVAL"},
		{"brokers missing", map[string]string{}, "KAFKA_BROKERS"},
		{"brokers only separators", map[string]string{"KAFKA_BROKERS": " , "}, "KAFKA_BROKERS"},
		{"plain http base URL", map[string]string{"KAFKA_BROKERS": "k:9092", "CELESTRAK_BASE_URL": "http://celestrak.org"}, "CELESTRAK_BASE_URL"},
		{"other host", map[string]string{"KAFKA_BROKERS": "k:9092", "CELESTRAK_BASE_URL": "https://celestrak.com"}, "CELESTRAK_BASE_URL"},
		{"path on base URL", map[string]string{"KAFKA_BROKERS": "k:9092", "CELESTRAK_BASE_URL": "https://celestrak.org/NORAD"}, "CELESTRAK_BASE_URL"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(env(tc.env))
			if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
				t.Fatalf("Load err = %v, want containing %q", err, tc.wantErr)
			}
		})
	}
}

func TestLoadOverrides(t *testing.T) {
	c, err := Load(env(map[string]string{
		"KAFKA_BROKERS":      "a:9092, b:9092",
		"CELESTRAK_INTERVAL": "2h",
		"CELESTRAK_GROUP":    "visual",
	}))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if c.CelesTrakInterval != 2*time.Hour || c.CelesTrakGroup != "visual" {
		t.Fatalf("config = %+v", c)
	}
	if len(c.KafkaBrokers) != 2 || c.KafkaBrokers[1] != "b:9092" {
		t.Fatalf("brokers = %v", c.KafkaBrokers)
	}
}
