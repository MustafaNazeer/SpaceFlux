# SpaceFlux dashboard

The operator console for SpaceFlux: the current G, R and S space weather levels, a stale banner for each feed past its age limit, the latest close approach screening run with its coverage and suppressed pairs, and the recent alerts with their acknowledgement state. It is read only for now.

It is an Angular 22 app. Data comes from the query API's GraphQL endpoint (`POST /api/graphql`, see [docs/api/graphql.md](../docs/api/graphql.md)) through Apollo Client, and the page refreshes every 60 seconds. A Pause updates button in the header stops the refresh until you resume it. The look follows [docs/design/tokens.md](../docs/design/tokens.md) and the canonical design in [docs/design/dashboard.html](../docs/design/dashboard.html), written as plain component CSS.

SpaceFlux is a public data demonstration, not an operational warning system.

## Run it

Node 22.22.3 or later (Angular 22 also accepts Node 24.15.0 or later).

```sh
cd dashboard
npm ci
npm start
```

Then open http://localhost:4200. The dev server proxies `/api` to the query API at `http://127.0.0.1:8081` ([proxy.conf.json](proxy.conf.json)), so the page and the API share one origin and the session and CSRF cookies work as they would behind the ingress. Start the core Compose stack first (see [docs/setup-guide.md](../docs/setup-guide.md)); without it the page loads and says it is not updating.

At startup the app calls `GET /api/auth/session` once. That answer says who is signed in and sets the `XSRF-TOKEN` cookie, which Angular's HttpClient echoes in the `X-XSRF-TOKEN` header on every GraphQL `POST`.

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
