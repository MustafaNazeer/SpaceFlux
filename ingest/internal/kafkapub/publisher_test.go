package kafkapub

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/network"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/gpfeed"
)

const kafkaImage = "apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837"

// startKafka runs a single node KRaft broker using the environment from
// Apache's single node plaintext example, advertised on a fixed free host port.
func startKafka(t *testing.T) string {
	t.Helper()
	if testing.Short() {
		t.Skip("integration test: needs Docker")
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	addr := fmt.Sprintf("127.0.0.1:%d", port)

	ctx := context.Background()
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		Started: true,
		ContainerRequest: testcontainers.ContainerRequest{
			Image:        kafkaImage,
			ExposedPorts: []string{"9092/tcp"},
			Env: map[string]string{
				"KAFKA_NODE_ID":                                          "1",
				"KAFKA_PROCESS_ROLES":                                    "broker,controller",
				"KAFKA_LISTENERS":                                        "CONTROLLER://:29093,PLAINTEXT_HOST://:9092,PLAINTEXT://:19092",
				"KAFKA_ADVERTISED_LISTENERS":                             "PLAINTEXT_HOST://" + addr + ",PLAINTEXT://localhost:19092",
				"KAFKA_LISTENER_SECURITY_PROTOCOL_MAP":                   "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT",
				"KAFKA_CONTROLLER_QUORUM_VOTERS":                         "1@localhost:29093",
				"KAFKA_INTER_BROKER_LISTENER_NAME":                       "PLAINTEXT",
				"KAFKA_CONTROLLER_LISTENER_NAMES":                        "CONTROLLER",
				"KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR":                 "1",
				"KAFKA_TRANSACTION_STATE_LOG_MIN_ISR":                    "1",
				"KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR":         "1",
				"KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR": "1",
				"KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR":            "1",
				"KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS":                 "0",
			},
			HostConfigModifier: func(hc *container.HostConfig) {
				hc.PortBindings = network.PortMap{network.MustParsePort("9092/tcp"): {{HostIP: netip.MustParseAddr("127.0.0.1"), HostPort: fmt.Sprint(port)}}}
			},
			WaitingFor: wait.ForListeningPort("9092/tcp").WithStartupTimeout(2 * time.Minute),
		},
	})
	testcontainers.CleanupContainer(t, c)
	if err != nil {
		t.Fatalf("start kafka: %v", err)
	}
	return addr
}

func createTopics(t *testing.T, addr string, topics ...string) {
	t.Helper()
	cl, err := kgo.NewClient(kgo.SeedBrokers(addr))
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	resp, err := kadm.NewClient(cl).CreateTopics(ctx, 3, 1, nil, topics...)
	if err != nil {
		t.Fatal(err)
	}
	if err := resp.Error(); err != nil {
		t.Fatal(err)
	}
}

func consumeAll(t *testing.T, addr string, topic string, want int) []*kgo.Record {
	t.Helper()
	cl, err := kgo.NewClient(kgo.SeedBrokers(addr), kgo.ConsumeTopics(topic), kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()))
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var out []*kgo.Record
	for len(out) < want {
		fs := cl.PollFetches(ctx)
		if ctx.Err() != nil {
			t.Fatalf("consumed %d of %d records from %s before timeout", len(out), want, topic)
		}
		fs.EachRecord(func(r *kgo.Record) { out = append(out, r) })
	}
	return out
}

func TestPublishDeliversKeyedRecordsAcrossTopics(t *testing.T) {
	addr := startKafka(t)
	createTopics(t, addr, gpfeed.TopicRawGP, gpfeed.TopicRawGPDLQ)

	pub, err := New([]string{addr}, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	defer pub.Close()

	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	msgs := []gpfeed.Message{
		{Topic: gpfeed.TopicRawGP, Key: []byte("25544"), Value: []byte(`{"n":1}`)},
		{Topic: gpfeed.TopicRawGP, Key: []byte("25544"), Value: []byte(`{"n":2}`)},
		{Topic: gpfeed.TopicRawGP, Key: []byte("100057"), Value: []byte(`{"n":3}`)},
		{Topic: gpfeed.TopicRawGPDLQ, Value: []byte(`{"dlq":true}`)},
	}
	if err := pub.Publish(ctx, msgs); err != nil {
		t.Fatalf("Publish: %v", err)
	}
	if err := pub.Ping(ctx); err != nil {
		t.Fatalf("Ping: %v", err)
	}

	got := consumeAll(t, addr, gpfeed.TopicRawGP, 3)
	var issOrder []string
	partitions := map[string]int32{}
	for _, r := range got {
		if p, ok := partitions[string(r.Key)]; ok && p != r.Partition {
			t.Fatalf("key %s spread across partitions %d and %d", r.Key, p, r.Partition)
		}
		partitions[string(r.Key)] = r.Partition
		if string(r.Key) == "25544" {
			issOrder = append(issOrder, string(r.Value))
		}
	}
	if len(issOrder) != 2 || issOrder[0] != `{"n":1}` || issOrder[1] != `{"n":2}` {
		t.Fatalf("records for one key out of order: %v", issOrder)
	}
	dlq := consumeAll(t, addr, gpfeed.TopicRawGPDLQ, 1)
	if dlq[0].Key != nil {
		t.Fatalf("dlq record without a known key got key %q", dlq[0].Key)
	}
}

func TestPublishToMissingTopicFails(t *testing.T) {
	addr := startKafka(t)
	pub, err := New([]string{addr}, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	defer pub.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	err = pub.Publish(ctx, []gpfeed.Message{{Topic: "raw.missing", Value: []byte("{}")}})
	if err == nil {
		t.Fatal("publish to a topic that does not exist succeeded; ingest must not auto create topics")
	}
}

func TestPingFailsWhenBrokerUnreachable(t *testing.T) {
	pub, err := New([]string{"127.0.0.1:1"}, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	defer pub.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := pub.Ping(ctx); err == nil {
		t.Fatal("Ping succeeded against an unreachable broker")
	}
}

func TestPublishGivesUpWithinDeliveryTimeoutWhenBrokerUnreachable(t *testing.T) {
	pub, err := New([]string{"127.0.0.1:1"}, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer pub.Close()
	start := time.Now()
	err = pub.Publish(context.Background(), []gpfeed.Message{{Topic: gpfeed.TopicRawGP, Value: []byte("{}")}})
	if err == nil {
		t.Fatal("Publish succeeded with no broker")
	}
	if d := time.Since(start); d > 10*time.Second {
		t.Fatalf("Publish took %v to fail, want about the 2s delivery timeout", d)
	}
}
