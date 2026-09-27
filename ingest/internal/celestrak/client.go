// Package celestrak fetches GP element sets from CelesTrak.
//
// CelesTrak asks machine clients to stop on any non 200 response, and counts
// 301, 403 and 404 responses toward a firewall limit (docs/source/celestrak.md,
// ADR 0004). Every non 200 is therefore a halt.
package celestrak

import (
	"context"
	"errors"
	"net/url"
	"time"

	"github.com/MustafaNazeer/SpaceFlux/ingest/internal/feedhttp"
)

const (
	maxBody      = feedhttp.MaxBody
	maxErrorBody = feedhttp.MaxErrorBody
)

type (
	StatusError = feedhttp.StatusError
	BodyError   = feedhttp.BodyError
)

var ErrBodyTooLarge = feedhttp.ErrBodyTooLarge

// IsHalt reports whether err means polling must stop until an operator intervenes.
func IsHalt(err error) bool {
	var se *StatusError
	return errors.As(err, &se)
}

type Client struct {
	baseURL string
	http    *feedhttp.Client
}

func NewClient(baseURL, userAgent string, timeout time.Duration) *Client {
	return &Client{baseURL: baseURL, http: feedhttp.NewClient(userAgent, timeout)}
}

// URL is the exact request URL for a group, recorded as source_url on events.
func (c *Client) URL(group string) string {
	q := url.Values{"GROUP": {group}, "FORMAT": {"JSON"}}
	return c.baseURL + "/NORAD/elements/gp.php?" + q.Encode()
}

// Fetch never sends a conditional request: CelesTrak does not document one,
// and a 304 would count as a non 200 response.
func (c *Client) Fetch(ctx context.Context, group string) ([]byte, error) {
	resp, err := c.http.Fetch(ctx, c.URL(group), "")
	return resp.Body, err
}
