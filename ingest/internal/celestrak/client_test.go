package celestrak

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("../../testdata/celestrak/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func newClient(url string) *Client {
	return NewClient(url, "SpaceFlux-test", 5*time.Second)
}

func TestFetchReturnsBodyAndSendsQuery(t *testing.T) {
	fixture := readFixture(t, "gp-stations.json")
	var gotPath, gotGroup, gotFormat, gotUA string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotGroup = r.URL.Query().Get("GROUP")
		gotFormat = r.URL.Query().Get("FORMAT")
		gotUA = r.UserAgent()
		w.Header().Set("Content-Type", "application/json")
		w.Write(fixture)
	}))
	defer srv.Close()

	body, err := newClient(srv.URL).Fetch(context.Background(), "stations")
	if err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if !bytes.Equal(body, fixture) {
		t.Fatalf("body differs from fixture: got %d bytes, want %d", len(body), len(fixture))
	}
	if gotPath != "/NORAD/elements/gp.php" || gotGroup != "stations" || gotFormat != "JSON" {
		t.Fatalf("request = %s GROUP=%q FORMAT=%q", gotPath, gotGroup, gotFormat)
	}
	if gotUA != "SpaceFlux-test" {
		t.Fatalf("User-Agent = %q", gotUA)
	}
}

func TestFetchNon200HaltsWithStatusAndBody(t *testing.T) {
	msg := "GP data has not updated since your last successful download"
	for _, code := range []int{http.StatusMovedPermanently, http.StatusForbidden, http.StatusNotFound, http.StatusInternalServerError, http.StatusServiceUnavailable, http.StatusNoContent} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if code == http.StatusMovedPermanently {
					w.Header().Set("Location", "/elsewhere")
				}
				w.WriteHeader(code)
				w.Write([]byte(msg))
			}))
			defer srv.Close()

			_, err := newClient(srv.URL).Fetch(context.Background(), "stations")
			var se *StatusError
			if !errors.As(err, &se) {
				t.Fatalf("err = %v, want *StatusError", err)
			}
			if se.Code != code {
				t.Fatalf("Code = %d, want %d", se.Code, code)
			}
			if code != http.StatusNoContent && !strings.Contains(se.Body, "has not updated") {
				t.Fatalf("Body = %q", se.Body)
			}
			if !IsHalt(err) {
				t.Fatal("IsHalt = false, want true")
			}
		})
	}
}

func TestFetchDoesNotFollowRedirects(t *testing.T) {
	hits := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		http.Redirect(w, r, "/other", http.StatusMovedPermanently)
	}))
	defer srv.Close()

	newClient(srv.URL).Fetch(context.Background(), "stations")
	if hits != 1 {
		t.Fatalf("server hit %d times, want 1", hits)
	}
}

func TestFetchStatusErrorBodyIsBounded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		w.Write(bytes.Repeat([]byte("x"), 1<<20))
	}))
	defer srv.Close()

	_, err := newClient(srv.URL).Fetch(context.Background(), "stations")
	var se *StatusError
	if !errors.As(err, &se) || len(se.Body) > maxErrorBody {
		t.Fatalf("err = %v, body length %d, want at most %d", err, len(se.Body), maxErrorBody)
	}
}

func TestFetchNetworkErrorIsRetryable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := srv.URL
	srv.Close()

	_, err := newClient(url).Fetch(context.Background(), "stations")
	if err == nil {
		t.Fatal("expected error")
	}
	if IsHalt(err) {
		t.Fatalf("IsHalt(%v) = true, want false for a connection failure", err)
	}
}

func TestFetchTimeoutIsRetryable(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { <-release }))
	defer srv.Close()
	defer close(release)

	_, err := NewClient(srv.URL, "SpaceFlux-test", 50*time.Millisecond).Fetch(context.Background(), "stations")
	if err == nil || IsHalt(err) {
		t.Fatalf("err = %v, want retryable timeout", err)
	}
}

func TestFetchRejectsOversizedBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write(bytes.Repeat([]byte(" "), maxBody+1))
	}))
	defer srv.Close()

	_, err := newClient(srv.URL).Fetch(context.Background(), "stations")
	if !errors.Is(err, ErrBodyTooLarge) {
		t.Fatalf("err = %v, want ErrBodyTooLarge", err)
	}
	if IsHalt(err) {
		t.Fatal("oversized 200 body should go to the DLQ, not halt")
	}
	var be *BodyError
	if !errors.As(err, &be) {
		t.Fatalf("err = %T, want *BodyError", err)
	}
	if len(be.Prefix) != maxBody || be.Total != maxBody+1 {
		t.Fatalf("prefix %d bytes, total %d; want %d and %d", len(be.Prefix), be.Total, maxBody, maxBody+1)
	}
}

func TestFetchHonorsCancellation(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { <-release }))
	defer srv.Close()
	defer close(release)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := newClient(srv.URL).Fetch(ctx, "stations")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
}

func TestFetchBodyCutOffAfter200IsBodyError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "1000")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`[{"OBJECT_NAME":`))
	}))
	defer srv.Close()

	_, err := newClient(srv.URL).Fetch(context.Background(), "stations")
	var be *BodyError
	if !errors.As(err, &be) {
		t.Fatalf("err = %v, want *BodyError so the poller waits the full interval", err)
	}
	if string(be.Prefix) != `[{"OBJECT_NAME":` || IsHalt(err) {
		t.Fatalf("prefix = %q, halt = %v", be.Prefix, IsHalt(err))
	}
}
