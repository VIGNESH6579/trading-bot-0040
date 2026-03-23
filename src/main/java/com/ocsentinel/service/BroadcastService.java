package com.ocsentinel.service;

import com.ocsentinel.model.OCUpdate;
import com.ocsentinel.model.StatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    @Autowired
    private SimpMessagingTemplate stomp;

    /**
     * Push live OC update to all browser clients.
     * Browser subscribes to /topic/oc-update
     */
    public void ocUpdate(OCUpdate update) {
        try {
            stomp.convertAndSend("/topic/oc-update", update);
        } catch (Exception e) {
            log.error("OC broadcast error: {}", e.getMessage());
        }
    }

    /**
     * Push connection status to browser.
     * Browser subscribes to /topic/status
     */
    public void status(StatusMessage msg) {
        try {
            stomp.convertAndSend("/topic/status", msg);
        } catch (Exception e) {
            log.error("Status broadcast error: {}", e.getMessage());
        }
    }
}
