package dev.shruti.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The transport is generic, so URL validation must accept any HTTPS endpoint the caller chooses
 * while still refusing anything that could leak credentials or silently retarget delivery.
 *
 * Group discovery reuses the configured connection's credentials, so its path and cache scope are
 * the security boundary: discovery must stay on the configured host, never accept an absolute URL
 * from JavaScript, and never persist or return the bearer token itself.
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

    @Test
    public void acceptsOnlySameOriginAbsoluteGroupPaths() {
        assertTrue(ShrutiCommandTransportPlugin.isSafeGroupPath("/api/groups"));
        assertTrue(ShrutiCommandTransportPlugin.isSafeGroupPath("/api/groups/"));
        assertTrue(ShrutiCommandTransportPlugin.isSafeGroupPath("/api/v2/groups"));

        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath(null));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath(""));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("   "));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("api/groups"));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("//evil.example/api/groups"));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("https://evil.example/api/groups"));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("/api/groups?target=evil"));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("/api/groups#fragment"));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("/api/../secret"));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("/api/./groups"));
        assertFalse(ShrutiCommandTransportPlugin.isSafeGroupPath("/api\\groups"));
    }

    @Test
    public void buildsTheGroupsUrlOnTheConfiguredOrigin() {
        assertEquals(
            "https://smriti.example.com/api/groups",
            ShrutiCommandTransportPlugin.groupsUrl(
                "https://smriti.example.com/api/command",
                "/api/groups"
            )
        );
        assertEquals(
            "https://smriti.example.com:8443/api/groups",
            ShrutiCommandTransportPlugin.groupsUrl(
                "https://smriti.example.com:8443/custom/command",
                "/api/groups"
            )
        );
        assertEquals(
            "https://smriti.example.com/api/v2/groups",
            ShrutiCommandTransportPlugin.groupsUrl(
                "https://smriti.example.com/api/command",
                "/api/v2/groups"
            )
        );
    }

    @Test
    public void refusesGroupUrlsThatWouldLeaveTheConfiguredOrigin() {
        assertNull(ShrutiCommandTransportPlugin.groupsUrl(
            "https://smriti.example.com/api/command",
            "https://evil.example/api/groups"
        ));
        assertNull(ShrutiCommandTransportPlugin.groupsUrl(
            "https://smriti.example.com/api/command",
            "//evil.example/api/groups"
        ));
        assertNull(ShrutiCommandTransportPlugin.groupsUrl(
            "https://smriti.example.com/api/command",
            "/api/groups?target=evil"
        ));
        assertNull(ShrutiCommandTransportPlugin.groupsUrl(
            "https://smriti.example.com/api/command",
            "/api/groups#fragment"
        ));
        assertNull(ShrutiCommandTransportPlugin.groupsUrl(
            "http://smriti.example.com/api/command",
            "/api/groups"
        ));
    }

    @Test
    public void cacheScopeIsAStableDigestThatNeverContainsTheToken() {
        String token = "shruti-secret-token";
        String scope = ShrutiCommandTransportPlugin.cacheScope(token);

        assertEquals(64, scope.length());
        assertTrue(scope.matches("[0-9a-f]+"));
        assertFalse(scope.contains(token));
        assertEquals(scope, ShrutiCommandTransportPlugin.cacheScope(token));
        assertNotEquals(scope, ShrutiCommandTransportPlugin.cacheScope("another-token"));
    }

    @Test
    public void responseBodiesNeverEchoTheBearerToken() {
        assertEquals(
            "{\"error\":\"[REDACTED] is not valid\"}",
            ShrutiCommandTransportPlugin.redactBearerToken(
                "{\"error\":\"shruti-secret-token is not valid\"}",
                "shruti-secret-token"
            )
        );
        assertEquals(
            "{\"ok\":true}",
            ShrutiCommandTransportPlugin.redactBearerToken("{\"ok\":true}", "shruti-secret-token")
        );
    }
}
