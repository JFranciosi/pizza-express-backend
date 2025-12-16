package com.web;

import com.model.Bet;
import com.model.Game;
import com.service.BettingService;
import com.service.GameEngineService;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/game")
public class GameSocket {

    private static final Logger LOG = Logger.getLogger(GameSocket.class);

    // Mantiene traccia di tutte le connessioni attive per il broadcast
    // Nota: in un ambiente clusterizzato servirebbe una soluzione Pub/Sub (es.
    // Redis)
    private static final Set<WebSocketConnection> sessions = ConcurrentHashMap.newKeySet();

    @Inject
    GameEngineService gameEngine;

    @Inject
    BettingService bettingService;

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        sessions.add(connection);
        LOG.info("Nuova connessione WebSocket: " + connection.id());

        // Invia lo stato corrente del gioco appena ci si connette
        Game currentGame = gameEngine.getCurrentGame();
        if (currentGame != null) {
            connection.sendText("STATE:" + currentGame.getStatus() + ":" + currentGame.getMultiplier())
                    .subscribe().with(v -> {
                    }, t -> LOG.error("Errore onOpen", t));
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        sessions.remove(connection);
        LOG.info("Connessione chiusa: " + connection.id());
    }

    @Inject
    io.vertx.core.Vertx vertx;

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        // Protocollo semplificato:
        // BET:userId:username:amount
        // CASHOUT:userId

        try {
            if (message.startsWith("BET:")) {
                String[] parts = message.split(":");
                if (parts.length < 4) {
                    connection.sendText("ERROR:Formato scommessa errato. Usa BET:userId:username:amount")
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio errore", t));
                    return;
                }
                String userId = parts[1];
                String username = parts[2];
                double amount = Double.parseDouble(parts[3]);

                vertx.executeBlocking(() -> {
                    bettingService.placeBet(userId, username, amount);
                    return null;
                }).onSuccess(res -> {
                    connection.sendText("BET_OK:" + amount)
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio BET_OK", t));
                    broadcast("BET_ANNOUNCEMENT:" + username + ":" + amount);
                }).onFailure(err -> {
                    LOG.error("Errore placeBet", err);
                    connection.sendText("ERROR:" + err.getMessage())
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio ERROR placeBet", t));
                });

            } else if (message.startsWith("CASHOUT:")) {
                String[] parts = message.split(":");
                String userId = parts[1];

                vertx.executeBlocking(() -> {
                    bettingService.cashOut(userId);
                    return null;
                }).onSuccess(res -> {
                    connection.sendText("CASHOUT_OK")
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio CASHOUT_OK", t));
                }).onFailure(err -> {
                    LOG.error("Errore cashOut", err);
                    connection.sendText("ERROR:" + err.getMessage())
                            .subscribe().with(v -> {
                            }, t -> LOG.error("Errore invio ERROR cashOut", t));
                });
            }
        } catch (Exception e) {
            LOG.error("Errore gestione messaggio: " + message, e);
            connection.sendText("ERROR:" + e.getMessage())
                    .subscribe().with(v -> {
                    }, t -> LOG.error("Errore invio Exception", t));
        }
    }

    // Metodo per il broadcast chiamato dal GameEngineService
    public void broadcast(String message) {
        sessions.forEach(s -> {
            s.sendText(message)
                    .subscribe().with(
                            item -> {
                            },
                            failure -> LOG.error("Errore invio messaggio a " + s.id(), failure));
        });
    }
}
