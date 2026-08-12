package com.example.temperate.web.user.voice;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 严格解析 Voice WebSocket v2 子协议头，并只提取一次性 Ticket 而不记录原始 Header。
 */
public final class VoiceWebSocketProtocolParser {

    public static final String PROTOCOL = "ait-voice-v2";
    private static final int MAX_HEADER_LENGTH = 128;
    private static final Pattern TICKET = Pattern.compile(
            "^ait-ticket\\.([A-Za-z0-9_-]{43})$");

    private VoiceWebSocketProtocolParser() {
    }

    public static ParsedVoiceProtocol parse(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_HEADER_LENGTH
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Voice WebSocket protocol is invalid.");
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Voice WebSocket protocol is invalid.");
        }
        Set<String> tokens = new HashSet<>();
        String rawTicket = null;
        boolean protocolPresent = false;
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty() || !tokens.add(token)) {
                throw new IllegalArgumentException("Voice WebSocket protocol is invalid.");
            }
            if (PROTOCOL.equals(token)) {
                protocolPresent = true;
                continue;
            }
            java.util.regex.Matcher matcher = TICKET.matcher(token);
            if (!matcher.matches() || rawTicket != null) {
                throw new IllegalArgumentException("Voice WebSocket protocol is invalid.");
            }
            rawTicket = matcher.group(1);
        }
        if (!protocolPresent || rawTicket == null) {
            throw new IllegalArgumentException("Voice WebSocket protocol is invalid.");
        }
        return new ParsedVoiceProtocol(rawTicket);
    }

    /** 表示通过严格语法校验后得到的最小 Ticket 材料。 */
    public record ParsedVoiceProtocol(String rawTicket) {
    }
}
