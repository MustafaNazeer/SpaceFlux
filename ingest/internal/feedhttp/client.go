// Package feedhttp is the HTTP client shared by the feed pollers. It never
// follows redirects, bounds every body it reads, and reports a non 200 status
// as a *StatusError so each feed can decide which statuses halt it.
package feedhttp

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"
)

const (
	MaxBody      = 8 << 20
	MaxErrorBody = 512
)

var ErrBodyTooLarge = errors.New("response body exceeds limit")

type StatusError struct {
	Code int
	Body string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("HTTP %d: %s", e.Code, e.Body)
}

// BodyError is a failure after a 200 status line: the body was too large or
// could not be read in full. The provider may already count the download, so
// callers dead letter the prefix and wait a full interval instead of retrying.
type BodyError struct {
	Err    error
	Prefix []byte
	Total  int
}

func (e *BodyError) Error() string {
	return fmt.Sprintf("body after HTTP 200 (%d bytes read): %v", e.Total, e.Err)
}

func (e *BodyError) Unwrap() error { return e.Err }

type Response struct {
	Body        []byte
	ETag        string
	NotModified bool
}

type Client struct {
	userAgent string
	http      *http.Client
}

func NewClient(userAgent string, timeout time.Duration) *Client {
	return &Client{
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

// Fetch GETs url. A non empty etag is sent as If-None-Match, and a 304 reply
// to it is reported as NotModified rather than as an error.
func (c *Client) Fetch(ctx context.Context, url, etag string) (Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return Response{}, err
	}
	req.Header.Set("User-Agent", c.userAgent)
	if etag != "" {
		req.Header.Set("If-None-Match", etag)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return Response{}, ctxErr
		}
		return Response{}, fmt.Errorf("request: %w", err)
	}
	defer resp.Body.Close()

	if etag != "" && resp.StatusCode == http.StatusNotModified {
		return Response{ETag: etag, NotModified: true}, nil
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, MaxErrorBody))
		return Response{}, &StatusError{Code: resp.StatusCode, Body: string(b)}
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, MaxBody+1))
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return Response{}, ctxErr
		}
		return Response{}, &BodyError{Err: err, Prefix: body, Total: len(body)}
	}
	if len(body) > MaxBody {
		return Response{}, &BodyError{Err: ErrBodyTooLarge, Prefix: body[:MaxBody], Total: len(body)}
	}
	return Response{Body: body, ETag: resp.Header.Get("ETag")}, nil
}
