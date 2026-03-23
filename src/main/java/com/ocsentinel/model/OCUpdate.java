package com.ocsentinel.model;

import lombok.Data;
import java.util.List;

@Data
public class OCUpdate {
    private String instrument;
    private String expiry;
    private double spot;
    private double pcr;
    private double maxPain;
    private double maxCEStrike;    // Highest CE OI = resistance
    private double maxPEStrike;    // Highest PE OI = support
    private double atmIV;
    private long   totalCEOI;
    private long   totalPEOI;
    private List<StrikeRow> strikes;
    private long   timestamp;
    private String dataSource;     // "WEBSOCKET_V2" or "REST"
    private String trend;          // "BULLISH", "BEARISH", "NEUTRAL"
    private String trendReasoning;

    @Data
    public static class StrikeRow {
        private double strike;
        private OIData ce = new OIData();
        private OIData pe = new OIData();
    }

    @Data
    public static class OIData {
        private String token;      // Angel One symbol token
        private long   oi;
        private long   changeOI;
        private long   volume;
        private double iv;
        private double ltp;
        private double bid;
        private double ask;
    }
}
