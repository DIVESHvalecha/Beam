package com.beam.beam_signaling;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SignalMapper {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    public SignalMessage toMessage(String json) {
        return objectMapper.readValue(json, SignalMessage.class);
    }

    public String toJson(SignalMessage message) {
        return objectMapper.writeValueAsString(message);
    }
}