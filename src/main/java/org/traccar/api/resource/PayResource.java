/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 developers-payu-latam
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.traccar.api.resource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Base64;
import java.util.Locale;

import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.codec.digest.DigestUtils;
import org.glassfish.jersey.client.ClientProperties;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.tool.AES;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PauU payment
 *
 * @author PayU Maciej
 */
@Path("pay")
@Produces(MediaType.TEXT_HTML)
@Consumes(MediaType.APPLICATION_JSON)
public final class PayResource extends BaseResource {

    /** MD5 algorithm used */
    public static final String MD5_ALGORITHM = "md5";
    /** SHA algorithm used */
    public static final String SHA_ALGORITHM = "sha";

    /** Decimal format with no decimals */
    public static final String DECIMAL_FORMAT_1 = "###";
    /** Decimal format with one decimal */
    public static final String DECIMAL_FORMAT_2 = "###.0";
    /** Decimal format with two decimals */
    public static final String DECIMAL_FORMAT_3 = "###.00";

    /** The transaction value */
    public static final String TX_VALUE = "TX_VALUE";

    @Inject
    private Config config;

    /**
     * Private constructor
     */
    @Inject
    public PayResource(Config config) {
        this.config = config;
    }

    @GET
    @PermitAll
    public String get(@QueryParam("i") String encryptedDeviceId, @QueryParam("a") String encryptedTotalAmount) {
        try {
            Device device = getDevice(encryptedDeviceId);
            Long totalAmount = Long.valueOf(getAmount(encryptedTotalAmount));
            boolean validAmount = isValidTotalAmount(device, totalAmount);
            if (validAmount) {
                String body = pay(device, totalAmount);
                ObjectMapper mapper = new ObjectMapper();
                java.net.URI location = new java.net.URI(mapper.readTree(body).get("redirectUri").asText());
                throw new WebApplicationException(Response.temporaryRedirect(location).build());
            } else {
                return htmlAlert("Błąd!",
                        "Link do płatności stracił ważność. "
                        + "Użyj najnowszego jaki otrzymałeś lub wygeneruj nowy za pomocą ikony koszyka.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        // buildSignature("", "", new Integer(123), "", "", SHA_ALGORITHM);
        return htmlAlert("Błąd!", "");
    }

    private boolean isValidTotalAmount(Device device, Long toCheckTotalAmount) {
        Long currentTotalAmount = getPaymentAmount(device);
        if (toCheckTotalAmount >= currentTotalAmount) {
            return true;
        }
        return false;
    }

    /**
     * builds the signature for the transaction
     *
     * @param order
     *            The order that will have the signature
     * @param merchantId
     *            The merchantId into the signature
     * @param key
     *            The apiKey of the merchant
     * @param valueFormat
     *            The format to use for decimal values
     * @param algorithm
     *            The algorithm to use
     * @return the full signature to use
     */
    public static String buildSignature(String referenceCode, String currency, Integer merchantId, String key,
            String valueFormat, String algorithm) {

        String message = buildMessage(referenceCode, currency, merchantId, key, valueFormat);

        if (MD5_ALGORITHM.equalsIgnoreCase(algorithm)) {
            return DigestUtils.md5Hex(message);
        } else if (SHA_ALGORITHM.equalsIgnoreCase(algorithm)) {
            return DigestUtils.shaHex(message);
        } else {
            throw new IllegalArgumentException("Could not create signature. Invalid algoritm");
        }
    }

    /**
     * The message the signature will use
     *
     * @param order
     *            The order that will have the signature
     * @param merchantId
     *            The merchantId into the signature
     * @param key
     *            The apiKey of the merchant
     * @param valueFormat
     *            The format to use for decimal values
     * @return the message that will go into the signature
     */
    private static String buildMessage(String referenceCode, String currency, Integer merchantId, String key,
            String valueFormat) {

        // validateOrder(order, merchantId);

        DecimalFormat df = (DecimalFormat) NumberFormat.getInstance(Locale.US);
        df.applyPattern(valueFormat);

        StringBuilder message = new StringBuilder();
        message.append(key);
        message.append("~");
        message.append(merchantId);
        message.append("~");
        message.append(referenceCode);
        message.append("~");
        // message.append(df.format(order.getAdditionalValue(TX_VALUE).getValue()
        // .doubleValue()));
        // message.append("~");
        message.append(currency);

        return message.toString();
    }

    private String getPayUToken() throws IOException {
        String uri = "https://secure.payu.com/pl/standard/user/oauth/authorize";

        Client client = ClientBuilder.newClient();

        WebTarget resource = client.target(uri).queryParam("grant_type", "client_credentials")
                .queryParam("client_id", "307547").queryParam("client_secret", "0f611ed33899d5c9170be019b9184493")
                .queryParam("pos_id", "307547");

        Invocation.Builder request = resource.request();
        request.accept(MediaType.APPLICATION_JSON);
        Response response = request.get();

        //System.out.println("ERROR! " + response.getStatus());
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readTree(response.readEntity(String.class)).get("access_token").asText();
    }

    private String pay(Device device, Long totalAmount) throws IOException {
        String token = getPayUToken();
        //System.out.println(token);

        Client client = ClientBuilder.newClient();
        Entity payload = Entity.json("{  \"notifyUrl\": \"" + config.getString(Keys.PAY_CONFIRM_ADDRESS)
                + "\"," + "  \"customerIp\": \"127.0.0.1\","
                + "\"merchantPosId\": \"307547\",  \"description\": \"Przedłużenie usługi lokalizacji GPS ("
                + device.getPhone() + ")\", \"additionalDescription\":\""
                + device.getUniqueId() + "\"" + "," + "\"currencyCode\": \"PLN\","
                + "\"totalAmount\": \"" + totalAmount + "00" + "\",  \"products\":"
                + "[    {     \"name\": \"Dostęp do systemu oraz utrzymanie karty telemetrycznej przez kolejny rok\",  "
                + "    \"unitPrice\": \"" + totalAmount + "\",      \"quantity\": \"1\"    }]}\"");
        Response response = client.target("https://secure.payu.com/api/v2_1/orders/")
                .request(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)
                .property(ClientProperties.FOLLOW_REDIRECTS, Boolean.FALSE).post(payload);

        // System.out.println("status: " + response.getStatus());
        // System.out.println("headers: " + response.getHeaders());
        String body = response.readEntity(String.class);
        // System.out.println("body:" + body);
        return body;
    }

    private static String key = "gaderypoluki123!.";

    private Device getDevice(String encrypteDeviceId) {
        String decodedFromBase64 = new String(Base64.getDecoder().decode(encrypteDeviceId));
        long deviceId = Long.parseLong(AES.decrypt(decodedFromBase64, key));
        try {
            return  storage.getObject(Device.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("id", deviceId)));
        } catch (StorageException e) {
            return null;
        }
    }

    private String getAmount(String encryptedTotalAmount) {
        String decodedFromBase64 = new String(Base64.getDecoder().decode(encryptedTotalAmount));
        Integer amount = Integer.parseInt(AES.decrypt(decodedFromBase64, key));
        return amount.toString();
    }

    public String getPaymentURL(long deviceId, long amount) {
        String encI = AES.encrypt(new Long(deviceId).toString(), key);
        String encA = AES.encrypt(new Long(amount).toString(), key);

        String i = Base64.getEncoder().encodeToString(encI.getBytes());
        String a = Base64.getEncoder().encodeToString(encA.getBytes());

        return config.getString(Keys.PAY_WEB_URL, "") + "/api/pay?i=" + i + "&a=" + a;
    }

    public long getPaymentAmount(Device device) {
        long base = config.getLong(Keys.PAY_TARIF_BASE);
        long amountToPay = base;

        if (device.getAttributes().containsKey("_R")) {
            // TODO amountToPay =
            // amountToPay + Context.getConfig().getLong("pay.tarifR" + device.getAttributes().get("_R"));
            System.out.println("");
        }
        return amountToPay;
    }

    private String htmlAlert(String header, String message) {
        return "<!DOCTYPE html>\n" + "<html>\n"
                + "<meta charset=\"UTF-8\" name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<head>\n"
                + "<link rel=\"stylesheet\" "
                + "href=\"https://maxcdn.bootstrapcdn.com/bootstrap/3.4.0/css/bootstrap.min.css\">"
                + "</head>\n"
                + "<body style=\"margin:10px\">" + "<div class=\"alert alert-warning\" role=\"alert\">\n"
                + "  <h4 class=\"alert-heading\">" + header + "</h4>\n" + "  <p>" + message + "</p>\n"
                + "</div></body>\n" + "</html>";
    }
}
