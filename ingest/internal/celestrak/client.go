// Package celestrak fetches GP element sets from CelesTrak.
//
// CelesTrak asks machine clients to stop on any non 200 response, and counts
// 301, 403 and 404 responses toward a firewall limit (docs/source/celestrak.md,
// ADR 0004). Fetch therefore never follows redirects and reports every non 200
// as a *StatusError, which callers treat as a halt.
package celestrak

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"time"
)

const (
	maxBody      = 8 << 20
	maxErrorBody = 512
)

var ErrBodyTooLarge = errors.New("celestrak: response body exceeds limit")

type StatusError struct {
	Code int
	Body string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("celestrak: HTTP %d: %s", e.Code, e.Body)
}

// BodyError is a failure after a 200 status line: the body was too large or
// could not be read in full. CelesTrak may already count the download, so the
// caller dead letters the prefix and waits a full interval instead of retrying.
type BodyError struct {
	Err    error
	Prefix []byte
	Total  int
}

func (e *BodyError) Error() string {
	return fmt.Sprintf("celestrak: body after HTTP 200 (%d bytes read): %v", e.Total, e.Err)
}

func (e *BodyError) Unwrap() error { return e.Err }

// IsHalt reports whether err means polling must stop until an operator intervenes.
func IsHalt(err error) bool {
	var se *StatusError
	return errors.As(err, &se)
}

type Client struct {
	baseURL   string
	userAgent string
	http      *http.Client
}

func NewClient(baseURL, userAgent string, timeout time.Duration) *Client {
	return &Client{
		baseURL:   baseURL,
		userAgent: userAgent,
		http: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				Proxy:                 http.ProxyFromEnvironment,
				DialContext:           (&net.Dialer{Timeout: 30 * time.Second}).DialContext,
				TLSHandshakeTimeout:   10 * time.Second,
				ResponseHeaderTimeout: timeout,
				ForceAttemptHTTP2:     true,
			},
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
}

// URL is the exact request URL for a group, recorded as source_url on events.
func (c *Client) URL(group string) string {
	q := url.Values{"GROUP": {group}, "FORMAT": {"JSON"}}
	return c.baseURL + "/NORAD/elements/gp.php?" + q.Encode()
}

func (c *Client) Fetch(ctx context.Context, group string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.URL(group), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", c.userAgent)

	resp, err := c.http.Do(req)
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		return nil, fmt.Errorf("celestrak: request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, maxErrorBody))
		return nil, &StatusError{Code: resp.StatusCode, Body: string(b)}
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		return nil, &BodyError{Err: err, Prefix: body, Total: len(body)}
	}
	if len(body) > maxBody {
		return nil, &BodyError{Err: ErrBodyTooLarge, Prefix: body[:maxBody], Total: len(body)}
	}
	return body, nil
}
