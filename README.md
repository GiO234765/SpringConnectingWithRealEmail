# Email OTP Service

A Spring Boot REST service that sends one-time passwords (OTP) over real email and verifies them.

Built with **Spring Boot 4.1.1**, **Java 21**, Spring Mail (Gmail SMTP), and springdoc-openapi for
interactive API docs.

---

## Features

- **Real email delivery** over Gmail SMTP with STARTTLS.
- **6-digit OTP** generated with `SecureRandom`, configurable length.
- **Single-use codes** — a verified OTP is consumed immediately.
- **Expiry** — codes stop working after a configurable TTL (default 5 minutes).
- **Brute-force protection** — a wrong code is allowed only `otp.max-attempts` tries (default 3)
  before the code is destroyed.
- **Resend throttling** — a per-address cooldown prevents mail-bombing an inbox or getting the
  sending Gmail account suspended.
- **Codes stored hashed** (SHA-256) and compared in constant time.
- **Scheduled cleanup** of expired codes so memory cannot grow unbounded.
- **RFC 9457 problem responses** for every error case.
- **No credential leakage** — SMTP internals are logged, never returned to the caller.

---

## Requirements

- Java 21 or newer
- Maven 3.9+
- A Gmail account with **2-Step Verification enabled**

---

## Configuration

All secrets come from **environment variables** — nothing sensitive is committed.

| Variable         | Required | Default            | Purpose                                  |
| ---------------- | -------- | ------------------ | ---------------------------------------- |
| `MAIL_USERNAME`  | **yes**  | *(empty)*          | SMTP account, e.g. `you@gmail.com`       |
| `MAIL_PASSWORD`  | **yes**  | *(empty)*          | **App Password** (16 chars, not account) |
| `MAIL_HOST`      | no       | `smtp.gmail.com`   | SMTP server                              |
| `MAIL_PORT`      | no       | `587`              | SMTP port                                |
| `MAIL_FROM`      | no       | `no-reply@example.com` | Sender address                       |
| `MAIL_TIMEOUT_MS`| no       | `5000`             | SMTP connection/read/write timeout       |

### Getting a Gmail App Password

A normal Google account password will **not** work — Gmail rejects it.

1. Enable 2-Step Verification on the Google account.
2. Open <https://myaccount.google.com/apppasswords>.
3. Generate an App Password (16 characters).
4. Use that value as `MAIL_PASSWORD`.

### Running it

```bash
# macOS / Linux
export MAIL_USERNAME="you@gmail.com"
export MAIL_PASSWORD="abcd efgh ijkl mnop"
export MAIL_FROM="you@gmail.com"
./mvnw spring-boot:run
```

```powershell
# Windows PowerShell
$env:MAIL_USERNAME = "you@gmail.com"
$env:MAIL_PASSWORD = "abcd efgh ijkl mnop"
.\mvnw.cmd spring-boot:run
```

> Environment variables are read **at startup only** — restart after changing them.
> In IntelliJ: *Run → Edit Configurations → Environment variables*.

**Prefer a file?** Create `application-local.properties` (already git-ignored):

```properties
spring.mail.username=you@gmail.com
spring.mail.password=abcd efgh ijkl mnop
otp.from=you@gmail.com
```

Then run with `--spring.profiles.active=local`.

> Spring Boot does **not** read a `.env` file automatically — use one of the options above.

On startup the app logs its SMTP target and **warns loudly** if credentials are missing, so a
misconfiguration shows up at boot rather than as a 502 on the first request.

---

## API

Base URL: `http://localhost:8080`

Interactive docs: <http://localhost:8080/swagger-ui.html>

### `POST /api/auth/send-otp`

```bash
curl -X POST http://localhost:8080/api/auth/send-otp \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com"}'
```

**200**
```json
{ "status": "SENT", "message": "If the address is valid, an OTP has been sent." }
```

The response is deliberately generic so it cannot be used to discover which addresses exist.

### `POST /api/auth/verify-otp`

```bash
curl -X POST http://localhost:8080/api/auth/verify-otp \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","otp":"123456"}'
```

**200**
```json
{ "status": "SUCCESS", "message": "OTP verified successfully!" }
```

### Error responses

Every error uses RFC 9457 `application/problem+json`:

```json
{
  "type": "https://example.com/problems/400",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request body is invalid.",
  "instance": "/api/auth/send-otp",
  "errors": { "email": "Invalid email format" }
}
```

| Status | When                                                            |
| ------ | --------------------------------------------------------------- |
| `400`  | Malformed body / failed `@Email` or 6-digit OTP validation       |
| `401`  | Wrong, unknown or expired OTP                                    |
| `423`  | Too many wrong guesses — the code is destroyed                   |
| `429`  | Resend requested during the cooldown (includes `Retry-After`)    |
| `502`  | SMTP refused the message (see the cause breakdown below)         |

`502` detail messages are actionable without leaking secrets:

- rejected credentials → *"Gmail requires an App Password, not your account password"*
- unreachable server → *"some networks block port 587"*

---

## Configuration reference

Tunable under the `otp.*` prefix in `application.properties`:

| Property                | Default                | Meaning                                    |
| ----------------------- | ---------------------- | ------------------------------------------ |
| `otp.length`            | `6`                    | Digits per code (4–10)                     |
| `otp.ttl`               | `5m`                   | How long a code stays valid                |
| `otp.max-attempts`      | `3`                    | Wrong guesses allowed per code             |
| `otp.resend-cooldown`   | `1m`                   | Minimum wait between codes to one address  |
| `otp.cleanup-interval`  | `60000`                | Expired-code purge period (ms)             |
| `otp.from`              | `no-reply@example.com` | Sender address                             |
| `otp.subject`           | `Your verification code`| Email subject                             |

Bound in `OtpProperties` via `@ConfigurationProperties` and validated at startup.

---

## Project layout

```
src/main/java/com/example/
├── EmailApplication.java              # entry point; scan root + config scan + scheduling
├── Controller/
│   └── OtpController.java             # POST /api/auth/{send-otp,verify-otp}
├── config/
│   ├── MailCredentialsValidator.java  # startup warning for incomplete SMTP config
│   └── OtpProperties.java             # @ConfigurationProperties("otp")
├── exception/
│   ├── ApiExceptionHandler.java       # maps exceptions to RFC 9457 responses
│   ├── InvalidOtpException.java
│   ├── OtpCooldownException.java
│   └── TooManyOtpAttemptsException.java
└── otp/
    ├── dto/
    │   ├── OtpRequest.java            # { email }
    │   └── OtpVerificationRequest.java# { email, otp }
    └── service/
        └── OtpService.java            # generation, delivery, verification, cleanup
```

> `EmailApplication` **must** sit in the root `com.example` package. Spring Boot only scans its own
> package and below, so a main class in `com.example.Email` would silently leave the controller and
> service unregistered — every endpoint would return 404 with no other symptom.

---

## Build and test

```bash
./mvnw clean test        # 12 tests
./mvnw clean package    # runnable jar in target/
java -jar target/Email-0.0.1-SNAPSHOT.jar
```

`OtpServiceTest` covers correct/incorrect codes, single-use semantics, expiry, attempt lockout,
resend cooldown, email case-normalisation, and cleanup after a failed delivery.

---

## Notes and limitations

- **OTP state is in-memory.** Codes are lost on restart and are not shared across instances. For a
  multi-instance deployment, move storage to Redis or the database and replace the scheduled
  `@Scheduled` purge with TTL-based expiry.
- **Rate limits are per-process and in-memory**, so they reset on restart.
- **No CAPTCHA or IP-level throttling.** The current limits stop per-address brute force; add a
  gateway-level limiter if the service is publicly exposed.
- **Plain-text emails.** No HTML template is used yet.
