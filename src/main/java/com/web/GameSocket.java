package com.web;

import com.model.Game;
import com.service.BettingService;
import com.service.GameEngineService;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;

@WebSocket(path = "/game")
@RunOnVirtualThread
@Authenticated
public class GameSocket {

    private static final Logger LOG = Logger.getLogger(GameSocket.class);
    private static final Set<WebSocketConnection> sessions = ConcurrentHashMap.newKeySet();
    private static final Map<String, UserInfo> connectedUsers = new ConcurrentHashMap<>();
    private final Map<String, Bucket> rateLimiters = new ConcurrentHashMap<>();
    private final GameEngineService gameEngine;
    private final BettingService bettingService;
    private final JsonWebToken jwt;

    @Inject
    public GameSocket(GameEngineService gameEngine, BettingService bettingService, JsonWebToken jwt) {
        this.gameEngine = gameEngine;
        this.bettingService = bettingService;
        this.jwt = jwt;
    }

    private record UserInfo(String userId, String username) {
    }

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        String userId = jwt.getClaim("userId");
        String username = jwt.getClaim("username");
        if (username == null) {
            username = jwt.getName();
        }

        Bucket bucket = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(5)
                        .refillGreedy(5, Duration.ofSeconds(1))
                        .build())
                .build();
        rateLimiters.put(connection.id(), bucket);

        connectedUsers.put(connection.id(), new UserInfo(userId, username));
        sessions.add(connection);
        LOG.info("Nuova connessione autenticata: " + username + " (" + connection.id() + ")");

        Game currentGame = gameEngine.getCurrentGame();
        if (currentGame != null) {
            connection.sendText("STATE:" + currentGame.getStatus() + ":" + currentGame.getMultiplier())
                    .subscribe().with(v -> {
                    }, t -> LOG.error("Errore onOpen", t));
        }

        List<String> history = gameEngine.getHistory();
        if (history != null && !history.isEmpty()) {
            String historyMsg = "HISTORY:" + String.join(",", history);
            connection.sendText(historyMsg)
                    .subscribe().with(v -> {
                    }, t -> LOG.error("Errore invio HISTORY", t));
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        sessions.remove(connection);
        connectedUsers.remove(connection.id());
        rateLimiters.remove(connection.id());
        LOG.info("Connessione chiusa: " + connection.id());
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        UserInfo userInfo = connectedUsers.get(connection.id());
        if (userInfo == null) {
            connection.sendText("ERROR:Utente non autenticato")
                    .subscribe().with(v -> connection.close(), t -> {
                    });
            return;
        }

        Bucket bucket = rateLimiters.get(connection.id());
        if (bucket != null && !bucket.tryConsume(1)) {
            LOG.warn("Rate limit exceeded for connection: " + connection.id());
            connection.sendText("ERROR:Rate limit exceeded. Slow down!")
                    .subscribe().with(v -> {
                    }, t -> {
                    });
            return;
        }

        try {
            if (message.startsWith("BET:")) {
                String[] parts = message.split(":");
                if (parts.length < 5) {
                    connection.sendText("ERROR:Formato scommessa errato.")
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio errore", t));
                    return;
                }

                String userId = userInfo.userId();
                String username = userInfo.username();

                String nonce = parts[1];
                double amount = Double.parseDouble(parts[3]);

                if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
                    connection.sendText("ERROR:Importo scommessa non valido.")
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio errore validazione", t));
                    return;
                }
                int index = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;

                bettingService.placeBet(userId, username, amount, 0.0, index, nonce);

                connection.sendText("BET_OK:" + amount)
                        .subscribe().with(v -> {
                        }, t -> LOG.error("Errore invio BET_OK", t));
                broadcast("BET_ANNOUNCEMENT:" + username + ":" + amount);

            } else if (message.startsWith("CASHOUT:")) {
                String[] parts = message.split(":");
                String userId = userInfo.userId();
                int index = (parts.length > 2) ? Integer.parseInt(parts[2]) : 0;
                bettingService.cashOut(userId, index);
                connection.sendText("CASHOUT_OK")
                        .subscribe().with(v -> {
                        }, t -> LOG.error("Errore invio CASHOUT_OK", t));

            } else if (message.equals("PING")) {
                connection.sendText("PONG")
                        .subscribe().with(v -> {
                        }, t -> LOG.error("Errore invio PONG", t));
            }
        } catch (Exception e) {
            LOG.error("Errore gestione messaggio: " + message, e);
            connection.sendText("ERROR:" + e.getMessage())
                    .subscribe().with(v -> {
                    }, t -> LOG.error("Errore invio Exception", t));
        }
    }

    public void broadcast(String message) {
        sessions.forEach(s -> {
            s.sendText(message)
                    .subscribe().with(v -> {
                    }, t -> LOG.error("Errore invio broadcast a " + s.id(), t));
        });
    }
}
