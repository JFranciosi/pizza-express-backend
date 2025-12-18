package com.model;

public class Bet {
    private String userId;
    private String username;
    private String gameId;
    private double amount;
    private double autoCashout;
    private double cashOutMultiplier;
    private int index;
    private double profit;

    public Bet() {
    }

    public Bet(String userId, String username, String gameId, double amount, int index) {
        this.userId = userId;
        this.username = username;
        this.gameId = gameId;
        this.amount = amount;
        this.index = index;
        this.cashOutMultiplier = 0.0;
        this.profit = 0.0;
    }

    // Getter e Setter
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public double getAutoCashout() {
        return autoCashout;
    }

    public void setAutoCashout(double autoCashout) {
        this.autoCashout = autoCashout;
    }

    public double getCashOutMultiplier() {
        return cashOutMultiplier;
    }

    public void setCashOutMultiplier(double cashOutMultiplier) {
        this.cashOutMultiplier = cashOutMultiplier;
    }

    public double getProfit() {
        return profit;
    }

    public void setProfit(double profit) {
        this.profit = profit;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}