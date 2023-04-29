package org.traccar.database;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

@Singleton
public class ExtendedManager {

    @Inject
    private Storage storage;

    @Inject
    public ExtendedManager(Storage storage) {
        this.storage = storage;

    }

    public Collection<Device> getAllNotManagedDevices() throws StorageException {
        try {
            Map<Long, Device> allDevicesIds = storage.getObjects(Device.class, new Request(new Columns.All())).stream()
                    .collect(Collectors.toMap(Device::getId, Function.identity(), (o1, o2) -> o1, HashMap::new));

            Collection<User> allManagerUsers = storage.getObjects(User.class,
                    new Request(new Columns.All(),
                            new Condition.And(new Condition.Compare("userlimit", ">", "limit", "0"),
                                    new Condition.Equals("administrator", "0"))));

            for (User user : allManagerUsers) {
                Collection<Device> managerDevices = storage.getObjects(Device.class, new Request(
                        new Columns.Include("id"), new Condition.Permission(User.class, user.getId(), Device.class)));
                for (Device device : managerDevices) {
                    if (allDevicesIds.containsKey(device.getId())) {
                        allDevicesIds.remove(device.getId());
                    }
                }
            }
            return allDevicesIds.values();
        } catch (StorageException e) {
            throw new StorageException(e);
        }
    }
}
