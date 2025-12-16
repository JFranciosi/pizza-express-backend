# Pizza Express Backend 🍕🚀

Backend for the **Pizza Express** Crash Game, built with **Quarkus**.
This service manages the real-time game logic, user authentication, and betting system.

## 🌟 Features

- **Crash Game Engine**: Real-time multiplier generation using a secure hash-chain algorithm (Fair & Verifiable).
- **Real-Time Communication**: WebSocket endpoint (`/game`) for broadcasting game state (Multiplier, Time Left).
- **Authentication**: JWT-based Auth with `TokenService`. New users start with **500€**.
- **Betting System**:
  - **Place Bet**: `POST /bet/place` (Min 0.10€, Max 100€).
  - **Cancel Bet**: `POST /bet/cancel` (Only in `WAITING` phase). Atomic refund.
  - **Cash Out**: `POST /bet/cashout` (Returns authoritative win amount/balance).
  - **Race Condition Protection**: Atomic transactions ensure balance integrity.
- **Persistence**: Redis for fast game state management and H2/PostgreSQL for user data.

## 🛠️ Tech Stack

- **Java 17+**
- **Quarkus**: Supersonic Subatomic Java Framework.
- **Vert.x**: For reactive WebSockets and event loops.
- **Hibernate ORM / Panache**: Data persistence.
- **Lombok**: For boilerplate code reduction.

## 🚀 Getting Started

### Prerequisites

- JDK 17+
- Maven 3.8+

### Running the Application

```shell
./mvnw quarkus:dev
```

The application will start on `http://localhost:8080`.

### API Endpoints

| Method | Endpoint | Description | Auth Required |
| :--- | :--- | :--- | :--- |
| `POST` | `/auth/register` | Register new user (500€ Bonus) | ❌ |
| `POST` | `/auth/login` | Login and get JWT | ❌ |
| `POST` | `/bet/place` | Place a bet (Amount: 0.10 - 100) | ✅ |
| `POST` | `/bet/cancel` | Cancel active bet (In Waiting Phase) | ✅ |
| `POST` | `/bet/cashout` | Cash out current bet | ✅ |
| `WS` | `/game` | WebSocket for Game Stream | ❌ |

## 🧪 Testing

Run unit and integration tests:

```shell
./mvnw test
```

## 🔒 Security Notes

- **Decimal Precision**: All monetary values are strictly rounded to 2 decimal places.
- **Concurrency**: Betting actions use atomic locks (`ConcurrentHashMap.compute`) to prevent double-spending race conditions.