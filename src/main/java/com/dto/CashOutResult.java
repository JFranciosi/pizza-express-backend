package com.dto;

public class CashOutResult {
    public double winAmount;
    public double newBalance;
    public double multiplier;

    public CashOutResult(double winAmount, double newBalance, double multiplier) {
        this.winAmount = winAmount;
        this.newBalance = newBalance;
        this.multiplier = multiplier;
    }
}
