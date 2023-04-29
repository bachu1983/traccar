package org.traccar.model;

import java.util.Date;

import org.traccar.storage.StorageName;

@StorageName("tc_etollpackages")
public class EtollPackage extends BaseModel {

    private String message;
    private Date createDate;
    private Date updateDate;

    public EtollPackage() {
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message; //message.substring(0, Math.min(message.length(), 300));
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public Date getUpdateDate() {
        return updateDate;
    }

    public void setUpdateDate(Date updateDate) {
        this.updateDate = updateDate;
    }

}
