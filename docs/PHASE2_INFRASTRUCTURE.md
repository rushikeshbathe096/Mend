# Phase 2: Infrastructure Foundation

## Overview

Phase 2 establishes the local development infrastructure foundation for Mend. This includes:

- **PostgreSQL Database**: Primary data store for business state
- **Redis Cache**: Queuing, scheduling, and temporary state management
- **Docker Compose**: Container orchestration for local development
- **Health Checks**: Infrastructure status monitoring
- **Environment Configuration**: Secure, environment-based credential management

## Prerequisites

- Docker and Docker Compose installed
- Java 21 or later
- Maven 3.6+

## Quick Start

### 1. Start Infrastructure

```bash
cd /path/to/mend
docker compose up -d
```

### 2. Verify Services are Healthy

```bash
docker compose ps
```

All services should show `(healthy)` status. You can also check individual services:

```bash
# PostgreSQL
docker compose exec postgres pg_isready -U mend_user

# Redis
docker compose exec redis redis-cli ping
```

### 3. Run Backend

```bash
cd backend
./mvnw spring-boot:run
```

The backend will connect to PostgreSQL at `postgres:5432` and Redis at `redis:6379` (when running inside Docker) or `localhost:5432` and `localhost:6379` (when running locally).

### 4. Test Health Endpoint

```bash
curl http://localhost:8080/api/health
```

Expected response:
```json
{
  "status": "UP",
  "service": "mend-backend"
}
```

## Infrastructure Services

### PostgreSQL

- **Image**: postgres:17-alpine
- **Port**: 5432
- **Database**: mend
- **Username**: mend_user (default)
- **Password**: mend_password (default)
- **Volume**: postgres_data (named volume for persistence)
- **Health Check**: Uses `pg_isready` command

**Configuration**:
- Uses environment variables for credentials (see `.env.example`)
- Configured for schema validation (no automatic schema creation in Phase 2)
- Connection string: `jdbc:postgresql://postgres:5432/mend`

### Redis

- **Image**: redis:7-alpine
- **Port**: 6379
- **Volume**: redis_data (named volume for optional persistence)
- **Health Check**: Uses `redis-cli ping` command
- **Persistence**: AOF (Append-Only File) enabled

**Configuration**:
- Uses environment variables for host/port (see `.env.example`)
- Minimal configuration for Phase 2
- Connection: `redis://redis:6379` (inside Docker) or `redis://localhost:6379` (local)

## Environment Configuration

### .env File

Create a `.env` file in the repository root based on `.env.example`:

```bash
# PostgreSQL Configuration
POSTGRES_DB=mend
POSTGRES_USER=mend_user
POSTGRES_PASSWORD=mend_password

# Spring Boot Database Configuration
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/mend
SPRING_DATASOURCE_USERNAME=mend_user
SPRING_DATASOURCE_PASSWORD=mend_password

# Redis Configuration
REDIS_HOST=redis
REDIS_PORT=6379
```

**Security Note**: The `.env` file is **NOT** committed to Git. Use `.env.example` as a template and configure actual credentials in your `.env` file.

## Docker Compose Commands

### Start Infrastructure

```bash
docker compose up -d
```

Starts all services in detached mode.

### Stop Infrastructure (preserves data)

```bash
docker compose down
```

Stops containers but preserves volumes. PostgreSQL and Redis data will persist.

### Remove Infrastructure and Data

```bash
docker compose down -v
```

Stops containers and removes all named volumes. This will DELETE all data.

### View Logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f postgres
docker compose logs -f redis
```

### Restart Services

```bash
# Restart all
docker compose restart

# Restart specific service
docker compose restart postgres
```

## Database Connectivity

### From Backend (Inside Docker Network)

When running the Spring Boot backend inside a Docker container, use:
- Host: `postgres` (Docker service name)
- Port: `5432`
- URL: `jdbc:postgresql://postgres:5432/mend`

### From Local Machine

When running the backend locally:
- Host: `localhost` or `127.0.0.1`
- Port: `5432`
- URL: `jdbc:postgresql://localhost:5432/mend`

### Direct Connection

```bash
# From host machine
psql -h localhost -U mend_user -d mend

# From Docker container
docker compose exec postgres psql -U mend_user -d mend
```

## Redis Connectivity

### From Backend

```
# Inside Docker network
redis://redis:6379

# Local machine
redis://localhost:6379
```

### Test Connectivity

```bash
# Ping via docker-compose
docker compose exec redis redis-cli ping

# Response: PONG

# Check running info
docker compose exec redis redis-cli INFO
```

## Data Persistence

### PostgreSQL Persistence

PostgreSQL data is stored in a named Docker volume `mend_postgres_data`. This volume:
- Persists when containers are stopped (`docker compose down`)
- Survives individual container restarts
- Only deleted when using `docker compose down -v`

**To verify data persistence**:

```bash
# Insert test data
docker compose exec postgres psql -U mend_user -d mend -c \
  "CREATE TABLE test (id SERIAL PRIMARY KEY, value TEXT); \
   INSERT INTO test (value) VALUES ('test_data');"

# Stop and restart
docker compose down
docker compose up -d
sleep 5

# Verify data persists
docker compose exec postgres psql -U mend_user -d mend -c "SELECT * FROM test;"
```

### Redis Persistence

Redis persistence is configured with AOF (Append-Only File) mode. Data is saved to `redis_data` volume and will persist across restarts.

## Health Checks

Each service has a health check configured:

### PostgreSQL Health Check
```bash
docker compose exec postgres pg_isready -U mend_user
```

Expected output:
```
/var/run/postgresql:5432 - accepting connections
```

### Redis Health Check
```bash
docker compose exec redis redis-cli ping
```

Expected output:
```
PONG
```

## Spring Boot Configuration

The backend is configured to connect to PostgreSQL and Redis using environment variables:

**application.properties**:
```properties
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/mend}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:mend_user}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:mend_password}

spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
```

This configuration:
- Uses environment variables for all credentials
- Provides sensible defaults for local development
- Does NOT hardcode secrets
- Supports both Docker and local development environments

## Testing

### Run Backend Tests

```bash
cd backend
./mvnw clean test
```

Tests include:
- **PostgresConnectivityTest**: Verifies PostgreSQL connectivity
- **RedisConnectivityTest**: Verifies Redis connectivity
- **HealthControllerTest**: Verifies health endpoint contract
- **BackendApplicationTests**: Verifies application context loads

### Run Tests with Infrastructure

For integration tests that require actual PostgreSQL and Redis connectivity:

```bash
# Ensure docker-compose services are running
docker compose up -d
docker compose ps  # Wait for all services to be healthy

# Then run tests
cd backend
./mvnw clean test
```

## Troubleshooting

### PostgreSQL Connection Refused

```bash
# Check if PostgreSQL is running and healthy
docker compose ps

# If unhealthy, check logs
docker compose logs postgres

# Verify PostgreSQL is accepting connections
docker compose exec postgres pg_isready -U mend_user
```

### Redis Connection Refused

```bash
# Check if Redis is running
docker compose ps

# If unhealthy, check logs
docker compose logs redis

# Test Redis connectivity
docker compose exec redis redis-cli ping
```

### Port Already in Use

If port 5432 or 6379 is already in use:

```bash
# Find process using the port
lsof -i :5432  # for PostgreSQL
lsof -i :6379  # for Redis

# Stop the conflicting service
kill -9 <PID>

# Or modify docker-compose.yml to use different ports
```

### Backend Can't Connect

1. Ensure infrastructure is running and healthy:
   ```bash
   docker compose up -d
   docker compose ps
   ```

2. Check `.env` file has correct configuration:
   ```bash
   cat .env
   ```

3. Verify database exists:
   ```bash
   docker compose exec postgres psql -U mend_user -l
   ```

## Architecture Diagram

```
┌─────────────────────────────────────────┐
│         Spring Boot Backend             │
│  (Java 21, Spring Boot 4.1.1)          │
└──────────┬──────────────┬───────────────┘
           │              │
        Port             Port
        5432             6379
           │              │
           v              v
┌──────────────────┐  ┌──────────────┐
│   PostgreSQL     │  │    Redis     │
│   17 (Alpine)    │  │  7 (Alpine)  │
│   Database       │  │   Cache      │
│   Persistent Vol │  │ Persistent   │
└──────────────────┘  └──────────────┘
```

## Security Considerations

### Phase 2 Security Notes

- **No credentials in code**: All credentials use environment variables
- **No secrets in .env.example**: Placeholder values only
- **.env ignored by Git**: `/.env` is in `.gitignore`
- **Local development defaults**: Passwords are simple for local dev (not for production)
- **No exposed secrets**: Health endpoints don't expose credentials

### For Production

- Use strong, random passwords
- Use secrets management (e.g., AWS Secrets Manager, HashiCorp Vault)
- Enable PostgreSQL SSL/TLS
- Use Redis AUTH with strong passwords
- Restrict network access
- Use private Docker registries
- Implement proper backup and recovery strategies

## Phase 2 Completion Checklist

- [x] docker-compose.yml created with PostgreSQL and Redis
- [x] PostgreSQL has named persistent volume
- [x] PostgreSQL has health check
- [x] Redis has health check
- [x] Docker Compose starts successfully
- [x] PostgreSQL becomes healthy
- [x] Redis becomes healthy
- [x] Spring Boot connects to PostgreSQL
- [x] Spring Boot connects to Redis
- [x] PostgreSQL credentials are environment-driven
- [x] Redis configuration is environment-driven
- [x] .env is ignored by Git
- [x] .env.example documents required variables
- [x] No secrets are committed
- [x] PostgreSQL connectivity verified
- [x] Redis connectivity verified
- [x] Backend tests pass
- [x] Health endpoint works and preserved from Phase 1
- [x] PostgreSQL data persists across container restarts
- [x] Documentation is complete

## Next Steps (Phase 3+)

Phase 2 completes the infrastructure foundation. The following phases will:

- **Phase 3**: Implement Mend business schema and authentication
  - Users table
  - Merchants table  
  - Campaigns table
  - Action intents table
  - JWT authentication

- **Phase 4+**: Integration with Razorpay, AI classification, state machine implementation

## References

- [PostgreSQL 17 Documentation](https://www.postgresql.org/docs/17/)
- [Redis Documentation](https://redis.io/docs/)
- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [Spring Boot Data JPA Documentation](https://spring.io/projects/spring-data-jpa)
- [Spring Data Redis Documentation](https://spring.io/projects/spring-data-redis)
