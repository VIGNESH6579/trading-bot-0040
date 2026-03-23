package com.ocsentinel.model;

import lombok.Data;

@Data
public class AnalysisResult {
    private String verdict;      // BUY CE, BUY PE, or NO TRADE
    private double entry;
    private double stopLoss;
    private double target;
    private String reasoning;
    private long   timestamp;

    public AnalysisResult() {
        this.timestamp = System.currentTimeMillis();
    }
}
