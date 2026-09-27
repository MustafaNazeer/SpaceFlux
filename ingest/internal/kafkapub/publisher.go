// Package kafkapub publishes ingest messages to Kafka with franz-go (ADR 0003).
package kafkapub

import (
	"context"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/gpfeed"
)

type Publisher struct {
	client  *kgo.Client
	timeout time.Duration
}

// New relies on franz-go's defaults of an idempotent producer with acks from
// all in sync replicas, which keeps per key order across retries. Topics are
// never created here; they are provisioned outside the service.
//
// The franz-go default never times out a record, so a down broker would block
// Publish forever. deliveryTimeout roughly bounds each call, through the
// client option and a context. Neither can fail a record whose request is
// already in flight without a response, because that would break idempotent
// sequencing.
func New(brokers []string, deliveryTimeout time.Duration) (*Publisher, error) {
	cl, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.RecordDeliveryTimeout(deliveryTimeout))
	if err != nil {
		return nil, err
	}
	return &Publisher{client: cl, timeout: deliveryTimeout}, nil
}

func (p *Publisher) Publish(ctx context.Context, msgs []gpfeed.Message) error {
	recs := make([]*kgo.Record, len(msgs))
	for i, m := range msgs {
		recs[i] = &kgo.Record{Topic: m.Topic, Key: m.Key, Value: m.Value}
	}
	ctx, cancel := context.WithTimeout(ctx, p.timeout)
	defer cancel()
	return p.client.ProduceSync(ctx, recs...).FirstErr()
}

func (p *Publisher) Ping(ctx context.Context) error {
	return p.client.Ping(ctx)
}

func (p *Publisher) Close() {
	p.client.Close()
}
