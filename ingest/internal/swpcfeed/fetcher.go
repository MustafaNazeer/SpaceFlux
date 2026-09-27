package swpcfeed

import (
	"context"
	"errors"
	"net/http"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/feedhttp"
	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/poller"
)

// IsHalt reports a 403 (possibly blocked) or 404 (possibly retired product),
// which retrying will not fix (ADR 0005). Other statuses back off and retry.
func IsHalt(err error) bool {
	var se *feedhttp.StatusError
	return errors.As(err, &se) && (se.Code == http.StatusForbidden || se.Code == http.StatusNotFound)
}

// maxETag bounds the stored validator; a longer one is dropped rather than
// echoed back.
const maxETag = 1024

// Fetcher polls one product, sending the last ETag so an unchanged file costs
// a 304 with no body.
type Fetcher struct {
	client *feedhttp.Client
	url    string
	etag   string
}

func NewFetcher(c *feedhttp.Client, baseURL string, p Product) *Fetcher {
	return &Fetcher{client: c, url: baseURL + p.Path}
}

func (f *Fetcher) Fetch(ctx context.Context) ([]byte, error) {
	resp, err := f.client.Fetch(ctx, f.url, f.etag)
	if err != nil {
		var se *feedhttp.StatusError
		if errors.As(err, &se) {
			f.etag = ""
		}
		return nil, err
	}
	if resp.NotModified {
		return nil, poller.ErrUnchanged
	}
	f.etag = resp.ETag
	if len(f.etag) > maxETag {
		f.etag = ""
	}
	return resp.Body, nil
}
