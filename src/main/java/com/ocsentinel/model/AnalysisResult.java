package com.ocsentinel.model;

import lombok.Data;

@Data
public class AnalysisResult {
    private String verdict;      // BUY CE, BUY PE, or NO TRADE
    private double entry;
    private double stopLoss;
    private double target;
    private String reasoning;
    private long   dataTimestamp; // Timestamp of the OC data used
    private long   timestamp;     // Signal generation timestamp

    public AnalysisResult() {
        this.timestamp = System.currentTimeMillis();
    }
}
