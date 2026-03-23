package com.ocsentinel.model;

import lombok.Data;

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
