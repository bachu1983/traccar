package org.traccar.sms;

public class SmsToSend extends SmsBase {

    public static final String TYPE_ECONOMIC = "ECO";
    public static final String TYPE_UNIQUE = "FULL";
    public static final String SENDER_NAME_INFO = "INFO";

    private String from;
    private String message;
    private String bulkVariant;
    private String doubleEncode = "false";

    public SmsToSend(String to, String from, String message, String bulkVariant) {
        super("/message/send");
        this.to = to;
        this.from = from;
        this.message = message;
        this.bulkVariant = bulkVariant;
    }

    public String toString() {
        return message;
    }

    private String to;
    public String getTo() {
        return to;
    }
    public void setTo(String to) {
        if (!to.startsWith("48")) {
            this.to = "48" + to;
        }
        this.to = to;
    }
    public String getFrom() {
        return from;
    }
    public void setFrom(String from) {
        this.from = from;
    }
    public String getMessage() {
        return message;
    }
    public void setMessage(String message) {
        this.message = message;
    }
    public String getBulkVariant() {
        return bulkVariant;
    }
    public void setBulkVariant(String bulkVariant) {
        this.bulkVariant = bulkVariant;
    }
    public String getDoubleEncode() {
        return doubleEncode;
    }
    public void setDoubleEncode(String doubleEncode) {
        this.doubleEncode = doubleEncode;
    }
}
