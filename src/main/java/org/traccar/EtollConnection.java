package org.traccar;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.etoll.EtollPositionJson;
import org.traccar.model.Device;
import org.traccar.model.EtollPosition;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Limit;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EtollConnection {

    private static final Logger LOGGER = LoggerFactory.getLogger(EtollConnection.class);

    private static final String CA_KEYSTORE_TYPE = KeyStore.getDefaultType(); // "JKS";
    private static final String CA_KEYSTORE_PATH = "./etoll/etollcert.jks";
    private static final String CA_KEYSTORE_PASS = "123456";

    private static final String CLIENT_KEYSTORE_TYPE = "PKCS12";
    private static final String CLIENT_KEYSTORE_PATH = "./etoll/etollcert.p12";
    private static final String CLIENT_KEYSTORE_PASS = "123456";

    private Storage storage;

    public EtollConnection(Storage storage) {
        this.storage = storage;
    }

    public void validateAndSendEtollPositionsToEtoll(Collection<EtollPosition> positions) throws Exception {
        ArrayList<EtollPositionJson> etollPositionsList = new ArrayList<>();

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        for (Iterator<EtollPosition> iterator = positions.iterator(); iterator.hasNext();) {
            EtollPosition etollPosition = iterator.next();
            Position position = storage.getObject(Position.class, new Request(
                    new Columns.All(), new Condition.Equals("id", etollPosition.getPositionId())));
            if (isPoland(position)) {
                EtollPositionJson etollPositionJson = toEtollJson(position);

                StringBuilder validationMessage = new StringBuilder();
                Set<ConstraintViolation<EtollPositionJson>> violations = validator.validate(etollPositionJson);
                for (Iterator<ConstraintViolation<EtollPositionJson>> iteratorEpj = violations.iterator(); iteratorEpj
                        .hasNext();) {
                    ConstraintViolation<EtollPositionJson> constraintViolation = iteratorEpj.next();
                    validationMessage.append(constraintViolation.getPropertyPath() + "="
                            + constraintViolation.getInvalidValue() + " " + constraintViolation.getMessage());
                }

                if (validationMessage.toString().isEmpty()) {
                    etollPositionsList.add(etollPositionJson);
                    etollPosition.setErrorStatus(null);
                } else {
                    try {
                        EtollPosition invalidEtollPosition = storage.getObject(EtollPosition.class, new Request(
                                new Columns.All(), new Condition.Equals("id", etollPosition.getId())));

                        if (invalidEtollPosition != null) {
                            invalidEtollPosition.setErrorStatus(EtollPosition.STATUS_INVALID_JSON);
                            invalidEtollPosition.setMessage(StringUtils.truncate(validationMessage.toString(), 300));
                            invalidEtollPosition.setPackageId(1);
                            storage.updateObject(invalidEtollPosition, new Request(new Columns.All()));
                        }
                    } catch (StorageException e) {
                        LOGGER.info("[Etoll] Cannot update invalid position: " + etollPosition.getId());
                    }
                }
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        String json = "";
        try {
            json = mapper.writeValueAsString(etollPositionsList);
        } catch (JsonProcessingException e) {
            LOGGER.info("[Etoll] Cannot create JSON");
        }

        try {
            if (!json.isEmpty()) {
                sendEtollPositionsToEtoll(json);
            } else {
                LOGGER.info("[Etoll] Json is empty");
            }
        } catch (Exception e) {
            LOGGER.info("[Etoll] Cannot sent positions to Etoll");
            throw e;
        }
    }

    private boolean isPoland(Position position) {
        if (position != null) {
            if (position.getLongitude() < 14.116667 && position.getLongitude() > 24.15) {
                return false;
            } else if (position.getLatitude() < 49.0) {
                return false;
            } else if (54.9 - position.getLatitude() - 0.3 * position.getLongitude() > 0) {
                return false;
            } else if (1.25 * position.getLongitude() + 20.375 - position.getLatitude() > 0) {
                return false;
            }
        }
        return true;
    }

    private void sendEtollPositionsToEtoll(String json) throws Exception {
        SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(createSslCustomContext(),
                new String[] {"TLSv1.2"},  // Allow TLSv1 protocol only
                null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());
        try (CloseableHttpClient httpclient = HttpClients.custom().setSSLSocketFactory(csf).build()) {
            HttpPost req = new HttpPost("https://spoe-dev.il-pib.pl:8443/zsl/ssl/c667fbad-6ef5-44e2-9d82-a95853917053");
            // req.setConfig(configureRequest());
            StringEntity requestEntity = new StringEntity(json, ContentType.APPLICATION_JSON);
            req.setEntity(requestEntity);
            try (CloseableHttpResponse response = httpclient.execute(req)) {
                HttpEntity entity = response.getEntity();
                int status = response.getStatusLine().getStatusCode();
                String content = EntityUtils.toString(entity);
                LOGGER.info("[Etoll] HTTP response: " + status + " " + content);
                if (status == 415) {
                    throw new EtollFrameValidationException(status + " " + content);
                } else if (status != 200) {
                    throw new EtollServerNotAvailableException(
                            status + " " + response.getStatusLine().getReasonPhrase());
                }
            }
        }
    }

    public static RequestConfig configureRequest() {
        HttpHost proxy = new HttpHost("changeit.local", 8080, "http");
        RequestConfig config = RequestConfig.custom().setProxy(proxy).build();
        return config;
    }

    public static SSLContext createSslCustomContext() throws KeyStoreException, IOException, NoSuchAlgorithmException,
            CertificateException, KeyManagementException, UnrecoverableKeyException {
        // Trusted CA keystore
        KeyStore tks = KeyStore.getInstance(CA_KEYSTORE_TYPE);
        tks.load(new FileInputStream(CA_KEYSTORE_PATH), CA_KEYSTORE_PASS.toCharArray());

        // Client keystore
        KeyStore cks = KeyStore.getInstance(CLIENT_KEYSTORE_TYPE);
        cks.load(new FileInputStream(CLIENT_KEYSTORE_PATH), CLIENT_KEYSTORE_PASS.toCharArray());

        SSLContext sslcontext = SSLContexts.custom()
                // .loadTrustMaterial(tks, new TrustSelfSignedStrategy()) // use it to customize
                .loadKeyMaterial(cks, CLIENT_KEYSTORE_PASS.toCharArray()) // load client certificate
                .build();
        return sslcontext;
    }

    private EtollPositionJson toEtollJson(Position position) throws StorageException {
        EtollPositionJson epj = null;
        if (position != null) {
            epj = new EtollPositionJson();
            epj.setDataId(Double.toString(position.getId()));
            epj.setEventType(getEventType(position));
            epj.setFixTimeEpoch(position.getFixTime().getTime() * 1000);
            epj.setGpsHeading(ensureRange(Math.round(position.getCourse() * 100) / 100.0, 0.0, 360.0));
            epj.setGpsSpeed(ensureRange(Math.round(position.getSpeed() * 0.5144 * 100) / 100.0, 0.0, 56.0));
            epj.setLatitude(ensureRange(Math.round(position.getLatitude() * 1000000000) / 1000000000.0, -90.0, 90.0));
            epj.setLongitude(
                    ensureRange(Math.round(position.getLongitude() * 1000000000) / 1000000000.0, -180.0, 180.0));
            epj.setSatellitesForFix((Integer) position.getAttributes().getOrDefault(Position.KEY_SATELLITES, 12));

            Device device =  storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", position.getDeviceId())));

            if (device.getAttributes().containsKey("_imei")) {
                epj.setSerialNumber(device.getString("_imei"));
            } else {
                epj.setSerialNumber(device.getUniqueId());
            }
        }
        return epj;
    }

    private EtollPositionJson.EventType getEventType(Position position) {
        EtollPositionJson.EventType type = EtollPositionJson.EventType.LOCATION;
        try {new Condition.Or(null, null);
            //Event event = Context.getDataManager().getEtollEvent(position.getDeviceId(), position.getId());
            List<Event> events = storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.And(new Condition.Equals("deviceid", position.getDeviceId()),
                                    new Condition.Equals("positionid", position.getId())),
                            new Condition.Or(new Condition.Equals("type", Event.TYPE_DEVICE_STOPPED),
                                    new Condition.Equals("type", Event.TYPE_DEVICE_MOVING))),
                    new Order("id"), new Limit(1)));
            if (!events.isEmpty()) {
                Event event = events.get(0);
                if (event.getType().equals(Event.TYPE_DEVICE_MOVING)) {
                    type = EtollPositionJson.EventType.STARTJOURNEY;
                } else if (event.getType().equals(Event.TYPE_DEVICE_STOPPED)) {
                    type = EtollPositionJson.EventType.ENDJOURNEY;
                }
                LOGGER.info("[Etoll] Position:" + position.getId() + " Event type:" + type);
            }
        } catch (Exception e) {
        }
        return type;
    }

    public static void main(String[] args) throws Exception {
        ArrayList<EtollPositionJson> al = new ArrayList<>();
        EtollPositionJson epj = new EtollPositionJson();
        al.add(epj);
        al.add(epj);
        epj.setDataId("19665472");
        epj.setEventType(EtollPositionJson.EventType.LOCATION);
        epj.setFixTimeEpoch(new Date().getTime());
        epj.setGpsHeading(5.0);
        epj.setGpsSpeed(45.0);
        epj.setLatitude(52.138791);
        epj.setLongitude(18.618390);
        epj.setSatellitesForFix(10);
        epj.setSerialNumber("12345678910");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(al);
        System.out.println(json);

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        Set<ConstraintViolation<EtollPositionJson>> violations = validator.validate(epj);
        StringBuilder builder = new StringBuilder();
        for (Iterator<ConstraintViolation<EtollPositionJson>> iterator = violations.iterator(); iterator.hasNext();) {
            ConstraintViolation<EtollPositionJson> constraintViolation = iterator.next();
            builder.append(constraintViolation.getMessage());
        }
        System.out.println(builder);

        EtollConnection ec = new EtollConnection(null);
        ec.sendEtollPositionsToEtoll(json);
    }

    public class EtollServerNotAvailableException extends Exception {
        public EtollServerNotAvailableException(String errorMessage) {
            super(errorMessage);
        }
    }

    public class EtollFrameValidationException extends Exception {
        public EtollFrameValidationException(String errorMessage) {
            super(errorMessage);
        }
    }

    private double ensureRange(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
