package com.service;

public interface WalletService {
    /**
     * Tenta di riservare fondi per una scommessa.
     * 
     * @param userId        ID dell'utente
     * @param amount        Importo da scommettere
     * @param roundId       ID del round di gioco
     * @param transactionId ID univoco della transazione per idempotenza
     * @return true se i fondi sono stati riservati con successo, false altrimenti
     */
    boolean reserveFunds(String userId, double amount, String roundId, String transactionId);

    /**
     * Accredita una vincita al giocatore.
     * 
     * @param userId        ID dell'utente
     * @param amount        Importo vinto (inclusa la posta originale)
     * @param roundId       ID del round di gioco
     * @param transactionId ID univoco della transazione per idempotenza
     * @return true se l'accredito è avvenuto (o era già stato processato), false su
     *         errore
     */
    boolean creditWinnings(String userId, double amount, String roundId, String transactionId);

    /**
     * Rimborsa una scommessa (es. cancellazione o rollback).
     * 
     * @param userId        ID dell'utente
     * @param amount        Importo da rimborsare
     * @param roundId       ID del round di gioco
     * @param transactionId ID univoco della transazione per idempotenza
     * @return true se il rimborso è avvenuto
     */
    boolean refundBet(String userId, double amount, String roundId, String transactionId);

    /**
     * Ottiene il saldo corrente dell'utente.
     * 
     * @param userId ID dell'utente
     * @return Il saldo attuale
     */
    double getBalance(String userId);
}
