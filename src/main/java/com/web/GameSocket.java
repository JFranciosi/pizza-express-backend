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
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

@WebSocket(path = "/game")
@RunOnVirtualThread
public class GameSocket {

    private static final Logger LOG = Logger.getLogger(GameSocket.class);

    private static final Set<WebSocketConnection> sessions = ConcurrentHashMap.newKeySet();

    private final GameEngineService gameEngine;
    private final BettingService bettingService;

    @Inject
    public GameSocket(GameEngineService gameEngine, BettingService bettingService) {
        this.gameEngine = gameEngine;
        this.bettingService = bettingService;
    }

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        sessions.add(connection);
        LOG.info("Nuova connessione WebSocket: " + connection.id());

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
        LOG.info("Connessione chiusa: " + connection.id());
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        try {
            if (message.startsWith("BET:")) {
                String[] parts = message.split(":");
                if (parts.length < 4) {
                    connection.sendText("ERROR:Formato scommessa errato. Usa BET:userId:username:amount[:index]")
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio errore", t));
                    return;
                }
                String userId = parts[1];
                String username = parts[2];
                double amount = Double.parseDouble(parts[3]);
                int index = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;

                // Blocking call on Virtual Thread
                bettingService.placeBet(userId, username, amount, 0.0, index);

                connection.sendText("BET_OK:" + amount)
                        .subscribe().with(v -> {
                        }, t -> LOG.error("Errore invio BET_OK", t));
                broadcast("BET_ANNOUNCEMENT:" + username + ":" + amount);

            } else if (message.startsWith("CASHOUT:")) {
                String[] parts = message.split(":");
                String userId = parts[1];
                int index = (parts.length > 2) ? Integer.parseInt(parts[2]) : 0;

                bettingService.cashOut(userId, index);

                connection.sendText("CASHOUT_OK")
                        .subscribe().with(v -> {
                        }, t -> LOG.error("Errore invio CASHOUT_OK", t));
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
