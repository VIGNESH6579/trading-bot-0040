package com.ocsentinel.service;

import com.ocsentinel.model.LiveTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Angel One SmartAPI WebSocket V2 Binary Frame Parser
 *
 * Angel One sends data in binary format (not JSON).
 * This parser decodes those binary frames into Java objects.
 *
 * Binary frame structure for Mode 3 (Snap Quote - full OI data):
 * ┌─────────────────────────────────────────────────────────┐
 * │ Byte  0      : Subscription Mode (1=LTP, 2=Quote, 3=SnapQuote)
 * │ Byte  1      : Exchange Type (1=NSE, 2=NFO, 3=BSE, 4=BFO)
 * │ Bytes 2-26   : Token (25 bytes, null-padded ASCII string)
 * │ Bytes 27-34  : Sequence Number (8 bytes, little-endian long)
 * │ Bytes 35-42  : Exchange Timestamp (8 bytes)
 * │ Bytes 43-50  : LTP × 100 (8 bytes long → divide by 100)
 * │ Bytes 51-58  : Last Traded Quantity (8 bytes)
 * │ Bytes 59-66  : Average Traded Price × 100 (8 bytes)
 * │ Bytes 67-74  : Volume (8 bytes)
 * │ Bytes 75-82  : Total Buy Quantity (8 bytes)
 * │ Bytes 83-90  : Total Sell Quantity (8 bytes)
 * │ Bytes 91-98  : Open × 100 (8 bytes)
 * │ Bytes 99-106 : High × 100 (8 bytes)
 * │ Bytes 107-114: Low × 100 (8 bytes)
 * │ Bytes 115-122: Close × 100 (8 bytes)
 * │ Bytes 123-130: Open Interest (OI) — raw value (8 bytes)
 * │ Bytes 131-138: OI Day High (8 bytes)
 * │ Bytes 139-146: OI Day Low (8 bytes)
 * │ Bytes 147-154: 52-week High (8 bytes)
 * │ Bytes 155-162: 52-week Low (8 bytes)
 * │ Bytes 163-170: Upper Circuit Limit (8 bytes)
 * │ Bytes 171-178: Lower Circuit Limit (8 bytes)
 * │ Bytes 179-186: % Change from close × 100 (8 bytes)
 * └─────────────────────────────────────────────────────────┘
 * Total Mode 3 frame = 187 bytes
 */
@Component
public class V2BinaryParser {

    private static final Logger log = LoggerFactory.getLogger(V2BinaryParser.class);

    // Minimum frame sizes per mode
    private static final int MODE_LTP_SIZE       = 51;   // Mode 1
    private static final int MODE_QUOTE_SIZE      = 91;   // Mode 2
    private static final int MODE_SNAPQUOTE_SIZE  = 187;  // Mode 3

    /**
     * Parse a binary frame from Angel One WebSocket V2
     * Returns null if frame is too short or invalid
     */
    public LiveTick parse(byte[] bytes) {
        if (bytes == null || bytes.length < MODE_LTP_SIZE) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN);

        LiveTick tick = new LiveTick();

        try {
            // ── Byte 0: Subscription mode ──────────────────────────
            int mode = buf.get() & 0xFF;

            // ── Byte 1: Exchange type ───────────────────────────────
            int exchType = buf.get() & 0xFF;
            tick.setExchangeType(exchType);

            // ── Bytes 2–26: Token (25-byte null-padded ASCII) ───────
            byte[] tokenBytes = new byte[25];
            buf.get(tokenBytes);
            String token = new String(tokenBytes, StandardCharsets.US_ASCII)
                    .replace("\0", "").trim();
            tick.setToken(token);

            // ── Bytes 27–34: Sequence number (skip) ─────────────────
            buf.getLong();

            // ── Bytes 35–42: Exchange timestamp ─────────────────────
            long exchTs = buf.getLong();
            tick.setTimestamp(exchTs > 0 ? exchTs * 1000 : System.currentTimeMillis());

            // ── Bytes 43–50: LTP × 100 ──────────────────────────────
            long ltpRaw = buf.getLong();
            tick.setLtp(ltpRaw / 100.0);

            // ── Bytes 51–58: Last Traded Quantity ───────────────────
            buf.getLong(); // LTQ — skip

            if (bytes.length < MODE_QUOTE_SIZE) {
                return tick; // Mode 1 only
            }

            // ── Bytes 59–66: Average Traded Price × 100 ─────────────
            buf.getLong(); // ATP — skip

            // ── Bytes 67–74: Volume ──────────────────────────────────
            tick.setVolume(buf.getLong());

            // ── Bytes 75–82: Total Buy Qty ───────────────────────────
            buf.getLong();

            // ── Bytes 83–90: Total Sell Qty ──────────────────────────
            buf.getLong();

            if (bytes.length < MODE_SNAPQUOTE_SIZE) {
                return tick; // Mode 2 only
            }

            // ── Bytes 91–98: Open × 100 ─────────────────────────────
            tick.setOpen(buf.getLong() / 100.0);

            // ── Bytes 99–106: High × 100 ────────────────────────────
            tick.setHigh(buf.getLong() / 100.0);

            // ── Bytes 107–114: Low × 100 ────────────────────────────
            tick.setLow(buf.getLong() / 100.0);

            // ── Bytes 115–122: Close × 100 ──────────────────────────
            tick.setClose(buf.getLong() / 100.0);

            // ── Bytes 123–130: OPEN INTEREST ─────────────────────────
            // This is the KEY field for option chain analysis
            tick.setOi(buf.getLong());

            // ── Bytes 131–138: OI Day High ───────────────────────────
            tick.setOiDayHigh(buf.getLong());

            // ── Bytes 139–146: OI Day Low ────────────────────────────
            tick.setOiDayLow(buf.getLong());

            // Mode 3 full frame parsed successfully
            log.debug("Parsed V2 tick: token={} ltp={} oi={} exchType={}",
                    token, tick.getLtp(), tick.getOi(), exchType);

        } catch (Exception e) {
            log.warn("Frame parse error (len={}): {}", bytes.length, e.getMessage());
            return null;
        }

        return tick;
    }
}
