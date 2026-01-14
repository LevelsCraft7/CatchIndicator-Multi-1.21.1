package com.levelscraft7.catchindicator.client;

public enum DiscoveryStatus {
    CAUGHT,
    SEEN,
    UNKNOWN;

    public static DiscoveryStatus fromString(String s) {
        if (s == null) return UNKNOWN;
        if ("CAUGHT".equalsIgnoreCase(s)) return CAUGHT;
        if ("SEEN".equalsIgnoreCase(s)) return SEEN;
        return UNKNOWN;
    }
}
