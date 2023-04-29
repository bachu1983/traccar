package org.traccar;

import java.util.Collection;
import java.util.Date;
import java.util.Iterator;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.EtollConnection.EtollFrameValidationException;
import org.traccar.EtollConnection.EtollServerNotAvailableException;
import org.traccar.model.EtollPackage;
import org.traccar.model.EtollPosition;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Limit;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

public class EtollThread extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger(EtollThread.class);

    private Storage storage;

    public EtollThread(Storage storage) {
        this.storage = storage;
    }

    @Override
    public void run() {
        while (true) {
            try {
               Collection<EtollPosition> positions = storage.getObjects(EtollPosition.class, new Request(
                       new Columns.All(), new Condition.Equals("packageid", 1), new Order("id"), new Limit(2000)));
               if (positions.size() > 0) {
                    EtollPackage epackage = new EtollPackage();
                    epackage.setCreateDate(new Date());
                    boolean etollServerAvailable = true;
                    boolean etollFrameValidated = true;

                    try {
                        send(storage, positions);
                    } catch (EtollServerNotAvailableException serverException) {
                        etollServerAvailable = false;
                        epackage.setMessage(StringUtils.truncate(serverException.toString(), 300));
                        LOGGER.error("[Etoll] Package exception", serverException);
                    } catch (EtollFrameValidationException frameException) {
                        etollFrameValidated = false;
                        epackage.setMessage(StringUtils.truncate(frameException.toString(), 300));
                        LOGGER.error("[Etoll] Package exception", frameException);
                    } catch (Exception e) {
                        etollServerAvailable = false;
                        epackage.setMessage(StringUtils.truncate(e.toString(), 300));
                        LOGGER.error("[Etoll] Package exception", e);
                    }

                    epackage.setId(storage.addObject(epackage, new Request(new Columns.Exclude("id"))));
                    storage.updateObject(epackage,
                            new Request(new Columns.All(), new Condition.Equals("id", epackage.getId())));
                    if (etollServerAvailable) {
                        for (Iterator<EtollPosition> iterator = positions.iterator(); iterator.hasNext();) {
                            EtollPosition etollPosition = iterator.next();
                            etollPosition.setPackageId(epackage.getId());
                            if (!etollFrameValidated) {
                                etollPosition.setErrorStatus(EtollPosition.STATUS_INVALID_FRAME);
                            }
                            storage.updateObject(etollPosition, new Request(new Columns.All(), new Condition.Equals("id", etollPosition.getId())));
                        }
                    }
                    epackage.setUpdateDate(new Date());
                    storage.updateObject(epackage,
                            new Request(new Columns.All(), new Condition.Equals("id", epackage.getId())));
                }
            } catch (Exception e) {
                LOGGER.error("[Etoll] Package error", e);
            }

            try {
                Thread.sleep(6 * 10 * 1000);
            } catch (InterruptedException e) {
                LOGGER.error("[Etoll] Package error", e);
            }
        }
    }

    private void send(Storage storage, Collection<EtollPosition> positions) throws Exception {
        EtollConnection ec = new EtollConnection(storage);
        ec.validateAndSendEtollPositionsToEtoll(positions);
    }

}
