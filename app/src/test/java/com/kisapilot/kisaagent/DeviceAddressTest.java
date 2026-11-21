package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
public class DeviceAddressTest {
    @Test public void normalizeDeviceAddress() {
        assertEquals("192.168.0.194", DeviceAddress.normalize(" 192.168.000.194 "));
    }
    @Test public void rejectInvalidOrNonDeviceAddresses() {
        for (String value : new String[]{"", "192.168.0", "256.1.2.3", "http://192.168.0.1", "192.168.0.1:5005", "1.2.3.-1", "0.0.0.0", "255.255.255.255", "224.0.0.1"}) {
            try { DeviceAddress.normalize(value); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
}
