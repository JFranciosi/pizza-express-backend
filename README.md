# Pizza Express Backend 🍕

The high-performance, reactive backend engine for **Pizza Express**, a multiplayer "Crash" style betting game.
Built with **Java 21** and **Quarkus**, this application leverages **Redis** for state persistence and high-speed transactions, ensuring a seamless real-time experience via WebSockets.

**Frontend Repository**: [Pizza Express Frontend (Angular)](https://github.com/JFranciosi/pizza-express-frontend)

## Key Features

### Real-Time Game Engine
- **Crash Mechanics**: Implements an exponential growth curve (`Multiplier = e^(growth_rate * time)`).
- **Game States**: Manages `WAITING`, `FLYING`, and `CRASHED` states via a non-blocking event loop running every 50ms.
- **Dual Betting**: Players can place two simultaneous bets per round (Bet 1 & Bet 2).
- **Auto-Cashout**: Server-side execution of cashouts when the multiplier hits a user-defined target.

### Security & Fairness
- **Provably Fair System**: Uses a reverse SHA-256 hash chain (10,000 rounds) to pre-determine crash points. Players can verify the fairness of every round using the revealed seed.
- **JWT Authentication**: Secure stateless authentication with Access and Refresh tokens (signed via RSA keys).
- **Wallet Idempotency**: Prevents double-spending and race conditions using Redis locks and unique transaction IDs.

### Performance & Scalability
- **Reactive Architecture**: Built on **Quarkus** and **Vert.x** for non-blocking I/O.
- **Redis-First Data**: User profiles, balances, and game history are stored entirely in Redis for microsecond latency.
- **Native Compilation**: Supports GraalVM native image builds for instant startup and low memory footprint.

### Economy
- **Auto-Refill Scheduler**: A background job checks every 5 minutes; if a user has had a 0 balance for 24 hours, they are automatically refilled to 500€.

## Technology Stack

- **Language**: Java 21
- **Framework**: Quarkus 3.30 (Extensions: Rest-Jackson, SmallRye JWT, Redis Client, Scheduler, Mailer, WebSockets Next).
- **Database**: Redis (Used for KV storage, Hash maps, ZSET leaderboards, and Pub/Sub).
- **Build Tool**: Maven 3.9+.
- **Containerization**: Docker (JVM & Native Micro images).
- **CI/CD**: GitHub Actions (Azure Web App deployment).

## Getting Started

### Prerequisites
- JDK 21+
- Docker (for Redis and container building)
- Maven (optional, wrapper included)

### 1. Start Redis
The application requires a Redis instance. You can run one easily via Docker:
```bash
docker run --name redis-pizza -p 6379:6379 -d redis
```

### 2. Configure Environment
Create a `.env` file or set environment variables. The application relies on `src/main/resources/application.properties`.

**Critical Variables:**
```properties
# Redis Connection
REDIS_HOST=localhost
REDIS_PASSWORD=your_redis_password

# Security (RSA Private Key for JWT Signing)
# Must be a Base64 encoded PKCS8 Private Key
JWT_PRIVATE_KEY=MIIEvQIBADANBgkqhkiG9w0B...

# Email Service (Gmail)
QUARKUS_MAILER_USERNAME=your_email@gmail.com
QUARKUS_MAILER_PASSWORD=your_app_password

# CORS (Frontend URL)
CORS_ORIGINS=http://localhost:4200
```

### 3. Run Locally (Dev Mode)
Use the Maven Wrapper to start the app with hot-reload:
```bash
./mvnw quarkus:dev
```
The API will be available at `http://localhost:8080`.

## 📡 API Documentation

### WebSocket Events (`/game`)
The core game communication happens over WebSocket.

| Direction | Event | Payload Example | Description |
|-----------|-------|-----------------|-------------|
| S -> C | `STATE` | `STATE:FLYING:1.45` | Current game status and multiplier. |
| S -> C | `TICK` | `TICK:2.34` | Real-time multiplier update. |
| S -> C | `CRASH` | `CRASH:5.67:hash_secret` | Game ended. Reveals the hash secret. |
| C -> S | `BET` | `BET:userId:username:100:0` | Place a bet (Amount: 100, Index: 0). |
| C -> S | `CASHOUT` | `CASHOUT:userId:0` | Manual cashout for bet index 0. |

### REST Endpoints

#### Authentication
- `POST /auth/register` - Create a new account.
- `POST /auth/login` - Login (Returns Access & Refresh Tokens).
- `POST /auth/refresh` - Refresh an expired Access Token.
- `POST /auth/change-password` - Update password.

#### Betting & Game
- `POST /bet/place` - Place a bet via REST (Alternative to WS).
- `POST /bet/cashout` - Cashout via REST.
- `GET /game/history` - Retrieve previous crash points.
- `GET /bet/top?type=profit` - Get leaderboard (Profit or Multiplier).

#### User
- `GET /users/{id}/avatar` - Get user avatar.
- `POST /auth/upload-avatar` - Upload a new profile picture.

## Docker Build
You can build the application in two modes: JVM (standard) or Native (optimized).

### JVM Build (Fast Build)
```bash
./mvnw package
docker build -f src/main/docker/Dockerfile.jvm -t pizza-express-backend .
docker run -i --rm -p 8080:8080 pizza-express-backend
```

### Native Build (High Performance)
Requires GraalVM or Docker runtime for compilation.
```bash
./mvnw package -Dnative
docker build -f src/main/docker/Dockerfile.native-micro -t pizza-express-backend-native .
docker run -i --rm -p 8080:8080 pizza-express-backend-native
```

## Provably Fair Logic
The game uses a **Reverse Hash Chain** to ensure results are predetermined and immutable.

1. **Generation**: A seed is generated.
2. **Chain**: SHA-256 is applied recursively 10,000 times.
3. **Execution**: The game moves backwards through the chain (Hash 10,000 -> Hash 9,999...).
4. **Verification**: After a crash, the server sends the secret. Clients can hash this secret (`SHA256(secret)`) and verify it matches the hash of the previous round.

**Crash Point Formula**:
```java
// Simplified logic from ProvablyFairService.java
long h = Long.parseLong(hex.substring(0, 13), 16);
double e = Math.pow(2, 52);
double multiplier = 0.99 / (1.0 - (h / e));
```

## Deployment
The project includes GitHub Actions workflows (`.github/workflows/`) for automatic deployment to Azure Web Apps.

- **Push to Main**: Triggers a build and deploys the JAR artifact to Azure.
- **Config**: Ensure Azure App Service Configuration has all the required Environment Variables defined in the "Configure Environment" section.