/*
 * TermDeb ADB bridge — device-listing integration for Wireless Debugging.
 *
 * Lives in com.termux.app.adb so the package-private DeviceServices surface
 * (devicesList) is visible. Verifies that live wireless transports appear in
 * `adb devices` output exactly like legacy network transports, and that the
 * registry stays consistent across connect/disconnect.
 */
package com.termux.app.adb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import com.termux.app.adb.remote.AdbTransportManager;
import com.termux.app.adb.remote.RemoteDevice;
import com.termux.app.adb.wireless.FakeSecureAdbd;
import com.termux.app.adb.wireless.WirelessDeviceStore;
import com.termux.app.adb.wireless.WirelessTransportManager;

/** `adb devices` integration for wireless transports. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DeviceListWirelessTest {

    private FakeSecureAdbd mAdbd;

    @Before
    public void setUp() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        WirelessTransportManager.init(context);
        AdbTransportManager.init(context);
        mAdbd = new FakeSecureAdbd();
    }

    @After
    public void tearDown() {
        WirelessTransportManager.clearStateForTest();
        if (mAdbd != null) mAdbd.stop();
    }

    private RemoteDevice registerOnlineDevice() {
        String msg = WirelessTransportManager.connect("127.0.0.1", mAdbd.port);
        assertNotNull(msg);
        return WirelessTransportManager.bySpec("127.0.0.1:" + mAdbd.port);
    }

    @Test
    public void onlineWirelessTransportAppearsInDevicesList() throws Exception {
        RemoteDevice dev = registerOnlineDevice();
        assertNotNull(dev);
        assertTrue(dev.isOnline());

        String list = DeviceServices.devicesList(false);
        assertTrue("wireless transport must be listed, got: " + list,
            list.contains("127.0.0.1:" + mAdbd.port + "\tdevice"));

        String longList = DeviceServices.devicesList(true);
        assertTrue("long form must include the product banner",
            longList.contains("product:termdeb_bridge"));
    }

    @Test
    public void disconnectedTransportVanishesFromDevicesList() throws Exception {
        RemoteDevice dev = registerOnlineDevice();
        assertTrue(dev.isOnline());
        WirelessTransportManager.disconnect("127.0.0.1", mAdbd.port);
        String list = DeviceServices.devicesList(false);
        assertTrue(list, !list.contains("127.0.0.1:" + mAdbd.port));
    }

    @Test
    public void storeRoundTripKeepsEndpoint() {
        WirelessDeviceStore store = WirelessDeviceStore.get(
            org.robolectric.RuntimeEnvironment.getApplication());
        store.put("guid-x", "192.168.1.102", 42567);
        WirelessDeviceStore.PairedDevice d = store.get("guid-x");
        assertNotNull(d);
        assertEquals("192.168.1.102", d.host);
        assertEquals(42567, d.lastAdbPort);
        assertEquals("192.168.1.102:42567", d.endpoint());
        store.remove("guid-x");
        assertEquals(0, store.all().size());
    }
}
