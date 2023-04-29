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

import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;

import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Typed;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificatorManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import com.google.inject.Injector;

/**
 * Send email with payment link
 *
 */
@Path("emailpay")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class EmailPayResource extends BaseResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailPayResource.class);

    @Inject
    private Config config;

    @Inject
    private NotificatorManager notificatorManager;

    public EmailPayResource() {
    }

    public EmailPayResource(Injector injector, Config config) {
        this.config = config;
        notificatorManager = new NotificatorManager(injector, config);
        storage = injector.getInstance(Storage.class);
    }

    @GET
    @PermitAll
    public Response get(@QueryParam("id") String deviceId) {
        Device device;
        User user;
        try {
            device = storage.getObject(Device.class,
                    new Request(new Columns.All(), new Condition.Equals("id", deviceId)));

            user = storage.getObject(User.class,
                    new Request(new Columns.All(), new Condition.Equals("id", getUserId())));

            permissionsService.checkPermission(Device.class, getUserId(), Long.parseLong(deviceId));
            sendEmail(device, user);
        } catch (StorageException | MessageException | InterruptedException e) {
            LOGGER.error("Send notification error", e);
        }
        return Response.noContent().build();
    }

    public void sendEmailIfFeeTime(Device device, User user)
            throws MessageException, InterruptedException, ParseException, SQLException {
        if (device != null
                && isFeeTime(device.getFeeDate(), config.getInteger(Keys.PAY_HOW_MANY_DAYS_BEFORE_FEE))) {
            String lastFeeNotification = device.getString("_lastFeeNotification" + user.getId());
            Boolean notifyUser = false;
            if (lastFeeNotification != null) {
                Date lastFeeNotficationDate = new SimpleDateFormat("yyyy-MM-dd").parse(lastFeeNotification);
                if (isFrequencyTime(lastFeeNotficationDate,
                        config.getInteger(Keys.PAY_FREQUENCY_DAYS))) {
                    notifyUser = true;
                }
            } else {
                notifyUser = true;
            }
            if (notifyUser) {
                Thread.sleep(config.getInteger(Keys.PAY_ANTYSPAM_DELAY, 0));
                sendEmail(device, user);

                String pattern = "yyyy-MM-dd";
                DateFormat df = new SimpleDateFormat(pattern);
                Date today = Calendar.getInstance().getTime();
                String todayAsString = df.format(today);
                device.set("_lastFeeNotification" + user.getId(), todayAsString);
                device.setName(device.getName().replaceAll("\\s*\\(.*?\\)\\s*", ""));
                device.setName(device.getName() + " (" + config.getString(Keys.PAY_TO_PAY_MESSAGE) + ")");
                try {
                    storage.updateObject(device, new Request(
                            new Columns.All(),
                            new Condition.Equals("id", device.getId())));
                } catch (StorageException e) {
                    LOGGER.error("Device update error while sending payment message", e);
                }
            }
        }
    }

    public void sendEmail(Device device, User user) throws MessageException, InterruptedException {
        for (Typed method : notificatorManager.getAllNotificatorTypes()) {
            PayResource payResource = new PayResource(config);
            long amountToPay = payResource.getPaymentAmount(device);
            String paymentUrl = payResource.getPaymentURL(device.getId(), amountToPay);
            Event paymentEvent = new Event(Event.TYPE_PAYMENT_TIME, device.getId());
            paymentEvent.setPaymentUrl(paymentUrl);
            paymentEvent.setPaymentAmount(amountToPay);

            notificatorManager.getNotificator(method.getType()).send(user, paymentEvent, null);
        }
        LOGGER.info("Payment notification to: userId=" + user.getId() + " deviceId=" + device.getId());
    }

    private boolean isFeeTime(Date feeDate, Integer inHowManyDays) {
        if (feeDate == null || inHowManyDays == null) {
            return false;
        }
        LocalDate localFee = convertToLocalDateViaInstant(feeDate);
        LocalDate localNow = LocalDate.now();
        if (localNow.plusDays(inHowManyDays.longValue()).isAfter(localFee) && !localFee
                .plusDays(config.getInteger(Keys.PAY_HOW_MANY_DAYS_AFTER_FEE)).isBefore(localNow)) {
            return true;
        }
        return false;
    }

    private static boolean isFrequencyTime(Date feeDate, Integer inHowManyDays) {
        if (feeDate == null || inHowManyDays == null) {
            return false;
        }
        LocalDate localFee = convertToLocalDateViaInstant(feeDate);
        LocalDate localNow = LocalDate.now();
        if (localNow.minusDays(inHowManyDays.longValue()).isAfter(localFee)) {
            return true;
        }
        return false;
    }

    private static LocalDate convertToLocalDateViaInstant(Date dateToConvert) {
        return dateToConvert.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
