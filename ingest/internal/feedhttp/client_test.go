package feedhttp

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestConditionalRequestReturnsNotModified(t *testing.T) {
	var gotINM string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotINM = r.Header.Get("If-None-Match")
		if gotINM == `"v1"` {
			w.Header().Set("ETag", `"v1"`)
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("ETag", `"v1"`)
		w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	c := NewClient("SpaceFlux-test", 5*time.Second)

	first, err := c.Fetch(context.Background(), srv.URL, "")
	if err != nil {
		t.Fatalf("first Fetch: %v", err)
	}
	if gotINM != "" || first.NotModified || first.ETag != `"v1"` || string(first.Body) != "[]" {
		t.Fatalf("first = %+v, If-None-Match sent %q", first, gotINM)
	}

	second, err := c.Fetch(context.Background(), srv.URL, first.ETag)
	if err != nil {
		t.Fatalf("second Fetch: %v", err)
	}
	if gotINM != `"v1"` || !second.NotModified || second.Body != nil {
		t.Fatalf("second = %+v, If-None-Match sent %q", second, gotINM)
	}
}

func TestNotModifiedWithoutConditionalIsStatusError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotModified)
	}))
	defer srv.Close()

	_, err := NewClient("SpaceFlux-test", 5*time.Second).Fetch(context.Background(), srv.URL, "")
	var se *StatusError
	if !errors.As(err, &se) || se.Code != http.StatusNotModified {
		t.Fatalf("err = %v, want StatusError 304 when no ETag was sent", err)
	}
}
