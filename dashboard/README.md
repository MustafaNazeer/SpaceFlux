# SpaceFlux dashboard

The operator console for SpaceFlux: the current G, R and S space weather levels, a stale banner for each feed past its age limit, the latest close approach screening run with its coverage and suppressed pairs, and the recent alerts with their acknowledgement state. Reading needs no sign in; the operator can sign in to acknowledge alerts.

It is an Angular 22 app. Data comes from the query API's GraphQL endpoint (`POST /api/graphql`, see [docs/api/graphql.md](../docs/api/graphql.md)) through Apollo Client, and the page refreshes every 60 seconds. New alerts also arrive as soon as they are stored, through the live alert subscription over WebSocket (`/api/graphql/ws`), and are added to the top of the recent alerts list; the 60 second refresh stays as the fallback. A Pause updates button in the header stops the refresh and closes the subscription until you resume it. The look follows [docs/design/tokens.md](../docs/design/tokens.md) and the canonical design in [docs/design/dashboard.html](../docs/design/dashboard.html), written as plain component CSS.

The recent alerts can be filtered to close approaches or space weather alerts; the filter lives in the address (`/?kind=close_approach` or `/?kind=space_weather_level`), so Back returns to the same filter. Load older pages further back, 20 alerts at a time. Each alert title opens the alert's own page at `/alerts/{event_id}`, and each object name opens that object's close approaches at `/objects/{norad_cat_id}/alerts`. These two pages load once when opened and are not refreshed.

SpaceFlux is a public data demonstration, not an operational warning system.

## Run it

Node 22.22.3 or later (Angular 22 also accepts Node 24.15.0 or later).

```sh
cd dashboard
npm ci
npm start
```

Then open http://localhost:4200. The dev server proxies `/api` to the query API at `http://127.0.0.1:8081` ([proxy.conf.json](proxy.conf.json)), so the page and the API share one origin and the session and CSRF cookies work as they would behind the ingress. It also forwards the WebSocket upgrade on `/api/graphql/ws` with the browser's `Origin` unchanged, and the API accepts that socket only from exactly `http://localhost:4200`, so a page opened at http://127.0.0.1:4200 gets no live alerts and relies on the 60 second refresh alone. Start the core Compose stack first (see [docs/setup-guide.md](../docs/setup-guide.md)); without it the page loads and says it is not updating.

The pages are routed in the browser, so whatever serves the built app has to answer every path that is not a file or `/api` with `index.html`, as the dev server does. An event id holds `/` and `:`, so the alert page's path carries it as one segment with each `/` written as `%2F`; that server or proxy must pass `%2F` through as it is, not decode it into a `/` or refuse it.

At startup the app calls `GET /api/auth/session` once. That answer says who is signed in and sets the `XSRF-TOKEN` cookie, which Angular's HttpClient echoes in the `X-XSRF-TOKEN` header on every `POST`; every API URL is relative, since HttpClient sends that header only to the page's own origin.

## Sign in and acknowledgement

The Sign in button in the header opens a small form that posts the username and password form encoded to `POST /api/auth/login` ([docs/api/rest.md](../docs/api/rest.md), section 7). Once signed in, the recent alerts list and an alert's own page show who acknowledged each alert and any note, and offer Acknowledge (with an optional note of up to 500 Unicode code points) or Unacknowledge through `POST /api/alerts/acknowledgements` (section 8). An alert's page also lists its whole acknowledgement history from `GET /api/alerts/acknowledgements` (section 9), 20 rows at a time. One object's close approaches and the screening table stay read only.

Sign in and acknowledge requests wait 35 seconds for an answer and are never resent by the page. When an acknowledgement gets no answer in time, loses its connection or meets a server error, the page reads the alert's state again, since the row may already be written, and says the outcome is not confirmed when it cannot tell; a sign in in the same position asks the session instead. While signed in and not paused, each one minute refresh also asks `GET /api/auth/session`, so a session that ended on the server shows as "Session ended, read only". Signing out, or a session found ended, removes every name and note from the page at once. A note being written when the session ends on its own stays in place, read only, until the next sign in; signing out discards it.

The session ends after 30 minutes with no request and 8 hours after sign in. The header warns two minutes before the idle limit, counted from the last request this tab sent that was answered below 500, with a Stay signed in button, and ten minutes before the 8 hour end, which `expires_at` in the session answer gives and the response's `Date` header places on the browser's clock. The alert and object pages load on first use, so the console's first load carries only what it shows.

While a note form is open or an acknowledgement request is out, the recent alerts list holds new alerts above it, as it does while focus is inside it.

## Test and build

```sh
npm test
npm run build
npm run format:check
```

`npm test` runs the Vitest component tests in jsdom. The GraphQL responses in the tests are produced by running each query against the server's own schema file (`query-api/src/main/resources/graphql/schema.graphqls`) with root values built from the committed contract examples in `schemas/alerts/examples`, so a renamed or retyped field fails the tests. The same tests check every query against the API's depth, field count and cost limits.

`npm run build` writes the production bundle to `dist/dashboard/browser`. `npm run format:check` runs Prettier over the TypeScript, CSS and JSON; the component templates are left out (see `.prettierignore`) because their inline spacing is deliberate.

## Fonts

IBM Plex Sans 1.1.0 and IBM Plex Mono 2.5.0 are served from this app's own origin, never from a font CDN. The build copies IBM's unmodified Latin1 and Pi subsets, only the weights in use, from the pinned `@ibm/plex-sans` and `@ibm/plex-mono` packages into `/fonts/`, with each package's `LICENSE.txt` (SIL Open Font License 1.1) beside them.
