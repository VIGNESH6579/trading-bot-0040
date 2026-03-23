package com.ocsentinel.controller;

import com.ocsentinel.model.OCUpdate;
import com.ocsentinel.model.StatusMessage;
import com.ocsentinel.service.AngelOneService;
import com.ocsentinel.service.AngelOneWebSocketV2;
import com.ocsentinel.service.BroadcastService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private AngelOneService     angelService;
    @Autowired private AngelOneWebSocketV2 wsV2;
    @Autowired private BroadcastService    broadcaster;
    @Autowired private com.ocsentinel.service.AiService aiService;

    // ── HEALTH ────────────────────────────────────────────────────────────────
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status",    "UP",
            "service",   "OC Sentinel — WebSocket V2 Edition",
            "version",   "2.0.0",
            "timestamp", System.currentTimeMillis()
        ));
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────
    // POST /api/login
    // { "clientCode":"A123", "mpin":"1234", "totp":"123456", "apiKey":"xxx" }
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body) {

        String clientCode = body.getOrDefault("clientCode", "").trim().toUpperCase();
        String mpin       = body.getOrDefault("mpin",       "").trim();
        String totp       = body.getOrDefault("totp",       "").trim();
        String apiKey     = body.getOrDefault("apiKey",     "").trim();

        if (clientCode.isEmpty() || mpin.isEmpty() || totp.isEmpty() || apiKey.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false, "message", "All fields required"));
        }

        Map<String, Object> result = angelService.login(clientCode, mpin, totp, apiKey);
        return ResponseEntity.ok(result);
    }

    // ── LOGOUT ────────────────────────────────────────────────────────────────
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        wsV2.stop();
        angelService.clearSession();
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── EXPIRIES ──────────────────────────────────────────────────────────────
    // GET /api/expiries?instrument=NIFTY&apiKey=xxx
    @GetMapping("/expiries")
    public ResponseEntity<Map<String, Object>> expiries(
            @RequestParam String instrument,
            @RequestParam String apiKey) {

        List<String> exps = angelService.fetchExpiries(instrument, apiKey);
        return ResponseEntity.ok(Map.of("success", true, "expiries", exps));
    }

    // ── START WEBSOCKET V2 FEED ───────────────────────────────────────────────
    // POST /api/start-feed
    // { "instrument":"NIFTY", "expiry":"27Mar2025", "apiKey":"xxx" }
    @PostMapping("/start-feed")
    public ResponseEntity<Map<String, Object>> startFeed(
            @RequestBody Map<String, String> body) {

        if (!angelService.getSession().isLoggedIn()) {
            return ResponseEntity.ok(Map.of(
                "success", false, "message", "Not logged in"));
        }

        String instrument = body.getOrDefault("instrument", "NIFTY");
        String expiry     = body.getOrDefault("expiry",     "");
        String apiKey     = body.getOrDefault("apiKey",     "");

        // Stop any existing feed
        wsV2.stop();

        // Start fresh with WebSocket V2
        wsV2.start(instrument, expiry, apiKey);

        broadcaster.status(new StatusMessage("STARTING",
            "Starting WebSocket V2 feed for " + instrument + " " + expiry));

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "WebSocket V2 feed started: " + instrument + " " + expiry
        ));
    }

    // ── SESSION STATUS ────────────────────────────────────────────────────────
    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> session() {
        var s = angelService.getSession();
        return ResponseEntity.ok(Map.of(
            "loggedIn",  s.isLoggedIn(),
            "name",      s.getName() != null ? s.getName() : "",
            "wsRunning", wsV2.isRunning()
        ));
    }

    // ── AI ANALYSIS ───────────────────────────────────────────────────────────
    @PostMapping("/analyze")
    public ResponseEntity<com.ocsentinel.model.AnalysisResult> analyze() {
        OCUpdate oc = wsV2.getLatestOC();
        if (oc == null) {
            com.ocsentinel.model.AnalysisResult err = new com.ocsentinel.model.AnalysisResult();
            err.setVerdict("WAITING");
            err.setReasoning("Waiting for live data feed...");
            return ResponseEntity.ok(err);
        }
        return ResponseEntity.ok(aiService.analyze(oc));
    }
}
