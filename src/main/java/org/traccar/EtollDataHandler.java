/*
 * Copyright 2015 Anton Tananaev (anton@traccar.org)
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

import java.text.ParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.geofence.GeofencePolygon;
import org.traccar.model.Device;
import org.traccar.model.EtollPosition;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import io.netty.channel.ChannelHandler;

@Singleton
@ChannelHandler.Sharable
public class EtollDataHandler extends BaseDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EtollDataHandler.class);

    private static final String ETOLL = "_etoll";

    public static final String IN_ETOLL_GEOFENCE = "_inEtollGeofence";

    private final CacheManager cacheManager;

    private Storage storage;

    private static Set<GeofencePolygon> etollGeofences = null;

    private static Boolean isEtollGeofenceEnabled = null;

    @Inject
    public EtollDataHandler(Config config, CacheManager cacheManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.storage = storage;

        if (isEtollGeofenceEnabled == null) {
            Boolean enabled = config.getBoolean(Keys.ETOLL_GEOFENCE);
            if (enabled != null && enabled) {
                isEtollGeofenceEnabled = true;
            } else {
                isEtollGeofenceEnabled = false;
                LOGGER.info("[Etoll] Geofences disabled");
            }
        }

        if (isEtollGeofenceEnabled && etollGeofences == null) {
            etollGeofences = new HashSet<GeofencePolygon>();
            try {
                Collection<Geofence> geofences = storage.getObjects(Geofence.class, new Request(new Columns.All()));
                for (Iterator<Geofence> iterator = geofences.iterator(); iterator.hasNext();) {
                    Geofence geofence = iterator.next();
                    if (geofence.getName().startsWith("_etoll")) {
                        try {
                            etollGeofences.add(new GeofencePolygon(geofence.getArea()));
                        } catch (ParseException e) {
                            LOGGER.info("[Etoll] Cannot parse geofence: " + geofence.getName());
                        }
                    }
                }
            } catch (StorageException e) {
                LOGGER.error("[Etoll] Geofences loading error");
            }
            LOGGER.info("[Etoll] Geofences loaded: " + etollGeofences.size());
        }
    }

    @Override
    protected Position handlePosition(Position position) {
        try {
            Device device = cacheManager.getObject(Device.class, position.getDeviceId());
            if (device.getAttributes().containsKey(ETOLL)) {
                Position lastPosition = getLastPosition(device);
                if (isEtollGeofenceEnabled && isInEtollGeofence(lastPosition)) {
                    EtollPosition eposition = new EtollPosition();
                    eposition.setPositionId(lastPosition.getId());
                    eposition.setPackageId(1);
                    storage.addObject(eposition, new Request(new Columns.All()));

                    device.set(IN_ETOLL_GEOFENCE, "true");
                    cacheManager.updateDevice(true, device);
                } else if (device.getAttributes().containsKey(IN_ETOLL_GEOFENCE)) {
                    device.getAttributes().remove(IN_ETOLL_GEOFENCE);
                    cacheManager.updateDevice(true, device);
                }

            }
        } catch (Exception error) {
            LOGGER.warn("Failed to store etoll position", error);
        }

        return position;
    }

    private Position getLastPosition(Device device) {
        return cacheManager.getPosition(device.getId());
    }

    private boolean isInEtollGeofence(Position lastPosition) {
        if (lastPosition != null) {
            for (Iterator<GeofencePolygon> iterator = etollGeofences.iterator(); iterator.hasNext();) {
                GeofencePolygon geofencePolygon = iterator.next();
                if (geofencePolygon.containsPoint(null, null, lastPosition.getLatitude(), lastPosition.getLongitude())) {
                    return true;
                }
            }
        }
        return false;
    }
}
