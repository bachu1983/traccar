package org.traccar.sms;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmsClient.class);

    private Client client = ClientBuilder.newClient();

    //public sendSMS() {
    //    WebTarget target = client.target(REST_URI + A )
    //}

    //SmsToSend sms = new SmsToSend("48660464149", "me", "testowanie", "ECO");

    public Response sendSms(SmsToSend sms) {
        Response response = client.target(sms.getUrl()).request(MediaType.APPLICATION_JSON)
                .header("App-Key", sms.getAppKey()).post(Entity.entity(sms, MediaType.APPLICATION_JSON));
        LOGGER.info("Sms response: " + response.getHeaders());
        return response;
    }

    public void sendSmsWithDelay(SmsToSend sms, long millis) {
        Thread t = new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(millis);
                    sendSms(sms);
                    //System.out.println(sms);
                } catch (InterruptedException e) {
                    throw new SecurityException("Sleep before sending password interrupted");
                }
            }
        };
        t.start();
    }

    /*public Driver getJsonDriver(int id) {
        return client.target(REST_URI).path(String.valueOf(id)).request(MediaType.APPLICATION_JSON).get(Driver.class);
    }

    public Response createXmlDriver(Driver emp) {
        return client.target(REST_URI).request(MediaType.APPLICATION_XML)
                .post(Entity.entity(emp, MediaType.APPLICATION_XML));
    }

    public Driver getXmlDriver(int id) {
        return client.target(REST_URI).path(String.valueOf(id)).request(MediaType.APPLICATION_XML).get(Driver.class);
    }*/
}
