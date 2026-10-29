# mcp-server

Read only MCP tools over `query-api`, for the assistant ([ADR 0015](../docs/adr/0015-mcp-tool-server.md)). It serves stateless Streamable HTTP at `POST /mcp` on the internal `tools` Compose network, with no published port, and every request needs `Authorization: Bearer` with the token in `deploy/secrets/mcp_server_token`.

## Tools

Each tool makes exactly one `GET` to one read endpoint of `query-api` ([REST API](../docs/api/rest.md)), with no credential. No tool takes a URL, host, path or free form query; names, descriptions and schemas are constants in `ReadTools.java`.

| Tool | Endpoint | Arguments and bounds |
|---|---|---|
| `space_weather_current` | `GET /api/space-weather/current` | none |
| `space_weather_history` | `GET /api/space-weather/history` | `scale` G, R or S; `satellite` 1 or more, required for R and S, refused for G; `from` and `to` RFC 3339 times, years 1000 to 9999, `to` after `from` by at most 7 days; `limit` 1 to 200 (50); `after` a cursor |
| `screening_current` | `GET /api/screening/current` | none |
| `recent_alerts` | `GET /api/alerts/recent` | `limit` 1 to 200 (50); `after` a cursor; `kind` `close_approach` or `space_weather_level` |
| `alert` | `GET /api/alerts/by-id` | `event_id`, at most 512 code points, no control characters |
| `catalog_object` | `GET /api/catalog/{norad_cat_id}` | `norad_cat_id` 0 to 999999999 |
| `watchlist` | `GET /api/watchlist` | none |
| `watchlist_passes` | `GET /api/watchlist/{catalog_number}/passes` | `catalog_number` 0 to 999999999 |

A cursor is base64url, at most 256 characters, as `query-api` returned it in `next`. No tool reads the operator's session, acknowledges an alert, or reads acknowledgement history.

## Limits

Each tool takes at most 30 calls in a clock minute (`spaceflux.mcp.calls-per-minute`); the next call is a tool error until the minute ends. An argument out of bounds is a tool error and calls nothing. An answer from `query-api` other than `200` becomes a tool error carrying its status and, for a `4xx`, the problem body's `detail`.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `QUERY_API_URL` | `http://query-api:8080` | Where `query-api` is |
| `MCP_TOKEN_FILE` | `/run/secrets/mcp_server_token` | The token callers present; at least 32 printable characters with no space, or the service does not start |

## Tests

`ToolServerIntegrationTest` starts the service in front of a stub `query-api` and calls it with the MCP Java SDK's own client. It needs no Docker:

```
./mvnw -pl mcp-server test
```
