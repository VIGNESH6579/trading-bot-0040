package com.ocsentinel.model;

import lombok.Data;

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
