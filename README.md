# Evently — Event Booking Platform

A production-shaped event booking platform: JWT auth (plus Google Sign-In), role-based access,
MySQL persistence with Flyway-versioned schema migrations, and Redis-backed distributed locking
that prevents overselling seats under concurrent load. Containerized with Docker and deployed
on Railway.

**Live demo:** https://eventbooking-production-e86a.up.railway.app
**API docs:** [API_DOCUMENTATION.md](./API_DOCUMENTATION.md)

---

## Why this project

Booking systems look simple until two people try to buy the last seat at the same millisecond.
This project is built around that problem: correctness under concurrency, not just CRUD.

- **Seat overselling is prevented with a real distributed lock** (Redisson, not a database
  `SELECT ... FOR UPDATE` alone), so the app can scale to multiple instances without racing.
- **Pending bookings expire automatically** — a scheduled job releases seats held by users who
  never completed payment, modeling the hold/confirm flow real ticketing platforms use.
- **Schema changes are version-controlled**, not `ddl-auto: update` — Flyway migrations are the
  single source of truth for the database, matching how teams actually manage schema in production.
- **Auth abuse is rate-limited at the filter level**, before Spring Security or the database get
  involved, so brute-force login attempts and signup spam are rejected cheaply.

## Architecture

```mermaid
flowchart TB
    subgraph Client
        UI["Browser / API client"]
    end

    subgraph App["Spring Boot app (Docker container)"]
        SEC["Spring Security<br/>JWT resource server"]
        RL["Rate limit filter<br/>(per-IP, Redis counter)"]
        CTRL["Controllers<br/>Auth · Users · Events · Bookings · Reviews · Wishlist"]
        SVC["Services"]
        LOCK["DistributedLockService<br/>(Redisson)"]
        JOB["BookingExpiryJob / EventStatusJob<br/>(@Scheduled)"]
        MAIL["EmailService<br/>(async, SMTP)"]
    end

    subgraph External
        GOOGLE["Google OAuth<br/>(Sign-In verification)"]
        SMTP[("SMTP provider")]
    end

    subgraph Data
        MYSQL[("MySQL<br/>Flyway-migrated schema")]
        REDIS[("Redis<br/>locks + rate-limit counters")]
    end

    UI -->|"HTTPS + Bearer JWT"| RL
    RL --> SEC
    SEC -.->|"verify Google ID token"| GOOGLE
    SEC --> CTRL
    CTRL --> SVC
    SVC -->|"acquire lock before seat mutation"| LOCK
    LOCK <--> REDIS
    RL -.->|"INCR / EXPIRE"| REDIS
    SVC -->|"JPA / Hibernate"| MYSQL
    SVC -.->|"booking/signup confirmation"| MAIL
    MAIL -.-> SMTP
    JOB -->|"release expired holds / complete past events"| MYSQL
```

**Request flow for a booking:** the rate-limit filter checks first (cheapest rejection point) →
Spring Security validates the JWT → the controller delegates to `BookingService` → the service
acquires a per-event Redis lock via `DistributedLockService` before touching seat counts, so two
concurrent requests for the same event can't both read-then-write stale availability → the
transaction commits, the lock releases, and a confirmation email fires asynchronously so it
never adds latency to the booking response.

## Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21, Spring Boot 4.1 |
| Web | Spring MVC (`spring-boot-starter-webmvc`) |
| Auth | Spring Security + OAuth2 Resource Server (JWT, HMAC-signed) + Google Sign-In |
| Persistence | MySQL 8, Spring Data JPA / Hibernate |
| Schema migrations | Flyway |
| Caching / locking | Redis + Redisson (distributed locks, rate-limit counters) |
| Email | Spring Mail (async, SMTP — Gmail/any provider), disabled by default |
| Build | Maven |
| Containerization | Docker (multi-stage build), Docker Compose for local dev |
| Deployment | Railway (Docker-based, managed MySQL + Redis) |
| CI | GitHub Actions (see `.github/workflows`) |

## Key features

**Core booking flow**
- Registration and JWT login, plus **Sign in with Google** as an additional auth method — both
  issue the same app JWT, so a Google-authenticated user is indistinguishable from a
  password user everywhere else in the system
- Role-based access (`USER` / `ADMIN`) via method-level `@PreAuthorize` and URL-level Spring
  Security rules
- Admin-managed event CRUD with images/banners, categories, city, and description
- Public search with filtering (category, city, price range, date, minimum seats, free-text)
  and pagination/sorting
- Create → confirm → cancel booking lifecycle, with a 5-minute pending hold that auto-expires
  and releases seats back to the pool
- QR-coded digital ticket (booking ref, event details, attendee name) generated client-side,
  plus "Add to Calendar" (Google Calendar / Outlook / `.ics` download) and event sharing

**Beyond booking**
- Wishlist — save events for later
- Post-event reviews and ratings, gated server-side so only attendees with a confirmed booking
  can review, and only once the event's status has flipped to `COMPLETED`
- User profile page — edit name/phone, change password, view booking history
- Admin dashboard — total events/bookings, revenue, confirmed/pending/cancelled counts, seats
  sold per event
- Event status badges (Upcoming / Sold out / Cancelled / Completed), auto-transitioned by a
  scheduled job as event dates pass
- Dark mode
- Transactional email (signup welcome + booking confirmation) sent asynchronously over SMTP,
  disabled by default so the app runs with zero mail configuration

**Reliability**
- Redisson distributed lock per event ID during seat mutations, plus optimistic locking
  (`@Version`) on the `Event` entity as a second line of defense
- Per-IP fixed-window rate limiter (Redis `INCR`/`EXPIRE`) on `/api/auth/login` and
  `POST /api/users`, rejecting abuse before authentication or database work happens
- Admin bootstrap — an `ADMIN` account is provisioned automatically from environment
  variables on first startup, no manual SQL required
- Every external dependency (SMTP, Google token verification) fails gracefully — a broken
  mail server or unreachable Google API degrades that one feature, never the app itself, and
  never affects the container's health check

## Running locally

### Option 1 — Docker Compose (recommended)

```bash
cp .env.example .env
# edit .env with your own values — see the file for what's required
docker compose up -d --build
```

Then open `http://localhost:8080`. Health check: `GET /actuator/health`.

### Option 2 — Maven, against your own MySQL/Redis

```bash
$env:DB_URL = "jdbc:mysql://localhost:3306/event_booking?createDatabaseIfNotExist=true"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your-password"
$env:ADMIN_EMAIL = "admin@example.com"
$env:ADMIN_PASSWORD = "change-this-password"
$env:JWT_SECRET = "replace-with-a-long-random-secret-at-least-32-characters"
.\mvnw.cmd spring-boot:run
```

Flyway creates the schema automatically on startup — no manual migration step needed.

Email and Google Sign-In are both optional — see `.env.example` for `MAIL_*` and
`GOOGLE_CLIENT_ID`. Leave `MAIL_ENABLED=false` (the default) to run with no mail server at all.

## Running the tests

```bash
./mvnw test
```

Includes a concurrency test (`BookingConcurrencyTest`) that fires simultaneous booking requests
at a limited-seat event to verify the distributed lock actually prevents overselling — the test
that matters most in this codebase.

## Deployment

Deployed on Railway from this repo's `Dockerfile`, with managed MySQL and Redis plugins.
Environment variables are wired through Railway's variable-reference system
(`${{ServiceName.VAR}}`) so database/cache credentials are never hardcoded. See
[`RAILWAY_DEPLOY.md`](./RAILWAY_DEPLOY.md) for the exact setup steps.

## Project structure

```
src/main/java/com/eventbooking/
├── config/       # Security, rate limiting, admin bootstrap
├── controller/   # REST endpoints (auth, users, events, bookings, reviews, wishlist)
├── dto/          # Request/response shapes, decoupled from entities
├── entity/       # JPA entities
├── exception/    # Domain exceptions + centralized handler
├── job/          # Scheduled booking-expiry and event-completion jobs
├── repository/   # Spring Data JPA repositories
└── service/      # Business logic, including the distributed locking and email services
src/main/resources/
├── db/migration/       # Flyway-versioned schema
└── static/              # Frontend (vanilla HTML/CSS/JS — no build step)
    └── js/vendor/        # Vendored, dependency-free QR code generator
```

## License

MIT — see [LICENSE](./LICENSE).
