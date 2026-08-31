# URL Shortener — Diagrams

Diagrams for the URL shortener: the class model (`UrlService`, `RateLimiterService`,
`UrlMapping`), the schema, and the read-vs-write flow.

- [1. High-Level Design (HLD)](#1-high-level-design-hld)
- [2. UML Class Diagram](#2-uml-class-diagram)
- [3. Entity-Relationship Diagram](#3-entity-relationship-diagram)
- [4. Read vs Write Flow](#4-read-vs-write-flow)

---

## 1. High-Level Design (HLD)

```mermaid
flowchart TB
    Client[Browser / UI / clients] -->|HTTPS| CDN[CDN / edge cache<br/>optional, hottest codes]
    CDN --> LB[Load Balancer / ALB]
    LB --> App[URL Shortener App<br/>Spring Boot · stateless · HPA 2→20 pods]

    subgraph App_Internal[Application]
      Ctrl[UrlController] --> RL[RateLimiterService<br/>token bucket]
      Ctrl --> Svc[UrlService<br/>Base62 + cache-aside]
    end

    RL -->|atomic Lua| Redis[(Redis<br/>rate-limit buckets + hot-code cache)]
    Svc <-->|cache-aside| Redis
    Svc -->|writes / cache miss| PGw[(PostgreSQL primary<br/>Flyway-managed)]
    Svc -->|reads at scale| PGr[(RDS read replica<br/>optional)]
    PGw -.replication.-> PGr

    App -.metrics.-> Prom[(Prometheus)]
    Prom --> Graf[Grafana]

    note1[Read:write ≈ 100:1<br/>most reads served from Redis]
```

---

## 2. UML Class Diagram

```mermaid
classDiagram
    class UrlController {
      +shorten(ShortenRequest) ResponseEntity
      +redirect(shortCode) 302
      +stats(shortCode) UrlStatsResponse
      +list(user, afterId, limit) List
    }
    class UrlService {
      -Duration CACHE_TTL
      +shorten(req, createdBy) ShortenResponse
      +resolveAndCount(shortCode) String
      +getStats(shortCode) UrlStatsResponse
      +listByUser(user, afterId, limit) List
    }
    class RateLimiterService {
      -long capacity
      -long refillPerSec
      +tryAcquire(clientId) boolean
    }
    class UrlMappingRepository {
      <<interface>>
      +findByShortCode(String) Optional
      +findPageByUser(user, afterId, limit) List
      +incrementClickCount(id) void
    }
    class UrlMapping {
      +Long id
      +String shortCode
      +String longUrl
      +String createdBy
      +Long clickCount
      +Instant expiresAt
      +Instant createdAt
    }
    class Base62 {
      <<utility>>
      +encode(long) String$
      +decode(String) long$
    }

    UrlController --> UrlService
    UrlController --> RateLimiterService
    UrlService --> UrlMappingRepository
    UrlService ..> Base62 : id → code
    UrlMappingRepository ..> UrlMapping : manages
```

---

## 3. Entity-Relationship Diagram

Single-table design (deliberately) — the mapping is a self-contained key→value record.

```mermaid
erDiagram
    URL_MAPPINGS {
      bigint id PK "Base62-encoded into short_code"
      varchar short_code UK "unique index (hot read path)"
      varchar long_url
      varchar created_by "nullable (anonymous)"
      bigint click_count
      timestamptz expires_at "nullable = never"
      timestamptz created_at
    }
```

---

## 4. Read vs Write Flow

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant Ctrl as UrlController
    participant RL as RateLimiter (Redis Lua)
    participant Svc as UrlService
    participant R as Redis
    participant DB as PostgreSQL

    Note over C,DB: WRITE — shorten
    C->>Ctrl: POST /api/v1/shorten
    Ctrl->>RL: tryAcquire(ip)
    RL-->>Ctrl: allowed
    Ctrl->>Svc: shorten(url)
    Svc->>DB: INSERT (get identity id)
    Svc->>Svc: shortCode = Base62.encode(id)
    Svc->>R: cache shortCode→longUrl
    Svc-->>C: 201 {shortCode, shortUrl}

    Note over C,DB: READ — redirect (hot path)
    C->>Ctrl: GET /{code}
    Ctrl->>Svc: resolveAndCount(code)
    Svc->>R: GET code
    alt cache hit (the common case)
        R-->>Svc: longUrl
    else miss
        Svc->>DB: SELECT by short_code
        Svc->>R: populate cache
    end
    Svc-->>C: 302 Location: longUrl
```
