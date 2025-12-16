package com.model;

public class Game {
    private String id;
    private GameState status;
    private double multiplier;
    private double crashPoint;
    private long startTime;

    public Game() {
    }

    public Game(String id, double crashPoint) {
        this.id = id;
        this.crashPoint = crashPoint;
        this.multiplier = 1.00;
        this.status = GameState.WAITING;
        this.startTime = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public GameState getStatus() {
        return status;
    }

    public void setStatus(GameState status) {
        this.status = status;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public double getCrashPoint() {
        return crashPoint;
    }

    public void setCrashPoint(double crashPoint) {
        this.crashPoint = crashPoint;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    private String secret;
    private String hash;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}