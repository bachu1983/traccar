package org.traccar.sms;

public class SmsResponse {
    private String responseCode;
    private String errorId;
    private String message;
    private String data;
    public String getResponseCode() {
        return responseCode;
    }
    public void setResponseCode(String responseCode) {
        this.responseCode = responseCode;
    }
    public String getErrorId() {
        return errorId;
    }
    public void setErrorId(String errorId) {
        this.errorId = errorId;
    }
    public String getMessage() {
        return message;
    }
    public void setMessage(String message) {
        this.message = message;
    }
    public String getData() {
        return data;
    }
    public void setData(String data) {
        this.data = data;
    }

}
