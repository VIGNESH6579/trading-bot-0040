package com.ocsentinel.model;

import lombok.Data;

@Data
public class SessionInfo {
    private String jwtToken;
    private String feedToken;
    private String clientCode;
    private String name;
    private boolean loggedIn;
}
