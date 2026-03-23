package com.ocsentinel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

// ── Session ─────────────────────────────────────────
@Data
public class SessionInfo {
    private String jwtToken;
    private String feedToken;
    private String clientCode;
    private String name;
    private boolean loggedIn;
}

// ── Live Tick from WebSocket V2 binary frame ─────────
@Data
public class LiveTick {
    private String token;
    private int    exchangeType;   // 1=NSE, 2=NFO
    private double ltp;            // Last Traded Price
    private long   volume;
    private double open;
    private double high;
    private double low;
    private double close;
    private long   oi;             // Open Interest (KEY for options)
    private long   oiDayHigh;
    private long   oiDayLow;
    private double impliedVolatility;
    private long   timestamp;
}

// ── Full OC Update pushed to browser ─────────────────
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

    @Data
    public static class StrikeRow {
        private double strike;
        private OIData ce = new OIData();
        private OIData pe = new OIData();
    }

    @Data
    public static class OIData {
        private long   oi;
        private long   changeOI;
        private long   volume;
        private double iv;
        private double ltp;
        private double bid;
        private double ask;
    }
}

// ── Status message to browser ─────────────────────────
@Data
public class StatusMessage {
    private String status;   // CONNECTED, DISCONNECTED, ERROR, RECONNECTING
    private String message;
    private long   timestamp;

    public StatusMessage(String status, String message) {
        this.status    = status;
        this.message   = message;
        this.timestamp = System.currentTimeMillis();
    }
}
