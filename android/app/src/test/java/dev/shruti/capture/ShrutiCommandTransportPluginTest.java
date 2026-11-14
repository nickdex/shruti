package dev.shruti.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The transport is generic, so URL validation must accept any HTTPS endpoint the caller chooses
 * while still refusing anything that could leak credentials or silently retarget delivery.
 */
public class ShrutiCommandTransportPluginTest {

    @Test
    public void acceptsAnHttpsEndpointWithoutANetworkDependentPath() {
        assertEquals(
            "https://smriti.example.com/api/command",
            ShrutiCommandTransportPlugin.normalizedEndpointUrl("https://smriti.example.com/api/command")
        );
        assertEquals(
            "https://smriti.example.com/",
            ShrutiCommandTransportPlugin.normalizedEndpointUrl("https://smriti.example.com/")
        );
        assertEquals(
            "https://smriti.example.com:8443/custom/path",
            ShrutiCommandTransportPlugin.normalizedEndpointUrl("https://smriti.example.com:8443/custom/path")
        );
    }

    @Test
    public void rejectsNonHttpsEndpoints() {
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("http://smriti.example.com/api/command"));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("ftp://smriti.example.com/api/command"));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("smriti.example.com/api/command"));
    }

    @Test
    public void rejectsEndpointsThatWouldLeakOrRetargetTheRequest() {
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("https://user:secret@smriti.example.com/api/command"));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("https://smriti.example.com/api/command?target=other"));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("https://smriti.example.com/api/command#fragment"));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("https:///api/command"));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("https:opaque"));
    }

    @Test
    public void rejectsBlankEndpoints() {
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl(null));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl("   "));
        assertNull(ShrutiCommandTransportPlugin.normalizedEndpointUrl(""));
    }
}
