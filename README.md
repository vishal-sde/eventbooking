# Evently - Event Booking Website

A full responsive event-booking website with a Spring Boot REST API, MySQL persistence, JWT
authentication, role-based access, and database locking that prevents concurrent overselling.

## Requirements

- Java 21
- MySQL 8+

Create a MySQL user or provide these environment variables before starting:

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/event_booking?createDatabaseIfNotExist=true"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your-password"
$env:ADMIN_EMAIL = "admin@example.com"
$env:ADMIN_PASSWORD = "change-this-password"
$env:JWT_SECRET = "replace-with-a-long-random-secret-at-least-32-characters"
.\mvnw.cmd spring-boot:run
```

Open `http://localhost:8080` to use the website. Public registration creates `USER` accounts; only
the environment-provisioned account receives `ADMIN`. Login returns a two-hour JWT. API clients send
that token in the `Authorization` header:

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType application/json `
  -Body '{"email":"admin@example.com","password":"change-this-password"}'

Invoke-RestMethod -Uri http://localhost:8080/api/users `
  -Headers @{ Authorization = "Bearer $($login.token)" }
```

The application uses MySQL only. The schema is created or updated automatically in the
`event_booking` database. You can inspect it through MySQL Workbench.

## Website features

- Responsive event discovery and filtering
- User registration and JWT login
- Seat booking, totals, booking history, and cancellation
- Admin event creation, editing, and cancellation
- Role-aware navigation and protected API actions

## API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/users` | Create a user |
| `POST` | `/api/auth/login` | Exchange credentials for a JWT |
| `GET` | `/api/auth/me` | Get the authenticated profile |
| `GET` | `/api/users` | List users (admin) |
| `GET` | `/api/users/{id}` | Get a user (admin) |
| `POST` | `/api/events` | Create an event (admin) |
| `GET` | `/api/events?minimumSeats=2` | List/filter events |
| `GET` | `/api/events/{id}` | Get an event |
| `PUT` | `/api/events/{id}` | Update an upcoming event (admin) |
| `DELETE` | `/api/events/{id}` | Cancel an event (admin) |
| `POST` | `/api/bookings` | Book seats (user/admin) |
| `GET` | `/api/bookings?userId=1` | List own bookings, or filter as admin |
| `GET` | `/api/bookings/{reference}` | Get an owned booking, or any as admin |
| `DELETE` | `/api/bookings/{reference}` | Cancel an owned booking, or any as admin |

Example booking request:

```json
{
  "userId": 1,
  "eventId": 1,
  "seatsRequired": 2
}
```
