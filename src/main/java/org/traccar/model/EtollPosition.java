package org.traccar.model;

import org.traccar.storage.StorageName;

@StorageName("tc_etollpositions")
public class EtollPosition extends BaseModel {

    public static final String STATUS_WARNING = "warning";
    public static final String STATUS_INVALID_JSON = "invalidJson";
    public static final String STATUS_INVALID_FRAME = "invalidFrame";

    public EtollPosition() {
    }

    private long positionId;
    private long packageId;
    private String errorStatus;
    private String message;

    public long getPositionId() {
        return positionId;
    }
    public void setPositionId(long positionId) {
        this.positionId = positionId;
    }
    public long getPackageId() {
        return packageId;
    }
    public void setPackageId(long packageId) {
        this.packageId = packageId;
    }
    public String getErrorStatus() {
        return errorStatus;
    }
    public void setErrorStatus(String errorStatus) {
        this.errorStatus = errorStatus;
    }
    public String getMessage() {
        return message;
    }
    public void setMessage(String message) {
        this.message = message; //.substring(0, Math.min(message.length(), 300));
    }

}
