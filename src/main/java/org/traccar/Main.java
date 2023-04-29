/*
 * Copyright 2012 - 2022 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.resource.EmailPayResource;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.ExtendedManager;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.schedule.ScheduleManager;
import org.traccar.sms.SmsClient;
import org.traccar.sms.SmsToSend;
import org.traccar.sms.SmsTool;
import org.traccar.storage.DatabaseModule;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;
import org.traccar.web.WebModule;
import org.traccar.web.WebServer;

import com.google.inject.Guice;
import com.google.inject.Injector;


public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final long CLEAN_PERIOD = 24 * 60 * 60 * 1000;

    private static Injector injector;

    public static Injector getInjector() {
        return injector;
    }

    private Main() {
    }

    public static void logSystemInfo() {
        try {
            OperatingSystemMXBean operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
            LOGGER.info("Operating system"
                    + " name: " + operatingSystemBean.getName()
                    + " version: " + operatingSystemBean.getVersion()
                    + " architecture: " + operatingSystemBean.getArch());

            RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
            LOGGER.info("Java runtime"
                    + " name: " + runtimeBean.getVmName()
                    + " vendor: " + runtimeBean.getVmVendor()
                    + " version: " + runtimeBean.getVmVersion());

            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            LOGGER.info("Memory limit"
                    + " heap: " + memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024) + "mb"
                    + " non-heap: " + memoryBean.getNonHeapMemoryUsage().getMax() / (1024 * 1024) + "mb");

            LOGGER.info("Character encoding: "
                    + System.getProperty("file.encoding") + " charset: " + Charset.defaultCharset());

        } catch (Exception error) {
            LOGGER.warn("Failed to get system info");
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);

        final String configFile;
        if (args.length <= 0) {
            configFile = "./debug.xml";
            if (!new File(configFile).exists()) {
                throw new RuntimeException("Configuration file is not provided");
            }
        } else {
            configFile = args[args.length - 1];
        }

        if (args.length > 0 && args[0].startsWith("--")) {
            WindowsService windowsService = new WindowsService("traccar") {
                @Override
                public void run() {
                    Main.run(configFile);
                }
            };
            switch (args[0]) {
                case "--install":
                    windowsService.install("traccar", null, null, null, null, configFile);
                    return;
                case "--uninstall":
                    windowsService.uninstall();
                    return;
                case "--service":
                default:
                    windowsService.init();
                    break;
            }
        } else {
            run(configFile);
        }
    }

    public static void run(String configFile) {
        try {
            injector = Guice.createInjector(new MainModule(configFile), new DatabaseModule(), new WebModule());
            logSystemInfo();
            LOGGER.info("Version: " + Main.class.getPackage().getImplementationVersion());
            LOGGER.info("Starting server...");

            if (injector.getInstance(BroadcastService.class).singleInstance()) {
                DeviceUtil.resetStatus(injector.getInstance(Storage.class));
            }

            var services = Stream.of(
                    ServerManager.class, WebServer.class, ScheduleManager.class, BroadcastService.class)
                    .map(injector::getInstance)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            for (var service : services) {
                service.start();
            }

            Thread.setDefaultUncaughtExceptionHandler((t, e) -> LOGGER.error("Thread exception", e));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Stopping server...");

                for (var service : services) {
                    try {
                        service.stop();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }));

           new Timer().scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    LOGGER.info("APN update run");
                    try {
                        Storage storage = injector.getInstance(Storage.class);
                        Collection<Device> devices = storage.getObjects(Device.class, new Request(new Columns.All()));

                        for (Iterator<Device> iterator = devices.iterator(); iterator.hasNext();) {
                            Device device = iterator.next();
                            if (device.getAttributes().containsKey("apn") && device.getLastUpdate() != null) {
                                if (device.getStatus().equals(Device.STATUS_OFFLINE)) {
                                    Duration duration = Duration.between(
                                            convertToLocalDateViaInstant(device.getLastUpdate()),
                                            convertToLocalDateViaInstant(new Date()));
                                    long dminutes = duration.getSeconds() / 60;
                                    LocalTime timeNow = LocalTime.now();
                                    if ((dminutes > 30 && dminutes < 60)
                                            || (dminutes < 60 * 10 && dminutes > 60 && timeNow.getHour() == 8)) {
                                        sendApnMessage(device.getPhone());
                                        LOGGER.info("APN update phone " + device.getPhone());
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("APN error", e);
                    }
                }
            }, 5000, 600000);

            new Timer().scheduleAtFixedRate(new TimerTask() {

                @Override
                public void run() {
                    LOGGER.info("Send payment requests");

                    createDumpUsersFromNotAssignedDevices();

                    try {
                        Config config = injector.getInstance(Config.class);
                        Storage storage = injector.getInstance(Storage.class);

                        Collection<User> allUsers = storage.getObjects(User.class, new Request(new Columns.All()));
                        for (User user : allUsers) {
                            if (user.getUserLimit() != 0) {
                                List<Device> allUserDevices = storage.getObjects(Device.class, new Request(
                                        new Columns.All(),
                                        new Condition.Permission(User.class, user.getId(), Device.class)));
                                for (Device device : allUserDevices) {
                                    try {
                                       EmailPayResource payResource = new EmailPayResource(injector, config);
                                       payResource.sendEmailIfFeeTime(device, user);
                                       blockDeviceIfTime(storage, config, device);
                                    } catch (Exception e) {
                                        LOGGER.error("Send payment notifications error device:" + device, e);
                                    }
                                }
                            }
                        }
                    } catch (Exception error) {
                        LOGGER.warn("Send payment notifications error", error);
                    }
                }
            }, 0, CLEAN_PERIOD);

           new EtollThread(injector.getInstance(Storage.class)).run();
        } catch (Exception e) {
            LOGGER.error("Main method error", e);
            throw new RuntimeException(e);
        }
    }
    public static LocalDateTime convertToLocalDateViaInstant(Date dateToConvert) {
        return dateToConvert.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static void sendApnMessage(final String phoneNumber) {
       final SmsClient smsClient = new SmsClient();

        SmsToSend sms = new SmsToSend(phoneNumber, SmsToSend.SENDER_NAME_INFO, SmsTool.I10_APN_WITH_NEW_PASSWORD,
                SmsToSend.TYPE_UNIQUE);
        // TODO unhide smsClient.sendSms(sms);
    }


    public static void blockDeviceIfTime(Storage storage, Config config, Device device) throws SQLException {
        if (config.getBoolean(Keys.PAY_BLOCK_IF_TIME)) {
            LocalDateTime localFee = convertToLocalDateViaInstant(device.getFeeDate());
            LocalDateTime localNow = LocalDateTime.now();

            if (localFee.isBefore(localNow)) {
                device.setDisabled(true);
                device.setStatus(Device.STATUS_OFFLINE);
                device.setName(device.getName().replaceAll("\\s*\\(.*?\\)\\s*", ""));
                device.setName(device.getName() + " (" + config.getString(Keys.PAY_BLOCKED_MESSAGE) + ")");
                try {
                    storage.updateObject(device, new Request(
                            new Columns.All(),
                            new Condition.Equals("id", device.getId())));
                } catch (StorageException e) {
                    LOGGER.error("Cannot block device", e);
                }
            }
        }
    }

    public static void createDumpUsersFromNotAssignedDevices() {
        try {
            Collection<Device> devices = injector.getInstance(ExtendedManager.class).getAllNotManagedDevices();
            for (Device device : devices) {
                createDumpUserFromNotAssignedDevice(device);
            }
        } catch (Exception e) {
            LOGGER.error("createDumpUsersFromNotAssignedDevices", e);
        }
    }

    public static void createDumpUserFromNotAssignedDevice(Device device) throws SQLException, ClassNotFoundException {
        try {
        User userEntity = new User();
        userEntity.setEmail(device.getContact());
        userEntity.setName(device.getModel());
        userEntity.setDeviceLimit(0);
        userEntity.setUserLimit(4);
        userEntity.setLimitCommands(true);
        Storage storage = injector.getInstance(Storage.class);
        User foundedUser = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("email", device.getContact())));
        if (foundedUser == null || foundedUser.getAdministrator()) {
            userEntity.setId(storage.addObject(userEntity, new Request(new Columns.Exclude("id"))));
            storage.addObject(userEntity, new Request(new Columns.All()));
            //TODO: chyba powinno byæ update zamiast add
        } else {
            userEntity = foundedUser;
        }
        storage.addPermission(new Permission(User.class, userEntity.getId(), Device.class, device.getId()));
        } catch (StorageException e) {
            LOGGER.error("createDumpUserFromNotAssignedDevice", e);
        }
    }
}
