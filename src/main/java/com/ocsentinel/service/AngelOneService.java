package com.ocsentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocsentinel.model.OCUpdate;
import com.ocsentinel.model.SessionInfo;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AngelOneService {

    private static final Logger log = LoggerFactory.getLogger(AngelOneService.class);
    private static final String BASE   = "https://apiconnect.angelbroking.com";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http   = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private SessionInfo session       = new SessionInfo();

    // ── LOGIN ─────────────────────────────────────────────────────────────────
    public Map<String, Object> login(String clientCode, String mpin,
                                     String totp, String apiKey) {
        Map<String, Object> result = new HashMap<>();
        try {
            String body = mapper.writeValueAsString(Map.of(
                "clientcode", clientCode,
                "password",   mpin,
                "totp",       totp
            ));

            Request req = buildRequest(BASE + "/rest/auth/angelbroking/user/v1/loginByPassword",
                    apiKey, null, body);

            try (Response res = http.newCall(req).execute()) {
                JsonNode j = mapper.readTree(res.body().string());
                if (j.path("status").asBoolean()) {
                    JsonNode data = j.path("data");
                    session = new SessionInfo();
                    session.setJwtToken(data.path("jwtToken").asText());
                    session.setFeedToken(data.path("feedToken").asText());
                    session.setClientCode(clientCode);
                    session.setName(data.path("name").asText(clientCode));
                    session.setLoggedIn(true);
                    result.put("success",   true);
                    result.put("name",      session.getName());
                    result.put("feedToken", session.getFeedToken());
                    log.info("Login OK: {}", clientCode);
                } else {
                    result.put("success", false);
                    result.put("message", j.path("message").asText("Login failed"));
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Error: " + e.getMessage());
        }
        return result;
    }

    // ── FETCH EXPIRIES ────────────────────────────────────────────────────────
    public List<String> fetchExpiries(String instrument, String apiKey) {
        if (!session.isLoggedIn()) return genExpiries();
        try {
            String body = mapper.writeValueAsString(Map.of(
                "name", instrument, "expirydate", "", "strikePrice", "", "optionType", "CE"
            ));
            Request req = buildRequest(
                BASE + "/rest/secure/angelbroking/marketData/v1/optionChain",
                apiKey, session.getJwtToken(), body);
            try (Response res = http.newCall(req).execute()) {
                JsonNode j = mapper.readTree(res.body().string());
                if (j.path("status").asBoolean() && j.has("data")) {
                    Set<String> exps = new LinkedHashSet<>();
                    for (JsonNode n : j.path("data")) {
                        String exp = n.path("expiry").asText();
                        if (!exp.isEmpty()) exps.add(exp);
                    }
                    if (!exps.isEmpty()) return new ArrayList<>(exps);
                }
            }
        } catch (Exception e) { log.error("Expiry fetch: {}", e.getMessage()); }
        return genExpiries();
    }

    // ── FETCH FULL OPTION CHAIN (REST snapshot) ───────────────────────────────
    // Used as initial load and fallback when WebSocket V2 data is incomplete
    public OCUpdate fetchOptionChain(String instrument, String expiry, String apiKey) {
        if (!session.isLoggedIn()) return null;
        try {
            String body = mapper.writeValueAsString(Map.of(
                "name", instrument, "expirydate", expiry, "strikePrice", "", "optionType", ""
            ));
            Request req = buildRequest(
                BASE + "/rest/secure/angelbroking/marketData/v1/optionChain",
                apiKey, session.getJwtToken(), body);
            try (Response res = http.newCall(req).execute()) {
                JsonNode j = mapper.readTree(res.body().string());
                if (j.path("status").asBoolean() && j.has("data")) {
                    log.info("REST OC fetch OK: {} rows", j.path("data").size());
                    return parseOC(j.path("data"), instrument, expiry, "REST");
                }
            }
        } catch (Exception e) { log.error("OC fetch error: {}", e.getMessage()); }
        return null;
    }

    // ── PARSE OC ──────────────────────────────────────────────────────────────
    public OCUpdate parseOC(JsonNode data, String instrument,
                            String expiry, String source) {
        Map<Double, OCUpdate.StrikeRow> map = new TreeMap<>();

        for (JsonNode item : data) {
            double strike = item.path("strikePrice").asDouble();
            String type   = item.path("optionType").asText("").toUpperCase();
            if (strike == 0 || type.isEmpty()) continue;

            map.computeIfAbsent(strike, s -> {
                OCUpdate.StrikeRow row = new OCUpdate.StrikeRow();
                row.setStrike(s);
                row.setCe(new OCUpdate.OIData());
                row.setPe(new OCUpdate.OIData());
                return row;
            });

            OCUpdate.OIData d = new OCUpdate.OIData();
            d.setToken(item.path("symbolToken").asText());
            d.setOi((long) item.path("openInterest").asDouble());
            d.setChangeOI((long) item.path("changeinOpenInterest").asDouble());
            d.setVolume((long) item.path("tradedVolume").asDouble(
                         item.path("totalTradedVolume").asDouble()));
            d.setIv(item.path("impliedVolatility").asDouble());
            d.setLtp(item.path("lastPrice").asDouble(item.path("ltp").asDouble()));
            d.setBid(item.path("bidPrice").asDouble());
            d.setAsk(item.path("askPrice").asDouble());

            if ("CE".equals(type)) map.get(strike).setCe(d);
            else if ("PE".equals(type)) map.get(strike).setPe(d);
        }

        return buildOCUpdate(new ArrayList<>(map.values()), instrument, expiry, source, 0);
    }

    // ── BUILD OC UPDATE ───────────────────────────────────────────────────────
    public OCUpdate buildOCUpdate(List<OCUpdate.StrikeRow> strikes,
                                   String instrument, String expiry,
                                   String source, double spotOverride) {
        if (strikes == null || strikes.isEmpty()) return null;

        long tce = 0, tpe = 0;
        for (OCUpdate.StrikeRow s : strikes) {
            tce += s.getCe().getOi();
            tpe += s.getPe().getOi();
        }
        double pcr = tce > 0 ? Math.round((double) tpe / tce * 100.0) / 100.0 : 0;

        // Estimate spot from middle of chain if not given
        double spot = spotOverride > 0
            ? spotOverride
            : strikes.get(strikes.size() / 2).getStrike();

        // ATM
        OCUpdate.StrikeRow atm = strikes.stream()
            .min(Comparator.comparingDouble(s -> Math.abs(s.getStrike() - spot)))
            .orElse(strikes.get(0));

        double atmIV = (atm.getCe().getIv() + atm.getPe().getIv()) / 2.0;

        // Max Pain
        double mp = computeMaxPain(strikes);

        // CE Wall (highest CE OI = resistance)
        OCUpdate.StrikeRow maxCE = strikes.stream()
            .max(Comparator.comparingLong(s -> s.getCe().getOi())).orElse(atm);

        // PE Wall (highest PE OI = support)
        OCUpdate.StrikeRow maxPE = strikes.stream()
            .max(Comparator.comparingLong(s -> s.getPe().getOi())).orElse(atm);

        // Trend logic
        String trend = "NEUTRAL";
        String tr = "Market is in balance.";
        if (pcr > 1.25 && spot > maxPE.getStrike()) {
            trend = "BULLISH";
            tr = "Strong PE support + High PCR.";
        } else if (pcr < 0.75 && spot < maxCE.getStrike()) {
            trend = "BEARISH";
            tr = "Strong CE resistance + Low PCR.";
        }

        OCUpdate oc = new OCUpdate();
        oc.setInstrument(instrument);
        oc.setExpiry(expiry);
        oc.setSpot(spot);
        oc.setPcr(pcr);
        oc.setMaxPain(mp);
        oc.setMaxCEStrike(maxCE.getStrike());
        oc.setMaxPEStrike(maxPE.getStrike());
        oc.setAtmIV(Math.round(atmIV * 10.0) / 10.0);
        oc.setTotalCEOI(tce);
        oc.setTotalPEOI(tpe);
        oc.setStrikes(strikes);
        oc.setTrend(trend);
        oc.setTrendReasoning(tr);
        oc.setTimestamp(System.currentTimeMillis());
        oc.setDataSource(source);
        return oc;
    }

    private double computeMaxPain(List<OCUpdate.StrikeRow> strikes) {
        double minPain = Double.MAX_VALUE, mp = strikes.get(0).getStrike();
        for (OCUpdate.StrikeRow s : strikes) {
            double pain = 0;
            for (OCUpdate.StrikeRow s2 : strikes) {
                if (s2.getStrike() > s.getStrike())
                    pain += s2.getCe().getOi() * (s2.getStrike() - s.getStrike());
                if (s2.getStrike() < s.getStrike())
                    pain += s2.getPe().getOi() * (s.getStrike() - s2.getStrike());
            }
            if (pain < minPain) { minPain = pain; mp = s.getStrike(); }
        }
        return mp;
    }

    private Request buildRequest(String url, String apiKey,
                                  String jwt, String body) {
        Request.Builder b = new Request.Builder().url(url)
            .addHeader("Content-Type",       "application/json")
            .addHeader("Accept",             "application/json")
            .addHeader("X-PrivateKey",       apiKey)
            .addHeader("X-UserType",         "USER")
            .addHeader("X-SourceID",         "WEB")
            .addHeader("X-ClientLocalIP",    "127.0.0.1")
            .addHeader("X-ClientPublicIP",   "127.0.0.1")
            .addHeader("X-MACAddress",       "00:00:00:00:00:00");
        if (jwt != null) b.addHeader("Authorization", "Bearer " + jwt);
        if (body != null) b.post(RequestBody.create(body, JSON));
        else b.get();
        return b.build();
    }

    private List<String> genExpiries() {
        List<String> out = new ArrayList<>();
        String[] mo = {"Jan","Feb","Mar","Apr","May","Jun",
                        "Jul","Aug","Sep","Oct","Nov","Dec"};
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        for (int i = 0; i < 6; i++) {
            Calendar t = (Calendar) cal.clone();
            t.add(Calendar.DAY_OF_YEAR, i * 7);
            int diff = (Calendar.THURSDAY - t.get(Calendar.DAY_OF_WEEK) + 7) % 7;
            t.add(Calendar.DAY_OF_YEAR, diff);
            out.add(String.format("%02d%s%d",
                t.get(Calendar.DAY_OF_MONTH),
                mo[t.get(Calendar.MONTH)],
                t.get(Calendar.YEAR)));
        }
        return out;
    }

    public SessionInfo getSession() { return session; }
    public void clearSession()      { session = new SessionInfo(); }
}
