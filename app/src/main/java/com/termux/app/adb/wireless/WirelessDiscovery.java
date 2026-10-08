/*
 * TermDeb ADB bridge — mDNS discovery for Wireless Debugging.
 *
 * Android 11+ adbd advertises (daemon/mdns.cpp, adb_mdns.h):
 *   - _adb-tls-pairing._tcp  while a pairing dialog is open
 *   - _adb-tls-connect._tcp  while Wireless Debugging is enabled
 * with the device name in the instance name and the port in the SRV/TXT.
 *
 * This is a CONVENIENCE layer for the Settings UI only: the pairing and
 * connect flows never require discovery — an explicit host:port works
 * without it (the user reads the IP/port off the device screen). Discovery
 * runs for a bounded window, then stops; results are reported once per
 * service instance.
 */
package com.termux.app.adb.wireless;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import com.termux.app.adb.TermdebAdbBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded-window NsdManager discovery of adb TLS services. */
public final class WirelessDiscovery {

    public static final String SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp.";
    public static final String SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp.";
    /** How long one discover() sweep listens. */
    private static final int SWEEP_SECONDS = 6;

    /** One discovered service instance. */
    public static final class DiscoveredService {
        public final String serviceType;
        public final String instanceName;
        public final String host;
        public final int port;

        DiscoveredService(String serviceType, String instanceName, String host, int port) {
            this.serviceType = serviceType;
            this.instanceName = instanceName;
            this.host = host;
            this.port = port;
        }

        public boolean isPairing() {
            return SERVICE_TYPE_PAIRING.equals(serviceType);
        }
    }

    private WirelessDiscovery() {
    }

    /**
     * Resolve the Wireless Debugging ADB port of {@code host} via a bounded
     * mDNS sweep of _adb-tls-connect._tcp (adbd advertises it whenever
     * Wireless debugging is enabled — daemon/mdns.cpp). The pairing service
     * is deliberately not consulted: it only lives while the device's
     * pairing dialog is open and carries the pairing port, never the ADB
     * port. Returns the first connect port advertised by that host, or null
     * when nothing was found (the caller reports that honestly).
     */
    public static Integer findConnectPort(Context context, String host) {
        for (DiscoveredService s : discover(context)) {
            if (!s.isPairing() && host.equals(s.host)) return s.port;
        }
        return null;
    }

    /**
     * Sweep for Wireless Debugging services. Blocking up to
     * {@code SWEEP_SECONDS} + resolution time; call off the UI thread.
     * Returns everything found in the window (possibly empty — e.g. when
     * Wireless Debugging is off, which the UI reports honestly).
     */
    public static List<DiscoveredService> discover(Context context) {
        final List<DiscoveredService> found =
            java.util.Collections.synchronizedList(new ArrayList<>());
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            TermdebAdbBridge.logWarn("WirelessDiscovery", "NsdManager unavailable");
            return found;
        }
        CountDownLatch done = new CountDownLatch(2);
        sweep(nsd, SERVICE_TYPE_PAIRING, found, done);
        sweep(nsd, SERVICE_TYPE_CONNECT, found, done);
        try {
            done.await(SWEEP_SECONDS + 4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found;
    }

    private static void sweep(NsdManager nsd, String type,
                              List<DiscoveredService> found, CountDownLatch done) {
        final CountDownLatch swept = done;
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    TermdebAdbBridge.logWarn("WirelessDiscovery",
                        "discovery start failed for " + serviceType + ": " + errorCode);
                    swept.countDown();
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            // Unresolvable instances are skipped silently; the
                            // user can always enter host:port manually.
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            String address = null;
                            if (info.getHost() != null) {
                                address = info.getHost().getHostAddress();
                            }
                            found.add(new DiscoveredService(type,
                                info.getServiceName(), address, info.getPort()));
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    swept.countDown();
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    swept.countDown();
                }
            });
        } catch (Exception e) {
            TermdebAdbBridge.logWarn("WirelessDiscovery",
                "discovery threw for " + type + ": " + e.getClass().getSimpleName());
            swept.countDown();
        }
    }
}
