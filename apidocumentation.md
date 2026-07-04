# API Documentation

Base URL (local): `http://localhost:8080`
Base URL (live): `https://eventbooking-production-e86a.up.railway.app`

All request/response bodies are JSON. Authenticated endpoints require:

```
Authorization: Bearer <jwt>
```

Tokens are obtained from `POST /api/auth/login` and expire after `JWT_EXPIRATION_MINUTES`
(default 120 minutes).

---

## Auth

### `POST /api/auth/login`
Public. Exchanges credentials for a JWT.

**Request**
```json
{ "email": "admin@example.com", "password": "your-password" }
```

**Response** `200`
```json
{
  "token": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresAt": "2026-07-04T18:32:00Z",
  "user": { "id": 1, "name": "Administrator", "email": "admin@example.com", "phone": "...", "role": "ADMIN" }
}
```

Rate-limited per IP (`RATE_LIMIT_LOGIN_CAPACITY`, default 5 attempts / 60s window).

### `GET /api/auth/me`
Authenticated. Returns the profile of the currently logged-in user, derived from the JWT.

**Response** `200` — same shape as `user` above.

---

## Users

### `POST /api/users`
Public. Registers a new user (always created with role `USER`).

**Request**
```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "+911234567890",
  "password": "at-least-8-characters"
}
```
Validation: `name`/`email`/`phone`/`password` all required · email must be valid format ·
phone must match `^[+]?[0-9]{10,15}$` · password 8–72 characters.

**Response** `201`
```json
{ "id": 5, "name": "Jane Doe", "email": "jane@example.com", "phone": "+911234567890", "role": "USER" }
```
Rate-limited per IP (`RATE_LIMIT_SIGNUP_CAPACITY`, default 10 / hour).

### `GET /api/users/{id}` — `ADMIN` only
Returns a single user by ID.

### `GET /api/users` — `ADMIN` only
Returns all users.

---

## Events

### `POST /api/events` — `ADMIN` only
Creates an event.

**Request**
```json
{
  "name": "Tech Conference 2026",
  "venue": "Convention Center",
  "eventDate": "2026-12-01T10:00:00",
  "totalSeats": 500,
  "ticketPrice": 49.99
}
```
Validation: all fields required · `eventDate` must be in the future · `totalSeats` 1–10000 ·
`ticketPrice` cannot be negative.

**Response** `201` — full event object including `availableSeats` (initialized to `totalSeats`),
`status`, and `version` (used for optimistic locking).

### `GET /api/events/{id}`
Public. Returns a single event.

### `GET /api/events`
Public. Search/list with filtering, pagination, and sorting.

| Query param | Type | Default | Notes |
|---|---|---|---|
| `search` | string | — | free-text match |
| `status` | enum | — | filter by event status |
| `minSeats` | int (≥1) | — | only events with at least this many available seats |
| `page` | int | `0` | zero-indexed |
| `size` | int | `10` | page size |
| `sortBy` | string | `eventDate` | field to sort on |
| `sortDir` | string | `asc` | `asc` or `desc` |

**Response** `200` — `PagedResponse<Event>`:
```json
{
  "content": [ /* array of event objects */ ],
  "page": 0,
  "size": 10,
  "totalElements": 42,
  "totalPages": 5,
  "first": true,
  "last": false
}
```

### `PUT /api/events/{id}` — `ADMIN` only
Updates an upcoming event's name, venue, date, or price.

### `DELETE /api/events/{id}` — `ADMIN` only
Cancels an event (soft cancel — sets status, does not hard-delete the row).

---

## Bookings

### `POST /api/bookings`
Authenticated. Creates a booking. This is the endpoint the distributed lock protects — concurrent
requests against the same `eventId` are serialized through Redisson so seat counts can't be
double-decremented.

**Request**
```json
{ "userId": 5, "eventId": 12, "seatsRequired": 2 }
```
Validation: all fields required · `seatsRequired` between 1 and 10.

**Response** `201`
```json
{
  "id": 30,
  "bookingRef": "BK-...",
  "userId": 5,
  "userName": "Jane Doe",
  "eventId": 12,
  "eventName": "Tech Conference 2026",
  "seatsBooked": 2,
  "totalAmount": 99.98,
  "status": "PENDING",
  "bookedAt": "2026-07-04T18:00:00",
  "cancelledAt": null,
  "expiresAt": "2026-07-04T18:05:00"
}
```
`PENDING` bookings expire 5 minutes after creation if not confirmed — `BookingExpiryJob` runs
every 60 seconds and releases the held seats back to the event.

### `POST /api/bookings/{reference}/confirm`
Confirms a pending booking (models the "payment succeeded" step). Moves status from `PENDING`
to `CONFIRMED`, cancelling the expiry.

### `GET /api/bookings/{reference}`
Fetches a booking by its reference code.

### `GET /api/bookings?userId={id}`
Lists bookings, optionally filtered by user.

### `DELETE /api/bookings/{reference}`
Cancels a booking and releases its seats back to the event.

---

## Error format

All errors are handled centrally (`GlobalExceptionHandler`) and returned as JSON, e.g.:
```json
{ "status": 404, "error": "Not Found", "message": "Event not found: 999" }
```

Common status codes:
| Code | Meaning |
|---|---|
| `400` | Validation failure (missing/invalid fields) |
| `401` | Missing/invalid/expired JWT |
| `403` | Authenticated but lacking the required role |
| `404` | Resource not found |
| `409` | Conflict — e.g. duplicate booking, seats unavailable |
| `429` | Rate limit exceeded (login/signup only) |

---

## Trying it against the live deployment

```bash
# Register
curl -X POST https://eventbooking-production-e86a.up.railway.app/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Test User","email":"test@example.com","phone":"+911234567890","password":"password123"}'

# Log in
curl -X POST https://eventbooking-production-e86a.up.railway.app/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'

# Use the returned token
curl https://eventbooking-production-e86a.up.railway.app/api/auth/me \
  -H "Authorization: Bearer <token>"
```