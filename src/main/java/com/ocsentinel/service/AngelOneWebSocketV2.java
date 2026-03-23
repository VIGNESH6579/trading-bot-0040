package com.ocsentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocsentinel.model.LiveTick;
import com.ocsentinel.model.OCUpdate;
import com.ocsentinel.model.StatusMessage;
import okhttp3.*;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Angel One SmartAPI WebSocket V2
 *
 * WHAT IS V2?
 * -----------
 * V2 sends data as BINARY frames (not JSON).
 * Each frame is a compact byte array where each field
 * sits at a fixed byte offset — like a C struct over TCP.
 * This makes it much faster than JSON parsing.
 *
 * HOW IT WORKS:
 * 1. Connect to wss://smartapisocket.angelbroking.com/smart-stream
 * 2. Send subscription JSON with mode=3 (Snap Quote = LTP + OI + OHLC)
 * 3. Server streams binary frames for every subscribed token
 * 4. V2BinaryParser decodes each frame
 * 5. We aggregate tick data into the option chain structure
 * 6. Push updated OC to browser via STOMP every time spot changes
 *
 * MODE 3 (SNAP QUOTE) gives us:
 * - Real-time LTP of every option strike
 * - Open Interest (OI) — updated ~every 3 minutes by exchange
 * - IV, OI Day High/Low
 *
 * TOKEN SYSTEM:
 * Each tradable instrument has a numeric "token" in Angel One's system.
 * Index tokens: NIFTY=26000, BANKNIFTY=26009, FINNIFTY=26037
 * Option tokens are fetched from REST OC and stored in tokenMap.
 */
@Service
public class AngelOneWebSocketV2 {

    private static final Logger log = LoggerFactory.getLogger(AngelOneWebSocketV2.class);

    // Angel One SmartAPI WebSocket V2 endpoint
    private static final String WS_URL = "wss://smartapisocket.angelbroking.com/smart-stream";

    // Heartbeat interval — Angel One requires ping every 30 seconds
    private static final int HEARTBEAT_SECONDS = 25;

    @Autowired private V2BinaryParser      parser;
    @Autowired private AngelOneService     angelService;
    @Autowired private BroadcastService    broadcaster;

    private final ObjectMapper             mapper    = new ObjectMapper();
    private final OkHttpClient             wsClient  = new OkHttpClient.Builder()
        .pingInterval(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean            running   = new AtomicBoolean(false);

    private WebSocket            activeWs      = null;
    private ScheduledFuture<?>   heartbeatTask = null;
    private ScheduledFuture<?>   restPollTask  = null;
    private ScheduledFuture<?>   reconnectTask = null;

    // Current subscription state
    private String currentInstrument;
    private String currentExpiry;
    private String currentApiKey;

    // Token → Strike + Type mapping (built from REST OC)
    // e.g. "58662" → { strike: 24000, type: "CE" }
    private final Map<String, StrikeInfo> tokenMap = new ConcurrentHashMap<>();

    // Latest tick data per token
    private final Map<String, LiveTick>   tickMap  = new ConcurrentHashMap<>();

    // Latest full OC snapshot (from REST, enriched by V2 ticks)
    private volatile OCUpdate latestOC = null;

    // ── START ─────────────────────────────────────────────────────────────────
    public void start(String instrument, String expiry, String apiKey) {
        this.currentInstrument = instrument;
        this.currentExpiry     = expiry;
        this.currentApiKey     = apiKey;
        running.set(true);

        log.info("Starting WebSocket V2 for {} {}", instrument, expiry);

        // Step 1: Load REST OC to get initial data and token map
        loadInitialOC();

        // Step 2: Connect WebSocket V2
        connect();

        // Step 3: REST polling every 30 seconds (OI updates from exchange ~3 min)
        scheduleRestPoll();
    }

    // ── STOP ─────────────────────────────────────────────────────────────────
    public void stop() {
        running.set(false);
        if (activeWs != null)     { activeWs.close(1000, "Client stop"); activeWs = null; }
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        if (restPollTask != null)  restPollTask.cancel(false);
        if (reconnectTask != null) reconnectTask.cancel(false);
        tokenMap.clear();
        tickMap.clear();
        log.info("WebSocket V2 stopped");
    }

    // ── CONNECT TO ANGEL ONE WS V2 ───────────────────────────────────────────
    private void connect() {
        if (!running.get() || !angelService.getSession().isLoggedIn()) return;

        String feedToken  = angelService.getSession().getFeedToken();
        String clientCode = angelService.getSession().getClientCode();

        Request req = new Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization",   feedToken)
            .addHeader("x-client-code",   clientCode)
            .addHeader("x-feed-token",    feedToken)
            .addHeader("x-client-type",   "WEB")
            .build();

        activeWs = wsClient.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("WebSocket V2 CONNECTED to Angel One");
                broadcaster.status(new StatusMessage("CONNECTED",
                    "WebSocket V2 connected — receiving live data"));

                // Subscribe in Mode 3 (Snap Quote) for full OI + LTP
                subscribeMode3(ws);

                // Start heartbeat ping every 25 seconds
                startHeartbeat(ws);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                // V2 sends BINARY frames — this is the key difference
                handleBinaryFrame(bytes.toByteArray());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                // V2 may send text for errors/ack
                log.debug("WS text: {}", text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                log.warn("WS closing: {} {}", code, reason);
                broadcaster.status(new StatusMessage("RECONNECTING", "Connection lost, reconnecting..."));
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response r) {
                log.error("WS failure: {}", t.getMessage());
                broadcaster.status(new StatusMessage("ERROR", "WS error: " + t.getMessage()));
                scheduleReconnect();
            }
        });
    }

    // ── SUBSCRIBE MODE 3 (SNAP QUOTE) ────────────────────────────────────────
    private void subscribeMode3(WebSocket ws) {
        try {
            // Build token list from our tokenMap
            // Exchange type 2 = NFO (for option contracts)
            // Exchange type 1 = NSE (for index spot)
            List<String> nfoTokens = new ArrayList<>(tokenMap.keySet());
            List<String> nseTokens = getIndexTokens();

            List<Map<String, Object>> tokenList = new ArrayList<>();

            // Subscribe to index spot (NSE)
            if (!nseTokens.isEmpty()) {
                tokenList.add(Map.of(
                    "exchangeType", 1,        // NSE
                    "tokens",       nseTokens
                ));
            }

            // Subscribe to NFO options
            if (!nfoTokens.isEmpty()) {
                // Angel One limits subscription to 50 tokens per request
                // Subscribe ATM ± 10 strikes (20 CE + 20 PE = 40 tokens)
                List<String> subset = nfoTokens.size() > 40
                    ? nfoTokens.subList(0, 40)
                    : nfoTokens;
                tokenList.add(Map.of(
                    "exchangeType", 2,        // NFO
                    "tokens",       subset
                ));
            }

            if (tokenList.isEmpty()) {
                log.warn("No tokens to subscribe — waiting for REST OC data");
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("correlationID", "oc_sentinel_v2");
            payload.put("action",        1);   // 1 = Subscribe
            payload.put("params", Map.of(
                "mode",      3,               // Mode 3 = Snap Quote (full OI + LTP)
                "tokenList", tokenList
            ));

            String msg = mapper.writeValueAsString(payload);
            ws.send(msg);
            log.info("Subscribed V2 mode=3: {} NFO tokens + {} NSE tokens",
                tokenList.stream().filter(t -> ((int)t.get("exchangeType")) == 2)
                    .mapToInt(t -> ((List<?>)t.get("tokens")).size()).sum(),
                nseTokens.size());

        } catch (Exception e) {
            log.error("Subscribe error: {}", e.getMessage());
        }
    }

    // ── HANDLE BINARY FRAME ───────────────────────────────────────────────────
    private void handleBinaryFrame(byte[] bytes) {
        LiveTick tick = parser.parse(bytes);
        if (tick == null || tick.getToken() == null) return;

        // Store latest tick for this token
        tickMap.put(tick.getToken(), tick);

        // If this is the index spot token — update spot price immediately
        String indexToken = getIndexToken(currentInstrument);
        if (tick.getToken().equals(indexToken) && tick.getLtp() > 0) {
            // Spot price updated in real-time via V2
            if (latestOC != null) {
                latestOC.setSpot(tick.getLtp());
                // Recalculate ATM and push to browser
                enrichAndBroadcast();
            }
            return;
        }

        // If this is an option token — update that strike's LTP + OI
        StrikeInfo info = tokenMap.get(tick.getToken());
        if (info != null && latestOC != null) {
            updateStrikeTick(info, tick);
        }
    }

    // ── UPDATE STRIKE FROM LIVE TICK ─────────────────────────────────────────
    private void updateStrikeTick(StrikeInfo info, LiveTick tick) {
        if (latestOC == null || latestOC.getStrikes() == null) return;

        for (OCUpdate.StrikeRow row : latestOC.getStrikes()) {
            if (Math.abs(row.getStrike() - info.strike) < 0.01) {
                OCUpdate.OIData d = "CE".equals(info.type)
                    ? row.getCe() : row.getPe();

                // Update LTP from V2 tick (real-time)
                if (tick.getLtp() > 0)  d.setLtp(tick.getLtp());
                // Update OI from V2 tick (updates ~every 3 min from exchange)
                if (tick.getOi() > 0)   d.setOi(tick.getOi());
                if (tick.getVolume() > 0) d.setVolume(tick.getVolume());

                latestOC.setDataSource("WEBSOCKET_V2");
                latestOC.setTimestamp(System.currentTimeMillis());
                break;
            }
        }
        enrichAndBroadcast();
    }

    // ── ENRICH AND BROADCAST ─────────────────────────────────────────────────
    private long lastBroadcast = 0;

    private void enrichAndBroadcast() {
        // Throttle: broadcast max once per second to avoid flooding browser
        long now = System.currentTimeMillis();
        if (now - lastBroadcast < 1000) return;
        lastBroadcast = now;

        if (latestOC != null) {
            // Recalculate derived fields (PCR, max pain etc.) with latest data
            OCUpdate fresh = angelService.buildOCUpdate(
                latestOC.getStrikes(),
                latestOC.getInstrument(),
                latestOC.getExpiry(),
                latestOC.getDataSource(),
                latestOC.getSpot()
            );
            if (fresh != null) {
                latestOC = fresh;
                broadcaster.ocUpdate(fresh);
            }
        }
    }

    // ── LOAD INITIAL OC FROM REST ─────────────────────────────────────────────
    private void loadInitialOC() {
        try {
            OCUpdate oc = angelService.fetchOptionChain(
                currentInstrument, currentExpiry, currentApiKey);
            if (oc != null) {
                latestOC = oc;
                broadcaster.ocUpdate(oc);
                log.info("Initial OC loaded: {} strikes", oc.getStrikes().size());
                // Note: In a full implementation, extract token numbers from the
                // Angel One instrument master CSV and populate tokenMap
                // For now we rely on REST + index spot from V2
            }
        } catch (Exception e) {
            log.error("Initial OC load: {}", e.getMessage());
        }
    }

    // ── REST POLL (backup + OI refresh) ──────────────────────────────────────
    private void scheduleRestPoll() {
        if (restPollTask != null) restPollTask.cancel(false);
        restPollTask = scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            try {
                OCUpdate oc = angelService.fetchOptionChain(
                    currentInstrument, currentExpiry, currentApiKey);
                if (oc != null) {
                    // Preserve V2 spot if we have it
                    if (latestOC != null && latestOC.getSpot() > 0) {
                        oc.setSpot(latestOC.getSpot());
                    }
                    latestOC = oc;
                    broadcaster.ocUpdate(oc);
                    log.info("REST OC refreshed: spot={}", oc.getSpot());
                }
            } catch (Exception e) {
                log.error("REST poll: {}", e.getMessage());
            }
        }, 5, 30, TimeUnit.SECONDS);  // Every 30 seconds
    }

    // ── HEARTBEAT ─────────────────────────────────────────────────────────────
    private void startHeartbeat(WebSocket ws) {
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                ws.send("ping");  // Angel One expects "ping" text frames
                log.debug("Heartbeat sent");
            } catch (Exception e) {
                log.warn("Heartbeat failed: {}", e.getMessage());
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    // ── RECONNECT ─────────────────────────────────────────────────────────────
    private void scheduleReconnect() {
        if (!running.get()) return;
        if (reconnectTask != null) reconnectTask.cancel(false);
        reconnectTask = scheduler.schedule(() -> {
            log.info("Reconnecting WebSocket V2...");
            connect();
        }, 5, TimeUnit.SECONDS);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────
    private List<String> getIndexTokens() {
        return switch (currentInstrument.toUpperCase()) {
            case "BANKNIFTY" -> List.of("26009");
            case "FINNIFTY"  -> List.of("26037");
            default          -> List.of("26000"); // NIFTY
        };
    }

    private String getIndexToken(String instrument) {
        return switch (instrument.toUpperCase()) {
            case "BANKNIFTY" -> "26009";
            case "FINNIFTY"  -> "26037";
            default          -> "26000";
        };
    }

    public boolean isRunning() { return running.get(); }
    public OCUpdate getLatestOC() { return latestOC; }

    // Token → Strike mapping helper
    public static class StrikeInfo {
        public final double strike;
        public final String type;
        public StrikeInfo(double strike, String type) {
            this.strike = strike;
            this.type   = type;
        }
    }
}
