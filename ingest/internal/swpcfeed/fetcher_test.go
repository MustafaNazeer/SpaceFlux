package swpcfeed

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/feedhttp"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/poller"
)

func TestFetcherSendsETagAndReportsUnchanged(t *testing.T) {
	var gotPath, gotINM string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotINM = r.URL.Path, r.Header.Get("If-None-Match")
		w.Header().Set("ETag", `"126f"`)
		if gotINM == `"126f"` {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	kp, _ := ProductByID("swpc.kp")
	f := NewFetcher(feedhttp.NewClient("SpaceFlux-test", 5*time.Second), srv.URL, kp)

	body, err := f.Fetch(context.Background())
	if err != nil || string(body) != "[]" || gotPath != kp.Path || gotINM != "" {
		t.Fatalf("first fetch body=%q err=%v path=%s inm=%q", body, err, gotPath, gotINM)
	}
	if _, err := f.Fetch(context.Background()); !errors.Is(err, poller.ErrUnchanged) || gotINM != `"126f"` {
		t.Fatalf("second fetch err=%v inm=%q, want ErrUnchanged after sending the ETag", err, gotINM)
	}
}

func TestHaltOnlyOnForbiddenOrNotFound(t *testing.T) {
	for code, halt := range map[int]bool{403: true, 404: true, 500: false, 503: false, 429: false, 301: false} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(code)
		}))
		kp, _ := ProductByID("swpc.kp")
		_, err := NewFetcher(feedhttp.NewClient("SpaceFlux-test", 5*time.Second), srv.URL, kp).Fetch(context.Background())
		srv.Close()
		if err == nil || IsHalt(err) != halt {
			t.Fatalf("HTTP %d: err=%v IsHalt=%v, want %v", code, err, IsHalt(err), halt)
		}
	}
}

func TestETagClearedAfterErrorAndCapped(t *testing.T) {
	var inm []string
	status, etag := http.StatusOK, `"a"`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		inm = append(inm, r.Header.Get("If-None-Match"))
		w.Header().Set("ETag", etag)
		w.WriteHeader(status)
	}))
	defer srv.Close()
	kp, _ := ProductByID("swpc.kp")
	f := NewFetcher(feedhttp.NewClient("SpaceFlux-test", 5*time.Second), srv.URL, kp)

	f.Fetch(context.Background())
	status = http.StatusServiceUnavailable
	f.Fetch(context.Background())
	status, etag = http.StatusOK, `"`+strings.Repeat("x", maxETag)+`"`
	f.Fetch(context.Background())
	f.Fetch(context.Background())

	want := []string{"", `"a"`, "", ""}
	for i := range want {
		if inm[i] != want[i] {
			t.Fatalf("If-None-Match per request = %q, want %q: clear after an error status and never store an oversized ETag", inm, want)
		}
	}
}
