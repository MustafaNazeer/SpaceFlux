// Package events holds what every feed processor shares: the message type
// handed to Kafka and bounded, schema checked dead letters.
package events

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/schema"
)

const (
	Service = "ingest"

	// KafkaMaxRecordBytes is franz-go's default producer batch limit, which a
	// single record may not exceed (kgo.ProducerBatchMaxBytes, 1000012).
	KafkaMaxRecordBytes = 1000012
	// MaxDLQPayload bounds the payload copied into a dead letter. JSON escaping
	// can grow text up to sixfold, so a dead letter over maxDLQRecord falls back
	// to base64, which grows it by only a third.
	MaxDLQPayload = 256 << 10
	maxDLQRecord  = 900_000
	maxDLQReason  = 4 << 10
)

type Message struct {
	Topic string
	Key   []byte
	Value []byte
}

type Publisher interface {
	Publish(ctx context.Context, msgs []Message) error
}

type deadLetter struct {
	SchemaVersion   int    `json:"schema_version"`
	SourceTopic     string `json:"source_topic"`
	Service         string `json:"service"`
	Stage           string `json:"stage"`
	Check           string `json:"check,omitempty"`
	Reason          string `json:"reason"`
	FailedAt        string `json:"failed_at"`
	SourceURL       string `json:"source_url,omitempty"`
	Payload         string `json:"payload"`
	PayloadEncoding string `json:"payload_encoding"`
	PayloadBytes    int    `json:"payload_bytes"`
	PayloadTrunc    bool   `json:"payload_truncated,omitempty"`
}

// DeadLetter builds a dead letter for sourceTopic's .dlq topic, validated
// against its own schema so a broken one surfaces as an error rather than being
// published. incomplete marks payloads that are partial even when total ==
// len(payload). check is "schema" for a topic schema failure and empty when the
// dead letter does not say which check failed.
func DeadLetter(v *schema.Validator, now time.Time, sourceTopic, sourceURL string, key []byte, stage, check, reason string, payload []byte, total int, incomplete bool) (Message, error) {
	if len(payload) > MaxDLQPayload {
		cut := MaxDLQPayload
		for cut > MaxDLQPayload-utf8.UTFMax && !utf8.RuneStart(payload[cut]) {
			cut--
		}
		payload = payload[:cut]
	}
	if len(reason) > maxDLQReason {
		reason = strings.ToValidUTF8(reason[:maxDLQReason], "")
	}
	d := deadLetter{
		SchemaVersion:   1,
		SourceTopic:     sourceTopic,
		Service:         Service,
		Stage:           stage,
		Check:           check,
		Reason:          reason,
		FailedAt:        now.UTC().Format(time.RFC3339Nano),
		SourceURL:       sourceURL,
		Payload:         string(payload),
		PayloadEncoding: "utf-8",
		PayloadBytes:    total,
		PayloadTrunc:    incomplete || len(payload) < total,
	}
	value, err := MarshalNoEscape(d)
	if err == nil && (!utf8.Valid(payload) || len(value) > maxDLQRecord) {
		d.Payload = base64.StdEncoding.EncodeToString(payload)
		d.PayloadEncoding = "base64"
		value, err = MarshalNoEscape(d)
	}
	if err == nil {
		err = v.Validate(value)
	}
	if err != nil {
		return Message{}, fmt.Errorf("build dead letter: %w", err)
	}
	return Message{Topic: sourceTopic + ".dlq", Key: key, Value: value}, nil
}

// MarshalNoEscape encodes v without HTML escaping, so embedded provider
// records keep their bytes.
func MarshalNoEscape(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}
