# Pizza Express Backend 🍕🚀

![Pizza Express Hero](./pizza-express-hero.png)

> 🎨 **Looking for the Frontend?**
> This is the Quarkus backend engine (API). To play the game, you need the Angular Interface:
> 👉 **[CLICCA QUI PER IL REPO DEL FRONTEND](https://github.com/JFranciosi/pizza-express-frontend)**

The backend engine for the **Pizza Express** Crash Game, built with **Quarkus** and **Redis**.
It handles real-time game logic, multiplayer WebSocket synchronization, and provably fair RNG.

## 🌟 Features

- **Real-Time Game Engine**:
  - `GameEngineService`: Manages game states (`WAITING`, `FLYING`, `CRASHED`).
  - **Crash Logic**: Exponential multiplier growth curve.
  - **Provably Fair**: SHA-256 hash generation for every round.
- **WebSocket Broadcast**:
  - Pushes updates (`TICK`, `START`, `CRASH`) to all connected clients.
- **Betting System**:
  - Atomic bet placement allowing high-concurrency.
  - **Auto-Cashout**: Automatically cashes out when target is reached.
- **Auto-Refill**:
  - Automatically refills user balance to 500€ if they stay at 0€ for 24 hours.
  - Scheduler runs every **1 minute** to check eligibility.
- **Redis Integration**:
  - `game:current`, `game:history`, `player:*` keys for state persistence.
  - **Redis ZSET** based refill queue (`player:zero_balance`).

## 🛠️ Tech Stack

- **Java 21**
- **Quarkus**: Supersonic Subatomic Java Framework.
- **Redis (Valkey)**: Primary database for state and high-speed transactions.
- **Vert.x**: Event loop and reactive messaging.
- **Undertow**: WebSocket server.

## 🚀 Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Redis (running on `localhost:6379` or via Docker)

### Running Locally

1. Start Redis:
   ```bash
   docker run --name redis -p 6379:6379 -d redis
   ```

2. Start the application in dev mode:
   ```bash
   ./mvnw quarkus:dev
   ```

3. The API will be available at `http://localhost:8080`.

## ⚙️ Configuration

The application is configured via `application.properties` or Environment Variables (recommended for production).

| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_HOST` | Redis Host address | `localhost` |
| `REDIS_PASSWORD` | Redis Password | *Required in prod* |
| `CORS_ORIGINS` | Allowed Frontend URLs | `http://localhost:4200` |
| `GMAIL_USER` | Email for sending resets | - |
| `GMAIL_PASSWORD` | App Password for Gmail | - |

## ☁️ Deployment (Azure)

This project is deployed on **Azure App Service**.

**Important Configuration on Azure**:
Ensure you set the `CORS_ORIGINS` environment variable to your frontend domain (e.g., `https://your-netlify-app.netlify.app`) to allow cross-origin requests.

## 🔧 Debug Tools

### Auto-Refill Force Scan
Trigger a manual scan of all users to backfill the auto-refill queue:
```bash
POST /game/debug/check-all-balances
```

### Force Zero Balance (Test)
Manually set a user to 0 to trigger refill logic:
```bash
POST /game/debug/force-zero/{userId}
```