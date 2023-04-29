/*
 * Copyright 2015 Amila Silva
 * Copyright 2016 - 2017 Anton Tananaev (anton@traccar.org)
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

import java.sql.SQLException;
import java.time.LocalTime;
import java.util.Date;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.session.DeviceSession;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import io.netty.channel.ChannelHandler;

@Singleton
@ChannelHandler.Sharable
public class FrequencyHandler extends BaseDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrequencyHandler.class);

    private static final String IGNITION_OFF_SEND_FREQUENCY = "_ignitionOffSendFrequency";
    private static final String IGNITION_ON_SEND_FREQUENCY = "_ignitionOnSendFrequency";

    private static final String IGNITION_SUPPORTED = "_ignitionSupported";
    private static final String IGNITION_START_TIME = "_ignitionStartTime";

    private static final String FREQUENCY_SUPPORTED = "_frequencySupported";

    private static final String IGNITION_ON_FREQUENCY_COMMAND = "command.ignitionOnFrequency";
    private static final String IGNITION_OFF_FREQUENCY_COMMAND = "command.ignitionOffFrequency";
    private static final String RESTART_COMMAND = "command.restart";
    private static final String FREQUENCY_STOP_COMMAND = "command.frequencyStop";

    private final CacheManager cacheManager;
    private Storage storage;
    private Config config;
    private ConnectionManager connectionManager;

    @Inject
    public FrequencyHandler(Config config, CacheManager cacheManager, Storage storage,
            ConnectionManager connectionManager) {
        this.cacheManager = cacheManager;
        this.storage = storage;
        this.config = config;
        this.connectionManager = connectionManager;
    }

    @Override
    protected Position handlePosition(Position position) {
        try {
            if ((position.getServerTime().getTime() - position.getFixTime().getTime()) < 10000) {
                boolean frequencyForProtocolEnabled = config
                        .getBoolean(Keys.FREQUENCY_ENABLED.withPrefix(position.getProtocol()));

                boolean handle = false;
                Device device = cacheManager.getObject(Device.class, position.getDeviceId());
                if (frequencyForProtocolEnabled && !device.getModel().startsWith("SIM")) {
                    handle = true;
                } else {
                    if (device.getAttributes().containsKey(FREQUENCY_SUPPORTED)) {
                        boolean frequencyForDeviceEnabled = device.getString(FREQUENCY_SUPPORTED).equals("true");
                        if (frequencyForDeviceEnabled) {
                            handle = true;
                        }
                    }
                }
                if (handle) {
                    setFrequencyAttributes(position);
                    checkIfNeedUpdateByCommand(position);
                    checkIfIgnitionSupported(position);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return position;
    }

    private void checkIfNeedUpdateByCommand(Position position) {
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        Position last = getLastPosition(device);
        if (device.getAttributes().containsKey(IGNITION_OFF_SEND_FREQUENCY)
                && device.getAttributes().containsKey(IGNITION_ON_SEND_FREQUENCY) && last != null) {
            int offFrequency = device.getInteger(IGNITION_OFF_SEND_FREQUENCY);
            int onFrequency = device.getInteger(IGNITION_ON_SEND_FREQUENCY);
            boolean turnOffFrequencyCheck = onFrequency == 0 && offFrequency == 0;

            if (!turnOffFrequencyCheck) {
                long diff = (position.getFixTime().getTime() - last.getFixTime().getTime()) / 1000;

                if (diff >= 5) {
                    if (device.getAttributes().containsKey(IGNITION_SUPPORTED)
                            && device.getBoolean(IGNITION_SUPPORTED)) {
                        boolean ignition = position.getBoolean(Position.KEY_IGNITION);
                        if (ignition && Math.abs(diff - onFrequency) >= 10
                                || (!ignition && offFrequency == 0 && Math.abs(diff - onFrequency) >= 10)) {
                            sendCommand(position.getDeviceId(), getCommandByProtocol(position.getProtocol(),
                                    IGNITION_ON_FREQUENCY_COMMAND, String.valueOf(turnOffFrequencyCheck)), 0);
                        } else if (!ignition && offFrequency > 0 && Math.abs(diff - offFrequency) >= 10) {
                            sendCommand(
                                    position.getDeviceId(), getCommandByProtocol(position.getProtocol(),
                                            IGNITION_OFF_FREQUENCY_COMMAND, String.valueOf(offFrequency)), 0);
                        } else if (!ignition && position.getSpeed() > 5) {
                            // ST-901 hang
                            sendCommand(position.getDeviceId(),
                                    getCommandByProtocol(position.getProtocol(), RESTART_COMMAND, ""), 0);
                        }
                    } else if (offFrequency > 0 && Math.abs(diff - offFrequency) >= 10) {
                        sendCommand(position.getDeviceId(), getCommandByProtocol(position.getProtocol(),
                                IGNITION_OFF_FREQUENCY_COMMAND, String.valueOf(offFrequency)), 0);
                        //sendCommand(position.getDeviceId(), getCommandByProtocol(position.getProtocol(),
                        //        IGNITION_ON_FREQUENCY_COMMAND, new Integer(onFrequency).toString()), 5000);
                    } else if (Math.abs(diff - onFrequency) >= 10) {
                        sendCommand(position.getDeviceId(), getCommandByProtocol(position.getProtocol(),
                                IGNITION_ON_FREQUENCY_COMMAND, String.valueOf(onFrequency)), 0);
                    }
                }
            } else {
                sendCommand(position.getDeviceId(),
                        getCommandByProtocol(position.getProtocol(), FREQUENCY_STOP_COMMAND), 0);
                device.getAttributes().remove(IGNITION_ON_SEND_FREQUENCY);
                device.getAttributes().remove(IGNITION_OFF_SEND_FREQUENCY);

                updateDeviceAttributesOnly(device);
            }
        }
    }

    private void setFrequencyAttributes(Position position) throws SQLException {

        Device device = cacheManager.getObject(Device.class, position.getDeviceId()); // do argumentow funkcji
        Position last = getLastPosition(device);
        LocalTime time = LocalTime.now();
        int onCurrFrequency = device.getInteger(IGNITION_ON_SEND_FREQUENCY);
        int offCurrFrequency = device.getInteger(IGNITION_OFF_SEND_FREQUENCY);
        boolean offFrequencySupported = getCommandByProtocol(position.getProtocol(), IGNITION_OFF_FREQUENCY_COMMAND, "")
                .isEmpty() ? false : true;
        boolean fruequencyStopSupportedByProtocol = getCommandByProtocol(position.getProtocol(), FREQUENCY_STOP_COMMAND,
                "").isEmpty() ? false : true;
        boolean inEtollGeofence = device.getAttributes().containsKey(EtollDataHandler.IN_ETOLL_GEOFENCE);
        boolean hasDeviceOnOffFrquencyAttributes = device.getAttributes().containsKey(IGNITION_ON_SEND_FREQUENCY)
                && device.getAttributes().containsKey(IGNITION_OFF_SEND_FREQUENCY);

        if (last != null) {
            if (inEtollGeofence) {
                device.set(IGNITION_ON_SEND_FREQUENCY, 5);
                device.set(IGNITION_OFF_SEND_FREQUENCY, 5);
            } else if (!inEtollGeofence && fruequencyStopSupportedByProtocol && hasDeviceOnOffFrquencyAttributes) {
                device.set(IGNITION_ON_SEND_FREQUENCY, 0);
                device.set(IGNITION_OFF_SEND_FREQUENCY, 0);
            } else if (offFrequencySupported && device.getAttributes().containsKey(IGNITION_SUPPORTED)
                    && device.getBoolean(IGNITION_SUPPORTED)) {
                device.set(IGNITION_ON_SEND_FREQUENCY, 15);
                device.set(IGNITION_OFF_SEND_FREQUENCY, 300);
            } else {
                if (position.getSpeed() > 3
                        || (position.getAttributes().containsKey(Position.KEY_IGNITION)
                                && position.getBoolean(Position.KEY_IGNITION))
                                && (!device.getAttributes().containsKey(IGNITION_SUPPORTED)
                                        || (device.getAttributes().containsKey(IGNITION_SUPPORTED)
                                                && position.getBoolean(IGNITION_SUPPORTED)))) {
                    if (position.getSpeed() > 54) {
                        device.set(IGNITION_OFF_SEND_FREQUENCY, 30);
                        device.set(IGNITION_ON_SEND_FREQUENCY, 30);
                    } else {
                        device.set(IGNITION_OFF_SEND_FREQUENCY, 15);
                        device.set(IGNITION_ON_SEND_FREQUENCY, 15);
                    }
                } else if (last.getSpeed() < 3) {
                    if (time.getHour() >= 0 && time.getHour() <= 5) {
                        device.set(IGNITION_OFF_SEND_FREQUENCY, 150);
                        device.set(IGNITION_ON_SEND_FREQUENCY, 150);
                    } else if (time.getHour() >= 20 && time.getHour() <= 23) {
                        device.set(IGNITION_OFF_SEND_FREQUENCY, 75);
                        device.set(IGNITION_ON_SEND_FREQUENCY, 75);
                    } else {
                        device.set(IGNITION_OFF_SEND_FREQUENCY, 60);
                        device.set(IGNITION_ON_SEND_FREQUENCY, 60);
                    }
                }
                if (!offFrequencySupported) {
                    device.set(IGNITION_OFF_SEND_FREQUENCY, 0);
                }
            }
        }

        if (onCurrFrequency != device.getInteger(IGNITION_ON_SEND_FREQUENCY)
                || offCurrFrequency != device.getInteger(IGNITION_OFF_SEND_FREQUENCY)) {
            updateDeviceAttributesOnly(device);
        }
    }

    private void checkIfIgnitionSupported(Position position) throws SQLException {
            Device device = cacheManager.getObject(Device.class, position.getDeviceId());
            if (ignitionJustStarted(position)) {
                device.set(IGNITION_SUPPORTED, true);
                device.set(IGNITION_START_TIME, position.getFixTime().getTime());
                updateDeviceAttributesOnly(device);
            } else if (device.getAttributes().containsKey(IGNITION_START_TIME)
                    && device.getBoolean(IGNITION_SUPPORTED)) {
                boolean ignition = position.getBoolean(Position.KEY_IGNITION);
                long diff = new Date().getTime() - device.getLong(IGNITION_START_TIME);
                // int minutes = (int) ((diff / (1000 * 60)) % 60);
                int hours = (int) ((diff / (1000 * 60 * 60)));

                if ((ignition && hours > 11) || (!ignition && position.getSpeed() > 25)) {
                    device.set(IGNITION_SUPPORTED, false);
                    updateDeviceAttributesOnly(device);
                }
            }
    }

    private boolean ignitionJustStarted(Position position) {
        Position last = getLastPosition(cacheManager.getObject(Device.class, position.getDeviceId()));
        if (position.getAttributes().containsKey(Position.KEY_IGNITION) && last != null) {
            boolean ignition = position.getBoolean(Position.KEY_IGNITION);
            boolean ignitionlast = last.getBoolean(Position.KEY_IGNITION);
            if (ignition && !ignitionlast) {
                return true;
            }
        }
        return false;
    }

    private boolean sendCommand(long deviceId, String commandString, long delay) {
        DeviceSession deviceSession = connectionManager.getDeviceSession(deviceId);
        if (deviceSession != null && deviceSession.supportsLiveCommands()) {
            if (commandString != null && commandString.length() > 0) {
                Command command = new Command();
                command.setId(0);
                command.setTextChannel(false);
                command.set(Command.KEY_DATA, commandString);
                command.setType(Command.TYPE_CUSTOM);
                command.setDescription("Frequency command");
                command.setDeviceId(deviceId);
                Thread t = new Thread() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(delay);
                            deviceSession.sendCommand(command);
                        } catch (InterruptedException e) {
                            // throw new SecurityException("Sleep before sending interrupted");
                        }
                    }
                };
                t.run();
                return true;
            }
        }
        return false;
    }

    private String getCommandByProtocol(String protocol, String commandName, String parameter) {
        String part1 = config.getString(Keys.FREQUENCY_COMMAND.withPrefix(protocol + "." + commandName + "."));
        if (part1 != null && !part1.isEmpty() && parameter != null) {
            int paramIndex = part1.lastIndexOf('%');
            if (paramIndex != -1) {
                int numberOfZeros = Character.getNumericValue(part1.charAt(paramIndex - 1));
                String zeros = "";
                for (int j = 0; j < numberOfZeros - parameter.length(); j++) {
                    zeros = zeros + "0";
                }
                return part1.replaceFirst(numberOfZeros + "%", zeros + parameter);
            } else {
                return part1;
            }
        }
        return "";
    }

    private String getCommandByProtocol(String protocol, String commandName) {
        return getCommandByProtocol(protocol,  commandName, null);
    }

    private Position getLastPosition(Device device) {
        return cacheManager.getPosition(device.getId());
    }

    private void updateDeviceAttributesOnly(Device device) {
        try {
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"),
                    new Condition.Equals("id", device.getId())));
        } catch (StorageException e) {
            LOGGER.warn("Cannot update", e);
        }
        cacheManager.updateDevice(true, device);
    }

}
