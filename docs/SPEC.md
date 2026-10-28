# ScrobbleCue – Technical Specification

_Version 0.2 · 2026-10-27 · Status: draft, before implementation_

This document describes **how** ScrobbleCue is built: the decisions and their reasons, what lives where and why, APIs, flows and rules. The roadmap and phases live in [PLAN.md](PLAN.md).

Anything not yet decided is marked **(proposed)** and listed under Open questions (section 18).

---

## 1. Scope

ScrobbleCue is a tool that logs records played on a turntable to Last.fm, using the user's Discogs collection as the source of release and track data. **It keeps no user data on its server**: users connect their Last.fm and Discogs accounts, and everything else lives on their own device.

**In scope for v1**
- Connect Last.fm and Discogs.
- Browse and search the Discogs collection (cached on the device).
- Scrobble a vinyl side, a whole record, or selected tracks – instantly (back-dated) or live (in real time).
- Recently played (from Last.fm and the device), most played and not played in a while (from the device).
- Per-track corrections, stored on the device.
- Android app, website, one official backend instance.

**Out of scope for v1**
- Side-based scrobbling for non-vinyl formats (CD, cassette, digital) – whole/selected tracks only.
- Syncing corrections or play logs between devices.
- Barcode scanning, Android widget and Quick Settings tile (v2), iOS app (later).
- Audio recognition or playback, social features.

---

## 2. System overview

```
┌──────────────────────────────── user's device ────────────────────────────────┐
│  Android app / Website                                                        │
│   • encrypted credential bundle (Last.fm + Discogs tokens, sealed)            │
│   • collection cache, release cache, track corrections, play log              │
│   • scrobble queue (offline + live mode)                                      │
└───────┬──────────────────────────────────────────────────────┬────────────────┘
        │ HTTPS + credential bundle                            │ HTTPS, no secrets
        ▼                                                      ▼
┌─────────────────────────────────┐              ┌─────────────────────────────────┐
│ ScrobbleCue backend             │              │ Discogs API (public releases)   │
│ (Spring Boot, stateless)        │              │ MusicBrainz API (track lengths) │
│  • holds the app secrets        │              └─────────────────────────────────┘
│  • signs + forwards requests    │
│  • tracklist preparation        │
│  • stores nothing               │
└──────┬───────────────┬──────────┘
       ▼               ▼
  Last.fm API     Discogs API (OAuth + collection)
```

### Trust boundaries
- **App secrets stay on the server.** The Last.fm shared secret and Discogs consumer secret are needed to sign requests and must never ship in an open-source client.
- **User tokens stay on the device.** The user's Last.fm session key and Discogs access token are useless without the app secrets, and the app secrets are useless without a user's token. The device holds one half, the server the other. The server combines them per request and forgets them.
- **The server stores nothing.** No database, no accounts, no history, no logs containing tokens or listening data.
- **Public data needs no server.** Discogs release data and MusicBrainz data are fetched by clients directly.

---

## 3. Decisions

Each decision lists the alternatives considered and why this one was chosen. Longer write-ups go into `docs/decisions/` as ADRs when the code is written.

### D1. Monorepo
- **Decision:** backend, web, Android (and later iOS) in one repository.
- **Alternatives:** one repository per component.
- **Why:** the API contract changes together with its clients; one change can update backend and clients at once. Simpler for one maintainer, easier to browse as a portfolio.

### D2. Backend: Java 25 + Spring Boot 4
- **Alternatives:** Kotlin + Spring, Node.js, Go.
- **Why:** the maintainer is a Java developer. Java 25 is the current LTS; Spring Boot 4 is the current major version.

### D3. Android: native Kotlin + Jetpack Compose
- **Alternatives:** React Native, Flutter, Java + XML views.
- **Why:** WorkManager (offline queue, live mode), notifications, widget and Quick Settings tile are native APIs. Compose is the current Android UI toolkit and requires Kotlin; Kotlin is easy to pick up from Java.

### D4. Web: React + TypeScript + Vite
- **Alternatives:** Thymeleaf + htmx (server-rendered Java).
- **Why:** the most common web stack, and the website needs real client-side logic (local storage, direct API calls, live-mode timers), which a server-rendered site cannot do without storing data on the server.

### D5. Code-first OpenAPI, generated clients
- **Decision:** the backend publishes an OpenAPI spec (springdoc); TypeScript, Kotlin and later Swift clients are generated from it.
- **Alternatives:** hand-written clients; contract-first (write YAML, generate server).
- **Why:** one source of truth, no duplicated hand-written clients. Code-first keeps the Java code as the definition of the API. CI fails if the committed spec and the code disagree.

### D6. No user data on the server – stateless backend
- **Decision:** the backend has no database and stores nothing about users: no accounts, no tokens, no history, no corrections.
- **Alternatives:** a database with accounts, encrypted tokens, plays and corrections (the earlier design).
- **Why:** ScrobbleCue is a tool, not a service that collects data. With nothing stored there is nothing to leak, back up, export or delete, and GDPR obligations shrink to explaining what is processed in transit. It is also cheaper and simpler to run: the backend is a single stateless container.
- **Consequences:** user tokens live on the device (D7); live mode runs on the device (D11); corrections and play logs are per device (D12).

### D7. Sealed credential bundle on the device
- **Decision:** after connecting Last.fm and Discogs, the backend returns an **encrypted credential bundle** (AES-256-GCM, server key) containing the user's Last.fm session key and username, and Discogs token, token secret and username. The client stores it as an opaque value and sends it with every request; the backend decrypts it in memory, uses it, and forgets it.
- **Alternatives:** giving the raw tokens to the client; storing tokens on the server (needs a database).
- **Why:** the server stays stateless while the tokens are never readable by client code or browser scripts. Tampering is detected by the encryption's authentication tag. The same mechanism works for Android, web and iOS.
- **Consequences:**
  - There are no ScrobbleCue accounts and no ScrobbleCue passwords.
  - "Log out" deletes the bundle from the device. Revoking access entirely is done in the user's Last.fm / Discogs settings.
  - Rotating the server key invalidates all bundles; the backend accepts several key IDs during a rotation, so users are not logged out.

### D8. Discogs access – Option A (start)
- **Decision:** authenticated Discogs requests (OAuth handshake, collection pages) are signed by the backend and forwarded; responses are passed straight through and not stored. Clients cache the collection locally.
- **Alternatives:**
  - **Option B** – backend only signs, the client sends the request from its own IP.
  - **Option C** – user pastes a Discogs personal access token; the client calls Discogs directly.
- **Why:** OAuth 1.0a requires the consumer secret for every signature. Option A is the simplest and most common setup. Logins are rare (≈3 requests per device) and a collection sync is ≈1 request per 100 records, so the shared server-IP limit (60/min ≈ 86,000/day) is ample.
- **Exit plan:** Discogs access sits behind one `DiscogsGateway` interface. Switching to Option B is a configuration change. Before switching, confirm with Discogs API support that server-signed, client-sent requests are acceptable.

### D9. Public data fetched directly by clients
- **Decision:** clients fetch Discogs release details (tracklists) and MusicBrainz track lengths directly.
- **Why:** both are public and need no secret, and the Discogs API allows browser requests (CORS `Access-Control-Allow-Origin: *`, verified). Each user's IP carries its own rate limit (Discogs 25/min unauthenticated, MusicBrainz 1/s).
- **Consequence:** Discogs' documentation says unauthenticated requests get **no image URLs**. In practice (checked 2026-10-27) release responses did include them, but that is undocumented. Cover images therefore come from the collection data (fetched with OAuth through the backend, which includes `cover_image` / `thumb`), not from the direct release request.

### D10. Duplicate protection on the client
- **Decision:** every play gets a local ID; queued scrobbles are removed only after Last.fm confirms them. If a send fails without a clear answer (timeout, lost connection), the client asks Last.fm for the user's recent scrobbles in that time range before retrying and skips tracks already present.
- **Alternatives:** server-side idempotency keys (needs server storage).
- **Why:** Last.fm does not de-duplicate. Checking Last.fm itself – the system of record – catches the one case where a client cannot know whether a send succeeded, without the server remembering anything.

### D11. Live mode runs on the device
- **Decision:** when a side starts, all track timestamps are computed up front. The device then sends "now playing" updates and scrobbles as each track starts and ends.
  - **Android:** pending live scrobbles are stored in Room and sent by WorkManager; this works with the app closed.
  - **Web:** timers run while the tab is open; pending scrobbles are kept in IndexedDB and sent on the next visit if the tab was closed early.
- **Alternatives:** server-side scheduler (needs server storage of tokens and pending scrobbles).
- **Why:** keeps the server stateless (D6). Because timestamps are fixed up front, a scrobble sent late (Android battery optimisation, background browser tabs, closed tab) is still recorded at the right time – only the "now playing" display can lag.

### D12. Corrections and play log are per device
- **Decision:** track corrections and the local play log are stored on the device and not synced.
- **Why:** syncing needs central storage, which contradicts D6. Cross-device "recently played" still works through Last.fm's own history. Export/import of corrections can be added later.

### D13. Stateless tracklist preparation on the backend
- **Decision:** clients send the raw Discogs release JSON, known durations and their local corrections to `POST /v1/tracklists/prepare`; the backend returns tracks grouped by side and cleaned, and stores nothing.
- **Why:** Discogs tracklists have many edge cases (section 9). One tested Java module means Android, web and iOS behave the same, and iOS needs no reimplementation.

### D14. Timestamp maths on the backend
- **Decision:** `POST /v1/scrobbles/timeline` turns a list of tracks plus a start or end time into track timestamps (and live-mode send times).
- **Why:** same reason as D13 – one implementation of the rules (30-second minimum, back-dating, side timing) for every client.

### D15. API versioning by path
- **Decision:** all endpoints under `/v1`; clients send `X-Client-Version`; `GET /v1/meta` returns the minimum supported client version.
- **Why:** Android users update slowly; old app versions must keep working, and very old ones must be told to update.

### D16. Track-length fallback chain
- **Decision:** Discogs duration → MusicBrainz → Last.fm `track.getInfo` → default 3:30. A user correction always wins.
- **Why:** many Discogs releases have no durations, and both instant timestamps and live mode need them.

### D17. v1 tracklist scope: vinyl
- **Decision:** side-based scrobbling for vinyl (any number of discs). Other formats: whole record or selected tracks only.
- **Why:** covers the core use case; vinyl edge cases are handled explicitly (section 9).

### D18. Hosting model
- **Decision:** one official instance run by the maintainer, used by the Play Store app and scrobblecue.app. Self-hosting is optional, for developers.
- **Why:** users need no setup or API keys. Because the backend is stateless, it can scale to zero when idle; a cold start only delays the first request by a few seconds.

### D19. License: MIT
- **Alternatives:** Apache-2.0, GPL-3.0, AGPL-3.0.
- **Why:** simplest permissive license; GPL/AGPL conflict with the App Store, and iOS is planned.

### D20. Manual collection sync
- **Decision:** the collection syncs automatically once after connecting Discogs, then only on request.
- **Why:** keeps Discogs traffic through the server minimal (D8). The UI shows when the collection was last synced.

---

## 4. Repository layout – what lives where and why

```
scrobblecue/
├── backend/            Stateless API. The only component that holds the app secrets.
├── web/                React website. Stores the user's data in the browser.
├── android/            Android app. Stores the user's data on the phone; offline queue.
├── ios/                (later) SwiftUI app on the same API.
├── api/                openapi.json – generated from the backend, committed, used to
│                       generate clients. Committed so client changes are reviewable.
├── docs/               PLAN.md (roadmap), SPEC.md (this file), decisions/ (ADRs),
│                       privacy policy.
├── .env.example        Names of every required secret, no values.
└── .github/workflows/  CI: one workflow per component, triggered by path filters.
```

**Why `api/` at the root:** the spec is shared by all clients and belongs to none of them. Generating clients from a committed file (rather than a running server) makes builds reproducible and lets CI detect drift.

**Why no database or docker-compose:** the backend stores nothing (D6). It runs locally with `./gradlew bootRun`.

---

## 5. Backend design

### 5.1 Package structure – package by feature

```
backend/src/main/java/com/scrobblecue/
├── ScrobbleCueApplication.java
├── credentials/   Credential bundle: sealing, opening, key rotation; OAuth state values
├── lastfm/        Last.fm: sign-in flow, request signing, scrobble, now playing,
│                  recent tracks, track.getInfo
├── discogs/       Discogs: OAuth 1.0a flow, DiscogsGateway (forwarding, Option A/B)
├── tracklist/     Stateless tracklist preparation: parsing, sides, cleanup, corrections
├── timeline/      Stateless timestamp maths for instant and live mode
├── meta/          /v1/meta: API version, minimum client versions
└── common/        Error handling (problem details), security config, rate limiting, clock
```

**Why package by feature, not by layer** (`controllers/`, `services/`, …): everything about one feature sits together, so a change to scrobbling touches one folder, and the boundaries are visible.

Inside a feature the same small set of roles is used:

| Role | Example | Responsibility |
|---|---|---|
| `*Controller` | `ScrobbleController` | HTTP only: validation, DTO mapping, status codes |
| `*Service` | `ScrobbleService` | Rules and orchestration |
| `*Client` | `LastFmClient` | Calls to an external API; nothing else knows its URLs |
| DTO records | `ScrobbleBatchRequest` | Java `record`s for API input/output |

**Rules**
- Controllers never call external clients directly.
- Credentials are opened per request by `credentials` and passed down; nothing holds them beyond the request.
- Time comes from an injected `Clock`, never `Instant.now()`, so timestamp logic is testable.
- No repository layer and no persistence dependencies at all.

### 5.2 Feature responsibilities

| Feature | Owns | Talks to |
|---|---|---|
| `credentials` | `CredentialSealer` (AES-256-GCM), key ring, OAuth `state` values | – |
| `lastfm` | Auth flow, signing, `track.scrobble`, `track.updateNowPlaying`, `user.getRecentTracks`, `track.getInfo` | Last.fm |
| `discogs` | OAuth 1.0a handshake, `DiscogsGateway` | Discogs |
| `tracklist` | `TracklistParser`, `SideGrouper`, `NameCleaner`, correction application | – |
| `timeline` | `TimelineCalculator` | – |

---

## 6. Data

### 6.1 On the server
**Nothing is stored.** Data passes through the backend in memory for the duration of a request only.

| Data in transit | Where it comes from | What the backend does |
|---|---|---|
| Credential bundle | Client | Decrypts, uses for signing, discards |
| Collection pages | Discogs | Passes through to the client |
| Tracklist + corrections | Client | Prepares and returns |
| Scrobbles | Client | Signs and sends to Last.fm, returns the result |

Logs contain no bundles, tokens, usernames or track data – only method, path, status and timing. IP addresses are not logged.

### 6.2 Credential bundle (sealed)
Plaintext structure before encryption:

```json
{
  "v": 1,
  "lastfm":  { "user": "…", "sessionKey": "…" },
  "discogs": { "user": "…", "token": "…", "tokenSecret": "…" },
  "issuedAt": "2026-10-27T19:42:00Z"
}
```

Sealed form: `keyId.base64url(nonce ‖ ciphertext ‖ tag)`. `discogs` is absent until Discogs is connected.

### 6.3 On the device

| Store | Contents | Android | Web |
|---|---|---|---|
| Credentials | Sealed bundle | DataStore, encrypted with an Android Keystore key | `HttpOnly; Secure; SameSite=Strict` cookie set by the backend – not readable by page scripts |
| Collection | Collection items from Discogs (id, title, artist, year, format, cover URL, added date) | Room | IndexedDB (Dexie) |
| Release cache | Raw release JSON per opened record | Room | IndexedDB |
| Duration cache | MusicBrainz / Last.fm lengths per track | Room | IndexedDB |
| Corrections | Per (release, position): title, artist, duration, skip | Room | IndexedDB |
| Play log | Local ID, release, side/scope, mode, start/end time, status | Room | IndexedDB |
| Scrobble queue | Pending scrobbles (offline and live mode) with timestamps | Room | IndexedDB |

"Log out" deletes the credentials. A separate "Clear all data" setting deletes everything.

---

## 7. API

### 7.1 Conventions
- **Base path:** `/v1`. Breaking changes → `/v2`; additive changes stay in `/v1`, and clients ignore unknown fields.
- **Credentials:** Android/iOS send `Authorization: Bearer <sealed bundle>`; the website sends the bundle cookie automatically (`credentials: "include"`).
- **Client version:** `X-Client-Version: android/1.4.0` or `web/1.4.0` on every request.
- **Errors:** RFC 9457 Problem Details (`application/problem+json`) with a stable `type` URI per error, e.g. `…/errors/discogs-not-connected`, `…/errors/lastfm-session-revoked`.
- **Pagination:** `?page=` where the upstream API uses pages (Discogs).
- **Docs:** Swagger UI at `/swagger-ui` in development.

### 7.2 Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/meta` | API version, minimum supported client versions |
| GET | `/v1/me` | Connected usernames (`lastfmUser`, `discogsUser`); the website cannot read its own `HttpOnly` cookie |
| POST | `/v1/auth/lastfm/start` | Returns the Last.fm authorisation URL (with a sealed `state`) |
| GET | `/v1/auth/lastfm/callback` | Last.fm redirects here; backend gets the session key and returns a new bundle to the client |
| POST | `/v1/auth/discogs/start` | Starts Discogs OAuth; returns the URL to open in a browser |
| GET | `/v1/auth/discogs/redirect` | Browser step: gets a request token, keeps its secret in a sealed cookie, redirects to Discogs |
| GET | `/v1/auth/discogs/callback` | Discogs redirects here; backend adds Discogs tokens to the bundle and returns it |
| POST | `/v1/auth/discogs/disconnect` | Returns a bundle without Discogs |
| POST | `/v1/auth/logout` | Web only: clears the bundle cookie |
| GET | `/v1/discogs/collection?page=` | One page (100 records) of the user's collection, forwarded |
| POST | `/v1/tracklists/prepare` | Raw Discogs release + durations + corrections → tracks grouped by side, cleaned |
| POST | `/v1/scrobbles/timeline` | Tracks + `startedAt` or `endedAt` → timestamps (and live-mode send times) |
| POST | `/v1/lastfm/now-playing` | Sends `track.updateNowPlaying` |
| POST | `/v1/lastfm/scrobbles` | Sends up to 50 scrobbles; returns accepted/ignored per track |
| GET | `/v1/lastfm/recent?from=&to=` | The user's recent Last.fm scrobbles (duplicate check, history) |

### 7.3 `POST /v1/lastfm/scrobbles` example

```json
{
  "scrobbles": [
    {
      "artist": "Rick Astley",
      "track": "Never Gonna Give You Up",
      "album": "Never Gonna Give You Up",
      "albumArtist": "Rick Astley",
      "durationSec": 213,
      "timestamp": "2026-10-27T19:38:27Z"
    }
  ]
}
```

Response: one result per scrobble – `ACCEPTED`, or `IGNORED` with Last.fm's reason.

---

## 8. Key flows

### 8.1 Connect Last.fm
1. Client calls `POST /v1/auth/lastfm/start` with `{"client": "WEB"}` or `{"client": "ANDROID"}` → `https://www.last.fm/api/auth/?api_key=…&cb=…`. The callback URL carries a sealed `state` (nonce, client type, expiry, 10 minutes).
2. Client opens it in a browser tab (Android: Custom Tab).
3. User approves; Last.fm redirects to `/v1/auth/lastfm/callback?token=…&state=…`.
4. Backend verifies `state`, calls `auth.getSession` (signed) → session key + username, and seals a new bundle.
5. The bundle is delivered without the server storing it:
   - **Web:** the callback response sets the bundle cookie and redirects to `https://scrobblecue.app/`.
   - **Android:** the callback redirects to a verified App Link, `https://scrobblecue.app/android/auth#bundle=…`. The URL fragment is never sent to any server, and App Link verification (`assetlinks.json`) ensures only the ScrobbleCue app can receive it.

### 8.2 Connect Discogs (Option A)
OAuth 1.0a needs the request-token secret in the last step, but Discogs only issues it after it knows the callback URL – so the flow has one extra browser step.
1. Client calls `POST /v1/auth/discogs/start` with `{"client": …}` and its bundle → URL of `/v1/auth/discogs/redirect?state=…`. The sealed `state` carries the current bundle.
2. Client opens that URL in a browser. The backend gets a request token from `https://api.discogs.com/oauth/request_token` (callback: `/v1/auth/discogs/callback?state=…`), stores the request-token secret in a sealed, 10-minute `HttpOnly` cookie **in the user's browser** (`SameSite=Lax`, path `/v1/auth/discogs`), and redirects to `https://www.discogs.com/oauth/authorize?oauth_token=…`.
3. User approves; Discogs redirects to the callback with `oauth_token` and `oauth_verifier`. The browser sends the cookie along.
4. Backend opens the `state` and the cookie, exchanges the verifier at `/oauth/access_token` (PLAINTEXT signature), calls `/oauth/identity` for the username, seals a new bundle that includes Discogs, and deletes the cookie.
5. Delivered as in 8.1 step 5. The first collection sync starts automatically.

The server keeps nothing between the steps.

### 8.3 Sync the collection
1. Client calls `GET /v1/discogs/collection?page=1`; backend signs and forwards to `/users/{username}/collection/folders/0/releases?page=1&per_page=100` and streams the response back.
2. Client repeats for all pages, writes everything to its local cache, then removes entries no longer in the collection.
3. Runs once after connecting Discogs, then only on pull-to-refresh / refresh button.

### 8.4 Open a record
1. Client loads `https://api.discogs.com/releases/{id}` directly and caches it.
2. For tracks without a duration, client queries MusicBrainz (max 1 request/second) and caches the results.
3. Client calls `POST /v1/tracklists/prepare` with the release JSON, durations and its local corrections → sides and tracks to display.

### 8.5 Scrobble – instant mode
1. User taps **Side A**. Client calls `POST /v1/scrobbles/timeline` with the side's tracks and `endedAt = now`.
2. Client writes the play and its scrobbles to the local queue.
3. Client sends them with `POST /v1/lastfm/scrobbles`; confirmed scrobbles are removed from the queue, ignored ones are kept in the play log with the reason.
4. The record moves to the top of "Recently played".

### 8.6 Scrobble – live mode
1. User taps **Start Side A**. Client calls `POST /v1/scrobbles/timeline` with `startedAt = now` → per track: start time (timestamp, now-playing moment) and end time (send moment).
2. Client queues everything locally and sends "now playing" for track 1.
3. As each track ends, the client sends its scrobble and the next "now playing".
   - **Android:** WorkManager jobs, also with the app closed. A notification shows the current track and a **Stop** button; when the side ends it shows **"Flip to side B?"**.
   - **Web:** browser timers while the tab is open; if the tab is closed, remaining scrobbles are sent on the next visit.
4. Stopping removes the remaining queued scrobbles.

### 8.7 Offline and failed sends
1. Scrobbles stay in the local queue until Last.fm confirms them; the client retries with exponential backoff (Android: WorkManager when the network is back).
2. If a send ended without a clear answer, the client first calls `GET /v1/lastfm/recent` for that time range and removes any tracks already scrobbled (D10).
3. Scrobbles older than 14 days are rejected by Last.fm; the client warns before they reach that age.

---

## 9. Tracklist processing rules (`tracklist` feature)

### 9.1 Parsing positions

| Pattern | Example | Result |
|---|---|---|
| `^([A-Z]{1,2})(\d*)([a-z]?)$` | `A1`, `B12`, `AA`, `A1a` | Side = letters (`A`, `AA`), track = number, sub-track = letter |
| `^(\d+)[-.](\d+)$` | `1-3`, `2.1` | Disc + track; no sides |
| `^\d+$` | `7` | Track only; no sides |
| anything else | `""`, `Video`, `Bonus` | Unassigned – selectable manually, not in any side |

Discogs entries with `type_ = heading` are dropped; `type_ = index` entries are replaced by their `sub_tracks`.

### 9.2 Grouping
- A release is **side-scrobblable** if its format includes Vinyl and at least one position has side letters.
- Sides are ordered alphabetically (`A, B, C, …`); `AA` is its own side (7" double A-sides).
- Non-vinyl or side-less releases return a single group: whole record / selected tracks.

### 9.3 Cleaning
- Strip Discogs disambiguation suffixes: `Artist (2)` → `Artist`.
- Use each track's own artists for compilations; the release artist becomes Last.fm `albumArtist`.
- Join multiple artists using Discogs `join` values (`&`, `feat.`, `,`).
- Trim whitespace and collapse duplicate spaces. No other renaming – Last.fm's own corrections handle the rest.
- Apply the client's corrections last (title, artist, duration, skip).

### 9.4 Timestamps (`timeline` feature)
- **Instant mode:** the last track ends at `endedAt`. Each track's timestamp (its start) = `endedAt − sum(durations of this and all later tracks)`.
- **Live mode:** track 1 starts at `startedAt`; each next track starts when the previous one ends. Send time = track end.
- Tracks of 30 seconds or less are not scrobbled (Last.fm rule) but still count for timing.
- Durations come from the D16 fallback chain.

### 9.5 Tests
Every rule has a unit test using a real Discogs release JSON stored as a fixture in `backend/src/test/resources/discogs/`.

---

## 10. Security

| Topic | Approach |
|---|---|
| Transport | HTTPS everywhere; HSTS on the website (`.app` domains enforce HTTPS anyway) |
| App secrets | Environment variables on the host; never in git, never in clients |
| User tokens | Only inside the sealed bundle (AES-256-GCM), on the user's device |
| Sealing keys | `CREDENTIAL_KEYS` holds one or more keys with IDs; new bundles use the newest key, old ones are accepted until removed |
| Web storage | Bundle in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `api.scrobblecue.app`; page scripts cannot read it. Requires the website and API on the same site (`scrobblecue.app` / `api.scrobblecue.app`) |
| Android storage | Bundle in DataStore, encrypted with an Android Keystore key |
| CSRF (web) | `SameSite=Strict` cookie, plus a required `X-ScrobbleCue` header on cookie-authenticated non-GET requests (403 `csrf-header-missing` otherwise). Bearer requests (Android) are not affected |
| CORS | Only `WEB_BASE_URL` may call the API from a browser, with credentials |
| OAuth flows | Sealed `state` with nonce and 10-minute expiry, verified on every callback. A forged state gets a 401 problem; a genuine but expired one redirects back to the client with `authError=<provider>-expired` (web) or `#error=<provider>-expired` (Android) |
| Revoked tokens | Last.fm error 9 or Discogs 401 → problem response; the client asks the user to reconnect |
| Rate limiting | In-memory per-bundle limits on scrobbling and collection forwarding, to protect the shared Discogs limit |
| Logging | No bundles, tokens, usernames, track data or IP addresses |
| Repository | Secret scanning + push protection enabled; `.env` git-ignored |

---

## 11. Reliability and error handling

### Last.fm
| Error | Meaning | Client handling |
|---|---|---|
| 9 | Invalid session key | Keep the queue; ask the user to reconnect Last.fm |
| 11, 16 | Service offline / temporarily unavailable | Retry with exponential backoff (stop before the 14-day limit) |
| 29 | Rate limit exceeded | Back off |
| `ignoredMessage` | Scrobble rejected (too old, bad artist, …) | Remove from the queue, show the reason in the play log |

The backend maps these to Problem Details types; clients decide what to do.

### Discogs
- 429 → backend returns 429 with `Retry-After`; the client waits and resumes the sync.
- 401 → `discogs-session-revoked` problem; the client asks the user to reconnect.
- Forwarded requests go through a token-bucket limiter set below 60/min.

### Backend restarts
Nothing is lost: the backend holds no state. In-flight requests fail and the client retries (D10 prevents duplicates).

---

## 12. Clients

### 12.1 Android
- **Architecture:** single activity, Compose screens, MVVM (`ViewModel` + `StateFlow`), Hilt for dependency injection.
- **Packages:** `ui/` (screens, navigation), `data/` (Room, DataStore, generated API client, Discogs/MusicBrainz clients, repositories), `work/` (WorkManager: queue sending, live mode), later `widget/` and `tile/`.
- **Package name:** `com.scrobblecue.app` (permanent once published on Google Play).

### 12.2 Web
- **Structure:** `pages/`, `components/`, `api/` (generated client), `external/` (Discogs + MusicBrainz), `store/` (IndexedDB via Dexie), `live/` (live-mode timers and queue).
- **Libraries:** React Router for routing, TanStack Query for server state (API calls, caching, loading states), Dexie for IndexedDB.

### 12.3 Shared behaviour
Both clients implement the same flows (section 8) against the same generated API; they differ only where the platform does (background work, notifications, credential storage).

---

## 13. Testing

| Level | Backend | Android | Web |
|---|---|---|---|
| Unit | JUnit 5, AssertJ; tracklist rules, timeline maths, credential sealing | JUnit, Turbine; queue and duplicate-check logic | Vitest; queue and live-mode logic |
| Integration | Spring Boot tests with MockMvc; Last.fm and Discogs replaced by Spring's **MockRestServiceServer** (canned responses) or Mockito mocks | Room in-memory DB, WorkManager test helpers | Fake IndexedDB |
| API contract | Generated spec checked in CI; clients regenerated and compiled | Compiles against generated client | Compiles against generated client |
| UI / end-to-end | – | Compose UI tests | Playwright |

External APIs are never called in automated tests. Manual testing uses a **separate Last.fm test account** so the maintainer's real profile stays clean.

---

## 14. Configuration

Environment variables (listed in `.env.example`):

| Variable | Used for |
|---|---|
| `LASTFM_API_KEY`, `LASTFM_SHARED_SECRET` | Last.fm API |
| `DISCOGS_CONSUMER_KEY`, `DISCOGS_CONSUMER_SECRET` | Discogs OAuth |
| `DISCOGS_USER_AGENT` | Required by Discogs, e.g. `ScrobbleCue/0.1 +https://scrobblecue.app` |
| `CREDENTIAL_KEYS` | Sealing keys with IDs, e.g. `k2:base64…,k1:base64…` (newest first) |
| `PUBLIC_BASE_URL` | Callback URLs (`http://localhost:8080` locally) |
| `WEB_BASE_URL`, `ANDROID_AUTH_LINK` | Where to send users after connecting |
| `MIN_CLIENT_VERSION_ANDROID`, `MIN_CLIENT_VERSION_WEB` | `/v1/meta` |

Each environment (local, staging, production) uses its own Last.fm and Discogs app registrations.

---

## 15. Environments and deployment

| Environment | Purpose | Notes |
|---|---|---|
| Local | Development | `./gradlew bootRun`; web via `npm run dev`; Android via Android Studio |
| Staging | Test before release | Same container as production, separate keys and test Last.fm account |
| Production | Official instance | One stateless container; can scale to zero when idle |

- **Host:** not decided. Requirements: HTTPS with a custom domain, environment-variable secrets, EU region preferred. No database or always-on requirement.
- **CI (GitHub Actions):** build + test per component on every push and pull request, path-filtered (`backend/**`, `web/**`, `android/**`).
- **Delivery:** backend as a Docker image; web as static files (host or CDN); Android as an AAB uploaded to Play Console, signed with Play App Signing.
- **Versioning:** semantic versioning per component, `CHANGELOG.md` per release.

---

## 16. Privacy

ScrobbleCue stores no personal data on its server.

| Data | Where | Retention |
|---|---|---|
| Last.fm and Discogs tokens | User's device, sealed | Until the user logs out or clears data |
| Collection, corrections, play log | User's device | Until the user clears data or uninstalls |
| Credentials and scrobbles in transit | Backend memory | Duration of one request |

- No accounts, no analytics, no advertising trackers.
- A short privacy policy is still required (Google Play, and GDPR transparency): it explains what is processed in transit and that nothing is stored.
- Google Play Data safety form: data processed only in transit may qualify as "ephemeral processing" – verify against Google's definitions when filling in the form.
- Play's account-deletion requirement applies to apps that let users create accounts; ScrobbleCue has none – verify when submitting.

---

## 17. Third-party API terms

### 17.1 Last.fm ([API Terms of Service](https://www.last.fm/api/tos))

| Requirement | How ScrobbleCue meets it |
|---|---|
| Non-commercial use only; commercial use needs a separate agreement | Free, open source, no monetisation |
| Store at most a small portion of Last.fm data (max 100 MB); caching must follow the HTTP cache headers of API responses | Server stores nothing. Clients cache Last.fm responses (e.g. recent tracks) only as long as their `Cache-Control` headers allow, which the backend passes through unchanged |
| Show a **"powered by AudioScrobbler"** button linking to Last.fm | Shown in the website footer, the Android "About" screen, and next to any list of Last.fm data |
| Link Last.fm data back to Last.fm: profiles to the user's profile, artists/albums/tracks to their catalogue pages | Every artist, album and track shown from Last.fm data (e.g. "Recently played") links to its Last.fm page; the connected username links to the profile |
| Credit Last.fm wherever its data is used | "Data from Last.fm" next to Last.fm-sourced lists |
| Button/link placements and public pages using the API need **prior approval** | Email partners@last.fm before public release (phase 7) with screenshots of the placements |
| Last.fm logos and marks need written approval and must not imply endorsement | No Last.fm logo used; "Not affiliated with Last.fm" in the README, website footer and store listing |
| Publicity about using the API needs prior written consent | No announcements naming Last.fm (e.g. "official", "partner") without consent |
| Privacy standards at least as strict as Last.fm's; don't use data to identify or contact users | Nothing stored; no accounts or contact details exist |
| Images and artwork are excluded from the licence | Cover art comes from Discogs, never from Last.fm |
| Delete Last.fm data when access ends | Nothing to delete on the server; clients clear their caches on log out |
| Last.fm may terminate access at any time, with or without cause | Accepted risk; see PLAN.md |

The approval email also asks whether the name "ScrobbleCue" is acceptable.

### 17.2 Discogs
- Attribution: "Data provided by Discogs" wherever Discogs data is shown (collection, record pages, README).
- Cover images follow Discogs' image usage rules: displayed from Discogs URLs, not copied to any server.
- Rate limits and User-Agent requirements: see D8, D9 and section 14.

---

## 18. Open questions

| Question | Decide by |
|---|---|
| Hosting provider | Phase 7 |
| Whether Discogs accepts Option B (email support) | Only if switching is needed |
| "Scrobble" trademark check | Before public release |
| Last.fm approval of attribution placements and the name | Phase 7, before public release |
