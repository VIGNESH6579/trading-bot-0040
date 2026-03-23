package com.ocsentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocsentinel.model.AnalysisResult;
import com.ocsentinel.model.OCUpdate;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${anthropic.api.key:}")
    private String apiKey;

    public AnalysisResult analyze(OCUpdate data) {
        if (apiKey == null || apiKey.isEmpty()) {
            AnalysisResult error = new AnalysisResult();
            error.setVerdict("ERROR");
            error.setReasoning("Anthropic API key is not configured.");
            return error;
        }

        try {
            String prompt = buildPrompt(data);
            String payload = buildPayload(prompt);

            Request req = new Request.Builder()
                .url(API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();

            try (Response res = http.newCall(req).execute()) {
                if (!res.isSuccessful()) {
                    log.error("Anthropic API error: {} {}", res.code(), res.body().string());
                    AnalysisResult error = new AnalysisResult();
                    error.setVerdict("ERROR");
                    error.setReasoning("AI Analysis failed. API returned: " + res.code());
                    return error;
                }

                JsonNode j = mapper.readTree(res.body().string());
                String content = j.path("content").get(0).path("text").asText();
                return parseAiResponse(content);
            }
        } catch (Exception e) {
            log.error("Analysis error: {}", e.getMessage());
            AnalysisResult error = new AnalysisResult();
            error.setVerdict("ERROR");
            error.setReasoning("Error during AI analysis: " + e.getMessage());
            return error;
        }
    }

    private String buildPrompt(OCUpdate d) {
        return String.format(
            "You are an expert options trader for the Indian Stock Market. " +
            "Analyze the following Option Chain data for %s (Expiry: %s):\n" +
            "- Spot Price: %.2f\n" +
            "- PCR: %.2f\n" +
            "- Max Pain: %.2f\n" +
            "- Resistance (CE Wall): %.2f\n" +
            "- Support (PE Wall): %.2f\n" +
            "- ATM IV: %.2f\n" +
            "- Total CE OI: %d\n" +
            "- Total PE OI: %d\n\n" +
            "Based on this data, provide a clear trade verdict: BUY CE, BUY PE, or NO TRADE. " +
            "Include an Entry Price, Stop Loss, and Target if you recommend a trade. " +
            "Explain your reasoning in 2-3 concise sentences.\n" +
            "Respond ONLY in JSON format like this: " +
            "{\"verdict\": \"BUY CE\", \"entry\": 150.5, \"stopLoss\": 120.0, \"target\": 210.0, \"reasoning\": \"...\"}",
            d.getInstrument(), d.getExpiry(), d.getSpot(), d.getPcr(), d.getMaxPain(),
            d.getMaxCEStrike(), d.getMaxPEStrike(), d.getAtmIV(), d.getTotalCEOI(), d.getTotalPEOI()
        );
    }

    private String buildPayload(String prompt) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "claude-3-5-sonnet-20240620");
        body.put("max_tokens", 512);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return mapper.writeValueAsString(body);
    }

    private AnalysisResult parseAiResponse(String content) {
        try {
            // Find JSON in the response if Claude adds any conversational text
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start != -1 && end != -1) {
                content = content.substring(start, end + 1);
            }
            return mapper.readValue(content, AnalysisResult.class);
        } catch (Exception e) {
            log.error("AI Response parse error: {}", e.getMessage());
            AnalysisResult error = new AnalysisResult();
            error.setVerdict("ERROR");
            error.setReasoning("Failed to parse AI response: " + content);
            return error;
        }
    }
}
