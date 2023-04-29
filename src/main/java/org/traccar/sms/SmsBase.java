package org.traccar.sms;

import com.fasterxml.jackson.annotation.JsonIgnore;

public abstract class SmsBase {

    @JsonIgnore
    private static final String REST_URI = "https://justsend.pl/api/rest/v2";

    @JsonIgnore
    private final String serviceName;

    public SmsBase(String serviceName) {
        super();
        this.serviceName = serviceName;
    }

    @JsonIgnore
    private final String appKey = "JDJhJDEyJHhxTHpyQTBjN0dLU2pMbkNlbjI5WGVIc3dpdzI4Q2FPdVBrLkVsNXNoY1Nyb3NrbzBpU3J5";

    public String getAppKey() {
        return appKey;
    }

    public String getUrl() {
        return REST_URI + serviceName;
    }
}
