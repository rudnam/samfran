# Samfran

An app for two.

A Go API, a React frontend, and PostgreSQL, shipped as one Docker image.

- **Backend:** Go, standard library plus one PostgreSQL driver (`github.com/lib/pq`).
- **Frontend:** React + TypeScript, bundled by Vite and embedded in the Go binary. An introduction page at `/` and the app at `/app/`, both in English and Japanese. Installable as a PWA.
- **Database:** PostgreSQL. Migrations are embedded SQL files, applied on startup.
- **Packaging:** one small, non-root Docker image (under 20 MB) with no shell.
- **Android:** a native app (Kotlin, Jetpack Compose, Material 3) on the same API, with the bus-stop alarm, notifications and sharing from Google Maps (see [android/README.md](android/README.md)). The website stays for browsers; nothing of it is inside the app.

## Quick start

You need Docker. Node is only needed for frontend hot reload.

```sh
cp .env.example .env            # the defaults run it on this machine only
docker compose up -d --build    # http://localhost:8080
```

## The three commands

| Command | What it does | When |
|---|---|---|
| `./verify.sh` | Go vet, Go unit and integration tests, and browser tests, in throwaway containers | Before every commit or deploy. CI runs it too. |
| `./preview.sh` | Builds the working tree (or `--ref <branch>`) and runs it privately at http://localhost:8081 with a fresh database of sample data | To **see** a change, e.g. what an AI just did, before deploying it |
| `./ci-results.sh <number>` | Downloads a pull request's CI screenshots (browser tests and Android screens) into `ci-results/pr-<number>/` | To **see** a change CI just tested, without running anything (works from a Claude Code cloud session, where `gh run download` is blocked) |
| `docker compose up -d --build` | Builds and (re)starts the local stack at http://localhost:8080 | To use it on this machine |

### Previewing a change before you deploy it

```sh
./preview.sh                          # what's in the working tree right now
./preview.sh --ref ai/new-feature     # a branch, without checking it out
./preview.sh --restore backup.sql.gz  # with a copy of production data (see Backups)
./preview.sh down                     # stop it and delete its data
PREVIEW_MINUTES=0 ./preview.sh        # keep it until `down` (default: it stops after 120 minutes)
```

Sign in as `preview@example.com` / `preview-password`. A fresh preview comes with some sample data (`e2e/seed`, which adds it through the API). The preview runs exactly the image production would run, in its own Compose project with its own database, so it cannot touch the live app or its data. `/healthz` shows which version you are looking at. It cleans up after itself: it stops, data and all, after two hours, when a pull request from the branch it shows is merged (it asks GitHub every minute, through the `gh` CLI if it is signed in), or when the Claude Code session in that folder ends. `verify.sh` tears its stack down when it exits, and clears what a killed run left before it starts. Both share one Go module and build cache per app (Docker volumes `samfran-gomod` and `samfran-gobuild`), so a run compiles only what changed.

You can also skip running anything: `./verify.sh` saves screenshots of the main screens at phone and desktop width in `e2e/test-results/`, and CI attaches them to every push and pull request as the `e2e-results` artifact (on a pull request, `./ci-results.sh <number>` downloads them and the Android screens).

### Frontend hot reload

With the local stack running (Quick start), start Vite's dev server. It forwards `/api` to `localhost:8080`:

```sh
cd web && npm install && npm run dev  # http://localhost:5173 (the introduction), /index.html (the app)
```

After Go changes, run `docker compose up -d --build` again.

## Production

Production runs on AWS: one EC2 instance behind CloudFront, shared with the other rudnam apps. The infrastructure, backups and server access are in [rudnam/rudnam-infra](https://github.com/rudnam/rudnam-infra).

- **Deploy:** push to `main`. CI (`.github/workflows/ci.yml`) runs `./verify.sh`, builds the image, pushes it to ECR and starts it on the instance with `deploy/aws.yml`. A failed check stops the deploy.
- **Roll back:** Actions → CI → **Run workflow** on `main`, with the full SHA of an earlier commit. It reuses that commit's image, or rebuilds it.
- **Settings and secrets:** the production `.env` is the SSM parameter `/rudnam/samfran/env`, not a file in this repo.
- **Backups:** nightly database dumps (kept 30 days) and daily disk snapshots (7 days). `./preview.sh --restore <dump>.sql.gz` runs a change against one.
- **Migrations** run on startup and only go forward, and a deploy briefly runs the old and new versions side by side. So every migration must keep the previous release working: adding a table, a nullable column or a column with a default is safe; renaming or dropping a column takes two releases (stop using it, then drop it). Don't roll back past a commit that changed the schema.

### Put a new app on AWS

Until these steps are done, CI only verifies: the deploy job waits for the repository's `AWS_DEPLOY_ROLE_ARN` variable.

1. In rudnam-infra, add the app to `apps` in `terraform/variables.tf` with `live = false`, then `terraform plan` and `apply`. That creates its ECR repository and lets the deploy role and the server use it.
2. Store its production settings: `.env.example`'s variables with real values, as the SecureString `/rudnam/samfran/env` (rudnam-infra README, "App secrets"). Give it a new `POSTGRES_PASSWORD` (`openssl rand -base64 32`).
3. Give the repository the deploy credentials: `scripts/rotate-github-key.sh` in rudnam-infra (it covers every app in `apps`), then set the variables `AWS_DEPLOY_ROLE_ARN` and `DEPLOY_BUCKET` from `terraform output`.
4. Push to `main` and check the deploy job.
5. Set `live = true`, plan and apply. That points the app's DNS name at CloudFront and adds it to Caddy.

## Contributing

1. Fork the repository (or branch, if you have write access) and run it locally (Quick start).
2. Make the change, following `CLAUDE.md` (it applies to people too).
3. Run `./verify.sh` until it prints `VERIFY PASSED`, and look at the change with `./preview.sh`.
4. Open a pull request against `main`. CI runs the same checks and attaches screenshots; nothing deploys until it is merged.

You never need production credentials: pull requests, including ones from forks, run without them.

## Configuration

All settings are environment variables (read in `internal/config`).

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URL` | required | PostgreSQL connection URL |
| `ADDR` | `:8080` | Listen address |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error`. Logs are JSON on stdout. |
| `COOKIE_SECURE` | `true` | Send the session cookie over HTTPS only. Set `false` only for local http. |
| `TRUST_PROXY` | `false` | Read the client IP from the last `X-Forwarded-For` entry, which the reverse proxy adds. Enable only when the app is reachable solely through that proxy. `docker-compose.yml` enables it; `deploy/local.yml` turns it off. |
| `PUBLIC_ORIGIN` | empty | The site's public address, e.g. `https://app.example.com`. Accepted by the CSRF check even if a proxy changes `Host`. |
| `ALLOW_SIGNUP` | `true` | Set `false` to stop new accounts being created, except by `SIGNUP_EMAILS` and invited addresses. Production runs with `false`. |
| `ANDROID_APK` | empty | The Android app's APK (`docker-compose.yml` sets `/downloads/samfran.apk`). While the file exists, the Us tab offers it and the app is told its version. See [android/README.md](android/README.md). |
| `GOOGLE_PLACES_API_KEY` | empty | Finds places to eat with Google Places as their names are typed in the add form, with romaji and readings ("ishimatsu" finds 石松), and fills in Area, Food and the Maps link from the one picked (`internal/restaurant/google.go`). A key from the Google Cloud console with **Places API (New)** enabled, restricted to that API. Without it, the search uses OpenStreetMap. |
| `ANTHROPIC_API_KEY` | empty | Lets the app read photos of shift sheets with Claude (`internal/shift/reader.go`). Without it, shifts are entered by hand. |
| `SIGNUP_EMAILS` | empty | Comma-separated addresses that may create an account even with `ALLOW_SIGNUP=false`: the owner's, who then invites their partner. |
| `AUTH_RATE_LIMIT` | `10` | Sign-in and sign-up attempts per minute per client IP. |
| `ANDROID_PACKAGE`, `ANDROID_CERT_FINGERPRINTS` | empty | Publish `/.well-known/assetlinks.json` for the Android app. See [android/README.md](android/README.md). |
| `DEMO` | as `ALLOW_SIGNUP` | Offer "Try the demo": each visitor gets a throwaway account with sample data in their language (`internal/demo`), without signing up. It has no password and is deleted when they end it or after a day. |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | empty | Show "Continue with Google" on the sign-in page. Needs `PUBLIC_ORIGIN`. See [Sign in with Google](#sign-in-with-google). |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` | empty, `587` | Email a link to confirm each new account's address, and links to reset forgotten passwords (port 587 with STARTTLS, or 465). `MAIL_FROM` is e.g. `Samfran <no-reply@example.com>`. Needs `PUBLIC_ORIGIN`. Without these, nobody is asked to confirm, and "Forgot your password?" isn't offered. |

### Sign in with Google

1. In the [Google Cloud console](https://console.cloud.google.com/apis/credentials), create a project, set up the OAuth consent screen (External; it asks only for the email address, so Google doesn't need to verify the app), then create an **OAuth client ID** of type *Web application*.
2. Add the authorized redirect URI `https://<your domain>/api/auth/google/callback`, the same origin as `PUBLIC_ORIGIN`.
3. Put the client ID and secret in `.env` as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, and redeploy.

A Google sign-in uses the account with the same email if there is one. If that account's email was never confirmed, its password stops working and its other sessions end, because whoever set the password never proved they own the address.

### Confirming email addresses

With the `SMTP_*` settings, new accounts get an email with a link, and the app shows a reminder until it's opened. People can keep using the app meanwhile. Signing in with Google counts as confirmed. Any SMTP provider works (Resend, Amazon SES, Postmark, or a Gmail account with an app password). The email is in Japanese for someone using the app in Japanese.

The same settings turn on **Forgot your password?** on the sign-in page: it emails a link that works once, for an hour, to choose a new password, which signs out every other session.

## What it does

Everything in it is shared between you: the restaurants you've been to (with dated visits and each person's own 1–5 star rating) and the ones you want to try, and one person's work shifts, read from a photo of the monthly sheet and offered as a calendar feed. And a wake-up alarm on the Android app that rings before your bus stop, so you don't sleep past it.

Sign-up is closed in production: only the owner's address (`SIGNUP_EMAILS`) can create an account, and the owner invites their partner from the **Us** tab with an invite link (single use, seven days). Whoever signs up through it, with Google or a password and any email, joins the owner's space.

## How the code is organized

The code is split by **feature**: each one owns its SQL, HTTP handlers and page. The layers underneath are shared and small.

| Path | What it does |
|---|---|
| `cmd/server/` | Entry point: loads config, connects, migrates, serves, and shuts down cleanly on `SIGTERM`. |
| `internal/activity/` | Recent changes: who changed what the two of you share (shifts and their settings, the calendar link, restaurants, invitations), from the app or via Claude. Features record through a `Log` hook; the Us tab shows the latest. |
| `internal/bus/` | Feature: where each person gets off the bus (`/api/bus/stop`), for the Android app's wake-up alarm. The phone watches its own position during a trip; the server never stores it. Finding the stop by name (`search.go`) asks Photon, an OpenStreetMap search. |
| `internal/geo/` | Distances between positions on the map. |
| `internal/config/` | Reads and validates every setting from the environment. |
| `internal/db/` | Connection pool, migrations (`migrations/*.sql`, embedded), shared errors. |
| `internal/httpx/` | HTTP plumbing every feature shares: handlers that return errors, JSON in and out, middleware (security headers, CSRF origin check, logging, panic recovery), rate limiting. |
| `internal/account/` | Feature: sign-up, sign-in, sessions and the `RequireUser` middleware; also Sign in with Google (`google.go`), confirming emails (`verify.go`), password reset (`reset.go`), demo accounts (`demo.go`) and an Android app's bearer-token sign-in (`app.go`). |
| `internal/mailer/` | Sends the app's emails over SMTP (`SMTP_*`). |
| `internal/demo/` | Fills a demo account with sample restaurants, in English and Japanese. |
| `internal/space/` | The space the two of you share: members, display names, invitations, and `Require`, the middleware that gives every other feature the space to scope its queries by. A new account joins its space at sign-up (`account.Config.Created`). |
| `internal/shift/` | Feature: one person's shifts. `codes.go` turns a sheet's codes (`13F`, `普`, `有休`) into times with an editable table of shift lengths, `reader.go` reads a person's row from a photo with Claude, streamed so the apps can show the days as they're read, and who else is at work around each shift (the photo is never stored), `others.go` keeps those whose hours overlap the shift, including a night shift from the day before, `names.go` remembers corrected coworker names and matches close misreadings to known ones, `ics.go` writes the calendar feed, with the 締め申請通知 days. |
| `internal/mcp/` | Feature: Claude as a connector, as in RudWallet: the MCP server at `/mcp` and its OAuth 2.1 sign-in (`oauth.go`). Its tools (`tools.go`) let Claude read a shift-sheet photo on the person's own Claude plan and save the month after they confirm, and list, add, visit and rate restaurants. |
| `internal/restaurant/` | Feature: restaurants, their visits, and each member's rating. A restaurant with no visits is on the want-to-go list. Adding one finds it by name as it's typed (`google.go`, Google Places, when it has a key; else `search.go`, Photon over OpenStreetMap), or reads a Google Maps link (`maps.go`). |
| `internal/server/` | Wires the features into one handler; serves the web app. Its tests run the whole API against a real database. |
| `internal/testdb/` | Gives integration tests an empty, migrated database. |
| `web/src/` | The React app: `api.ts` (typed API client), `App.tsx` (sign-in gate), `components/`, and `features/<name>/` pages. `i18n.tsx`, `language.ts` and `locales/ja.ts` translate it (below); `theme.ts` keeps the light or dark pick (Us → This device → Appearance). |
| `web/landing.html`, `web/src/landing.*` | The introduction page at `/`. Its Japanese is in each element's `data-ja`. |
| `e2e/` | Playwright browser tests, the throwaway stack that `verify.sh` and `preview.sh` use, and `seed/`, the preview's sample data. |
| `.claude/` | Claude Code hooks: make Docker work in cloud sessions, and stop this folder's preview when a session ends. |
| `deploy/` | Where the app runs: `local.yml` (this machine) and `aws.yml` (production). |
| `android/` | The native Android app: `core/*` (models, the API client, the phone's copy of the server's answers in Room, repositories, the design system), `feature/*` (signing in and one module per tab: Food, Shifts, Bus, Us) and `app/` (the activity, tabs, the bus alarm service and the notifications). See [android/README.md](android/README.md). |

### Adding a feature

Follow `internal/restaurant`; a feature is about five files:

1. `internal/db/migrations/00N_<name>.sql`: the tables, numbered one above the highest. Never edit a migration that has been deployed, and never reuse a number, even a deleted one's: a database that ran it would skip the new file.
2. `internal/<name>/<name>.go`: a `Store` (all the SQL, every query scoped by the space) and a `Handler` with `Register(mux, requireSpace)`, its routes behind `space.Handler.Require`. Handlers return errors: `httpx.Errorf(404, "...")` reaches the user, and anything else becomes a logged 500.
3. One line in `internal/server/server.go` to register it.
4. `internal/server/<name>_test.go`: API tests against the real database.
5. `web/src/api.ts` (types and calls), `web/src/features/<name>/` (the page), and `e2e/tests/<name>.spec.js` (a browser test).
6. Optionally, sample data for the preview in `e2e/seed/main.go`.
7. In the Android app, if it should have it: the call in `android/core/network` (`SamFranNetwork`), a repository in `android/core/data`, and the screen in the tab's `android/feature/<tab>`, with its strings in English and Japanese (android/README.md, "Native app").

### Translating

The app is written in English, and the English is the key: `t('Save')` shows "Save", or its entry in `web/src/locales/ja.ts` in Japanese. A key missing from `ja.ts` fails the type check, so nothing ships half-translated. Use `tn()` for counts and `tx()` for text with elements in it. The server's messages stay in English (old clients show them as they are); list each in `jaServer`, and `errorMessage()` shows it in Japanese. The app sends its language as `Accept-Language`, and `httpx.Language(r)` reads it, for anything the server writes, such as emails.

The language follows the device until someone picks one (the picker in the top bar, or the switch on the introduction page); the choice is kept in the browser and shared by both pages.

The Android app has its own strings, in each module's `res/values/strings.xml` and `res/values-ja/strings.xml`, worded as the website's. Its Japanese for the server's messages is `jaServer` itself, copied into `android/core/ui`'s `ServerText.kt`; a test there fails when the two differ, so a message added to `jaServer` is added there too.

## API

| Method | Path | Auth | Returns |
|---|---|---|---|
| POST | `/api/signup`, `/api/login` | – | `{email}` and a session cookie. Body `{email, password}`. |
| POST | `/api/logout` | – | `204` |
| GET | `/api/me` | user | `{email, has_password, confirm_email, demo}` |
| DELETE | `/api/me` | user | `204`. Deletes the account and all its data. Body `{password}`, or `{email}` for accounts without a password. |
| GET | `/api/auth/options` | – | `{google, demo, reset}`: which ways to sign in exist, whether visitors can try a demo, and whether a forgotten password can be reset by email |
| POST | `/api/demo` | – | Body `{tz}` (the visitor's time zone). Signs in to a new demo account filled with sample data, in the `Accept-Language` language; `201` with `/api/me`'s answer. Only when `DEMO` is on; rate-limited like sign-up. |
| GET | `/api/auth/google`, `/api/auth/google/callback` | – | Sign in with Google (redirects) |
| GET | `/api/verify-email?token=` | – | The link in a confirmation email; redirects to the app |
| POST | `/api/verify-email` | user | `204`. Sends the confirmation email again. |
| POST | `/api/password/forgot` | – | Body `{email}`. `204` whether or not an account uses it; if one does, a link to `/app/#/reset?token=…` is emailed (at most one a minute). `404` without the `SMTP_*` settings. Rate-limited like sign-in. |
| POST | `/api/password/reset` | – | Body `{token, password}`, the token from that link (single use, an hour). Sets the password, confirms the email, ends every other session and signs in: returns `/api/me`'s answer. `400` if the link expired or was used. |
| POST | `/api/auth/app-token` | – | For an Android app: trades the one-time code from Google sign-in in the phone's browser for a session token (`internal/account/app.go`). |
| GET | `/api/space` | user | `{members: [{name, email, me}], invites: [{email, created_at}]}` |
| PUT | `/api/space/me` | user | Body `{name}` (empty: the part of the email before the @). The space, as above. |
| POST, DELETE | `/api/space/invite-link` | user | POST makes an invite link, replacing an unused one: `201 {path, expires_at}` (`/join/<secret>`, shown once; single use, 7 days). DELETE cancels it. `/api/space` shows `invite_link: {expires_at}` while one is open. |
| GET | `/join/{secret}` | – | Where an invite link points: keeps the secret in a cookie for sign-up (which it then allows, with any email, even with sign-up closed), and opens `/app/#/join`. |
| POST | `/api/space/invites` | user | Body `{email}`. The space. `409` if the address has an account or is invited elsewhere. Not in a demo. |
| DELETE | `/api/space/invites/{email}` | user | The space, or `404` |
| GET | `/api/restaurants` | user | `[restaurant]`: `{id, name, area, cuisine, map_url, notes, added_by, created_at, visit_count, last_visit, ratings: [{name, me, stars}], visit_notes: [{visited_on, note}]}`. `visit_notes` are the visits with a note, newest first (left out when there are none), so the list can be searched by them. |
| POST | `/api/restaurants` | user | `201` and the restaurant with its `visits`. Body `{name, area, cuisine, map_url, notes}`, plus optionally `visit: {visited_on, note}` (a first visit) and `stars` (your rating). |
| GET | `/api/restaurants/search?q=&lat=&lng=` | user | Places to eat and drink (restaurants, fast food, cafés, bars, bakeries…) whose names match `q` (2 to 100 characters; fewer gives `[]`), up to 8: `[{name, area, street, kind, lat, lng}]`, near `lat`/`lng` first when given (the web page sends the person's bus stop). From Photon (photon.komoot.io, OpenStreetMap data; `internal/restaurant/search.go`). `502` when Photon doesn't answer. |
| GET | `/api/restaurants/suggest?q=&lat=&lng=&lang=&session=` | user | What the add form suggests as a name is typed, up to 8, as `search` gives, from Google Places when `GOOGLE_PLACES_API_KEY` is set. A suggestion from Google has `place_id`, `name`, `street` (an address line) and `kind` (Google's type) only; ask `place/{place_id}` for the rest. Without a key, or when Google fails, from Photon, as `search`. `lang` is `ja` or `en` (else `Accept-Language`); `session` is the form's token, the same for the `place` call, so Google bills them as one. (`internal/restaurant/google.go`) |
| GET | `/api/restaurants/place/{place_id}?lang=&session=` | user | A place Google suggested: `{name, area, street, kind, lat, lng, place_id, food, map_url}`. `name`, `area` and `street` are always in Japanese, as on the shop's sign; `food` is the kind of restaurant in `lang` ("ラーメン屋", "Ramen restaurant"), empty for a plain restaurant; `map_url` is its own Google Maps link. `404` without a key, `502` when Google doesn't answer. |
| POST | `/api/restaurants/lookup` | user | Body `{url, position?}`, a Google Maps link. `{name, map_url, lat?, lng?, found_by_name?}`: the place's name read from where the link leads (`""` if it can't be read). `lat`/`lng` come from the address (a pin, coordinates, or the place ID, `s2.go`); with `position: true` and none of those, they are looked up by the name on OpenStreetMap (`found_by_name`; only when every match is in one spot, `geocode.go`). `400` for anything but a Google Maps address; it follows only Maps redirects (`internal/restaurant/maps.go`). |
| GET | `/api/restaurants/{id}` | user | The restaurant, with `visits: [{id, visited_on, note, added_by}]`, newest first |
| PATCH | `/api/restaurants/{id}` | user | Changes the fields given; others stay. The restaurant. |
| DELETE | `/api/restaurants/{id}` | user | `204`. Its visits and ratings go with it. |
| POST | `/api/restaurants/{id}/visits` | user | `201` and the restaurant. Body `{visited_on: "YYYY-MM-DD", note}`. |
| DELETE | `/api/restaurants/{id}/visits/{visit}` | user | The restaurant |
| PUT | `/api/restaurants/{id}/rating` | user | Body `{stars}`, 1 to 5, or 0 to clear yours. The restaurant. |
| GET | `/api/shifts?month=YYYY-MM` | user | `{month, settings, days: [{date, code, kind, label, start, end, problem, with, others}]}`; the current month by default. `with` (working days only, left out when empty) names the others on the same shift. `others` (left out when empty) are the others on other shifts whose hours overlap it, by start: `[{name, code, day_offset, start, end}]`, where `day_offset` is -1 for a night shift from the day before and 1 for an early shift the next day. Times are worked out from the codes when read. |
| PUT | `/api/shifts/month` | user | Body `{month, days: [{date, code, with?, others?: [{name, code, day_offset?}]}], corrections?: [{from, to}]}`: replaces the month's days (an empty code clears a day). `with`: up to 30 names, 40 characters each; kept for working days only. `others`: up to 80, of which only those whose hours overlap the day's shift are kept. `corrections` are names the photo reader got wrong, as fixed while checking a scan (up to 200): they're remembered, and later scans use them. The month, as above. |
| POST | `/api/shifts/read` | user | Multipart `photo` (JPEG, PNG or WebP, up to 8 MB). Reads the person's row (settings `name_on_sheet`) and answers with the month as above plus `name_as_read` and `notes`; **saves nothing**. A shift with a small 有休 printed under it comes back as 有休 (paid leave), and `notes` lists those days. On working days, `with` lists the others with exactly the same code that day, and `others` the others whose hours overlap the shift (including a night shift from the day before, and the next morning's early shifts a night shift runs into); nothing else about them is kept. Names are matched to what the space already knows: a remembered correction, else the one saved coworker a character or two off (`notes` lists what changed), and the reader is told the known names. `503` without `ANTHROPIC_API_KEY`. Reading takes about a minute, longer than CloudFront waits, so the apps use the two below instead; this stays for app versions already installed. |
| POST | `/api/shifts/reads` | user | The same photo, read in the background: answers `202` `{"id"}` at once (or the same errors as above, checked before reading), or `429` with 3 reads already going in the space. |
| GET | `/api/shifts/reads/{id}` | user | A read started above: `202` `{"status": "reading", "name_as_read", "days_read", "days_in_month"}` until it's done (how far it's got: the name once found, then the days as they're read; `days_read` stays 0 while the sheet is looked over), then exactly what `POST /api/shifts/read` answers (the month, or its error). `404` for another space's read, or one finished over 10 minutes ago or lost to a restart: send the photo again. |
| GET, PUT | `/api/shifts/settings` | user | `{person_name, name_on_sheet, lengths: {letter: hours}, time_zone, closing_days, feed_path, reader}`. `closing_days`: the days of the month the hours are sent in (締め申請通知), marked on the calendar; 31 means the last day (default `[15, 31]`). PUT changes the fields given. |
| POST, DELETE | `/api/shifts/feed` | user | Turns the calendar feed on with a new secret address (`feed_path`), or off. |
| GET | `/api/shifts/feed/{secret}.ics` | – | The calendar feed (iCalendar): shifts and all-day 締め申請通知 events (`closing_days`), two months back to a year ahead, for Google Calendar's "From URL". The secret address is the key. |
| POST | `/mcp` | MCP token | The MCP server (Streamable HTTP, JSON responses only). `Authorization: Bearer <token>`; without one, `401` with `WWW-Authenticate: Bearer resource_metadata="…"`. Tools: `get_shift_settings`, `get_shifts`, `save_month_shifts`, `list_restaurants`, `add_restaurant`, `log_visit`, `rate_restaurant`. |
| GET, DELETE | `/api/mcp/tokens[/{id}]` | user | Claude's connections: `{id, name, created_at, last_used_at, signed_in}`. Deleting one disconnects it. |
| GET | `/.well-known/oauth-protected-resource[/mcp]`, `/.well-known/oauth-authorization-server` | – | Sign-in metadata for MCP clients (RFC 9728, RFC 8414). |
| POST | `/oauth/register` | – | Dynamic client registration (RFC 7591). |
| GET | `/oauth/authorize` | – | Checks the client, redirect URI and PKCE (S256), then sends the browser to the Connect page (`/app/#/connect`). |
| GET, POST | `/api/mcp/authorize` | user | For the Connect page: who is asking, then allow or deny; answers `{redirect_to}`. |
| POST | `/oauth/token` | – | `authorization_code` or `refresh_token` grants: `{access_token, token_type, expires_in: 3600, refresh_token}`. |
| GET | `/privacy` | – | The privacy page (Google's consent screen links to it). |
| GET | `/api/activity` | user | The space's latest 50 changes: `[{id, at, who, me, via, kind, details}]`, newest first. `via` is `app` or `claude`; the app words each `kind` (`shifts_saved`, `shift_settings`, `rated`, …). |
| GET, PUT | `/api/bus/stop` | user | `{set, name, lat, lng, alert_meters, buzz}`: the signed-in person's stop, and how the phone buzzes there (`gentle`, `strong` or `strongest`; `strong` by default). PUT `{name, lat, lng, alert_meters, buzz?}` (200 m to 5 km; 800 by default). Without `buzz`, it stays as it was. |
| GET | `/api/bus/search?q=&lat=&lng=` | user | Bus stops and stations whose names match `q` (2 to 80 characters; fewer gives `[]`), up to 8: `[{name, area, kind: "bus"\|"train", lat, lng}]`, near `lat`/`lng` first when given. From Photon (photon.komoot.io, OpenStreetMap data); a stop's two sides of the road come back as one. `502` when Photon doesn't answer. |
| GET | `/api/downloads` | – | `{android: {url, size, updated, version, version_name} \| null}`: the APK, while there is one. `version` is its `versionCode`, which the app compares with its own. |
| GET | `/download/samfran.apk` | – | The Android app |
| GET | `/healthz` | – | `{status: "ok", version}` if the database is reachable, else `503` |

Errors are `{"error": "message"}` with a matching status code. Requests that change data must come from the site itself (the `Origin` check), and the session cookie is `HttpOnly` and `SameSite=Lax`.
