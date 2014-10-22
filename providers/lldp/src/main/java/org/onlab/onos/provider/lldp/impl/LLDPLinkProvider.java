package org.onlab.onos.provider.lldp.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.onos.mastership.MastershipService;
import org.onlab.onos.net.ConnectPoint;
import org.onlab.onos.net.Device;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.Port;
import org.onlab.onos.net.device.DeviceEvent;
import org.onlab.onos.net.device.DeviceListener;
import org.onlab.onos.net.device.DeviceService;
import org.onlab.onos.net.link.LinkProvider;
import org.onlab.onos.net.link.LinkProviderRegistry;
import org.onlab.onos.net.link.LinkProviderService;
import org.onlab.onos.net.packet.PacketContext;
import org.onlab.onos.net.packet.PacketProcessor;
import org.onlab.onos.net.packet.PacketService;
import org.onlab.onos.net.provider.AbstractProvider;
import org.onlab.onos.net.provider.ProviderId;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.slf4j.LoggerFactory.getLogger;


/**
 * Provider which uses an OpenFlow controller to detect network
 * infrastructure links.
 */
@Component(immediate = true)
public class LLDPLinkProvider extends AbstractProvider implements LinkProvider {

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LinkProviderRegistry providerRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetSevice;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService masterService;

    private LinkProviderService providerService;

    private final boolean useBDDP = true;


    private final InternalLinkProvider listener = new InternalLinkProvider();

    protected final Map<DeviceId, LinkDiscovery> discoverers = new ConcurrentHashMap<>();

    /**
     * Creates an OpenFlow link provider.
     */
    public LLDPLinkProvider() {
        super(new ProviderId("lldp", "org.onlab.onos.provider.lldp"));
    }

    @Activate
    public void activate() {
        providerService = providerRegistry.register(this);
        deviceService.addListener(listener);
        packetSevice.addProcessor(listener, 0);
        LinkDiscovery ld;
        for (Device device : deviceService.getDevices()) {
            ld = new LinkDiscovery(device, packetSevice, masterService,
                              providerService, useBDDP);
            discoverers.put(device.id(), ld);
            for (Port p : deviceService.getPorts(device.id())) {
                ld.addPort(p);
            }
        }

        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        for (LinkDiscovery ld : discoverers.values()) {
            ld.stop();
        }
        providerRegistry.unregister(this);
        deviceService.removeListener(listener);
        packetSevice.removeProcessor(listener);
        providerService = null;

        log.info("Stopped");
    }


    private class InternalLinkProvider implements PacketProcessor, DeviceListener {

        @Override
        public void event(DeviceEvent event) {
            LinkDiscovery ld = null;
            Device device = event.subject();
            Port port = event.port();
            if (device == null) {
                log.error("Device is null.");
                return;
            }
            switch (event.type()) {
                case DEVICE_ADDED:
                    discoverers.put(device.id(),
                                    new LinkDiscovery(device, packetSevice, masterService,
                                                      providerService, useBDDP));
                    break;
                case PORT_ADDED:
                case PORT_UPDATED:
                    if (event.port().isEnabled()) {
                        ld = discoverers.get(device.id());
                        if (ld == null) {
                            return;
                        }
                        ld.addPort(port);
                    } else {
                        ConnectPoint point = new ConnectPoint(device.id(),
                                                              port.number());
                        providerService.linksVanished(point);
                    }
                    break;
                case PORT_REMOVED:
                    ConnectPoint point = new ConnectPoint(device.id(),
                                                          port.number());
                    providerService.linksVanished(point);
                    break;
                case DEVICE_REMOVED:
                case DEVICE_SUSPENDED:
                    ld = discoverers.get(device.id());
                    if (ld == null) {
                        return;
                    }
                    ld.stop();
                    providerService.linksVanished(device.id());
                    break;
                case DEVICE_AVAILABILITY_CHANGED:
                    ld = discoverers.get(device.id());
                    if (ld == null) {
                        return;
                    }
                    if (deviceService.isAvailable(device.id())) {
                        ld.start();
                    } else {
                        providerService.linksVanished(device.id());
                        ld.stop();
                    }
                    break;
                case DEVICE_UPDATED:
                case DEVICE_MASTERSHIP_CHANGED:
                    if (!discoverers.containsKey(device.id())) {
                        discoverers.put(device.id(),
                               new LinkDiscovery(device, packetSevice, masterService,
                                      providerService, useBDDP));
                    }
                    break;
                default:
                    log.debug("Unknown event {}", event);
            }
        }

        @Override
        public void process(PacketContext context) {
            if (context == null) {
                return;
            }
            LinkDiscovery ld = discoverers.get(
                    context.inPacket().receivedFrom().deviceId());
            if (ld == null) {
                return;
            }

            if (ld.handleLLDP(context)) {
                context.block();
            }
        }
    }

}
