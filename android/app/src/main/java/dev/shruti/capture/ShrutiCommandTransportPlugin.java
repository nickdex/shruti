package dev.shruti.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Native, device-local transport for Shruti's capture commands and Group discovery.
 *
 * This plugin knows nothing about the endpoint's command vocabulary: the WebView hands it an
 * arbitrary JSON document and a capture id, and it POSTs that document to whatever HTTPS
 * endpoint the user configured. It is deliberately generic so a new backend route (for example
 * Smriti's {@code /api/command}) needs no native change.
 *
 * The same credential also reads Group discovery from an absolute path on the configured
 * endpoint's origin. The request path can never move to another host, so JavaScript cannot ask
 * this plugin to send the bearer token elsewhere. A small cache of the last successful Group
 * list lets the User choose a destination offline; it is scoped to a digest of the current
 * credential and is never treated as authorization. Only Group ids and names leave the plugin.
 *
 * The bearer token is only ever read by this plugin: its persisted form is AES-GCM encrypted
 * with an AndroidKeyStore key and bound to the configured endpoint URL as authenticated data.
 * The plugin exposes only the non-secret endpoint URL in status/configuration responses.
 *
 * The retired Pebble plugin's persisted state is deleted on upgrade instead of migrated, so an
 * existing installation has to confirm the new endpoint and token once.
 */
@CapacitorPlugin(name = "ShrutiCommandTransport")
public class ShrutiCommandTransportPlugin extends Plugin {

    private static final String PREFERENCES_NAME = "shruti_command_transport";
    private static final String LEGACY_PREFERENCES_NAME = "shruti_pebble_webhook";
    private static final String GROUP_CACHE_PREFERENCES_NAME = "shruti_group_cache";
    private static final String GROUP_CACHE_SCOPE_KEY = "scope";
    private static final String GROUP_CACHE_BODY_KEY = "body";
    private static final String GROUP_CACHE_FETCHED_AT_KEY = "fetched_at";
    private static final String ENDPOINT_URL_KEY = "endpoint_url";
    private static final String TOKEN_CIPHERTEXT_KEY = "bearer_token_ciphertext";
    private static final String TOKEN_IV_KEY = "bearer_token_iv";
    private static final String KEYSTORE_NAME = "AndroidKeyStore";
    private static final String KEY_ALIAS = "shruti_command_transport_bearer_token_v1";
    private static final String LEGACY_KEY_ALIAS = "shruti_pebble_webhook_bearer_token_v1";
    private static final int AES_GCM_TAG_LENGTH_BITS = 128;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_RESPONSE_BODY_BYTES = 64 * 1024;

    /** One cleanup per process: repeated startups must never touch the transport's own settings. */
    private static volatile boolean legacyPebbleStateCleared = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static final class Configuration {

        final String endpointUrl;
        final String bearerToken;

        Configuration(String endpointUrl, String bearerToken) {
            this.endpointUrl = endpointUrl;
            this.bearerToken = bearerToken;
        }
    }

    private static final class EncryptedToken {

        final String ciphertext;
        final String iv;

        EncryptedToken(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }

    private static final class ResponseBody {

        final String value;
        final boolean truncated;

        ResponseBody(String value, boolean truncated) {
            this.value = value;
            this.truncated = truncated;
        }
    }

    /**
     * The last successful Group payload for one credential scope. It is a display cache only:
     * delivery re-checks membership server-side, so stale ids never grant access.
     */
    private static final class CachedGroups {

        final String body;
        final String fetchedAt;

        CachedGroups(String body, String fetchedAt) {
            this.body = body;
            this.fetchedAt = fetchedAt;
        }
    }

    private static final class ConfigurationException extends Exception {

        final String code;

        ConfigurationException(String message, String code) {
            super(message);
            this.code = code;
        }
    }

    @Override
    public void load() {
        if (legacyPebbleStateCleared) {
            return;
        }
        legacyPebbleStateCleared = true;

        // Same serial worker as configure/deliver, so cleanup always precedes a new configuration.
        try {
            worker.execute(this::clearLegacyPebbleState);
        } catch (RejectedExecutionException ignored) {
            clearLegacyPebbleState();
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        runAsync(call, () -> {
            try {
                call.resolve(statusResponse(readConfiguration()));
            } catch (ConfigurationException ignored) {
                // A missing, invalidated, or corrupt credential is safely represented as unconfigured.
                call.resolve(statusResponse(null));
            } catch (Exception ignored) {
                // Do not expose implementation or credential details through status.
                call.resolve(statusResponse(null));
            }
        });
    }

    @PluginMethod
    public void configure(PluginCall call) {
        String endpointUrl = normalizedEndpointUrl(call.getString("webhookUrl"));
        if (endpointUrl == null) {
            call.reject(
                "Endpoint URL must use HTTPS and have no credentials, query string, or fragment.",
                "INVALID_WEBHOOK_URL"
            );
            return;
        }

        String bearerToken = call.getString("bearerToken");
        if (!isSafeBearerToken(bearerToken)) {
            call.reject(
                "A nonblank bearer token without whitespace is required.",
                "MISSING_BEARER_TOKEN"
            );
            return;
        }

        runAsync(call, () -> {
            try {
                persistConfiguration(endpointUrl, bearerToken);
                clearGroupCache();
                call.resolve(statusResponse(new Configuration(endpointUrl, bearerToken)));
            } catch (Exception ignored) {
                // Never attach the exception: crypto/provider errors can include implementation details.
                call.reject(
                    "Unable to securely save the command transport configuration.",
                    "WEBHOOK_CONFIGURATION_FAILED"
                );
            }
        });
    }

    @PluginMethod
    public void clearConfiguration(PluginCall call) {
        runAsync(call, () -> {
            if (!clearPersistedConfiguration()) {
                call.reject(
                    "Unable to clear the command transport configuration.",
                    "WEBHOOK_CONFIGURATION_CLEAR_FAILED"
                );
                return;
            }

            call.resolve(statusResponse(null));
        });
    }

    @PluginMethod
    public void deliver(PluginCall call) {
        JSObject requestData = call.getData();
        Object payloadValue = requestData == null ? null : requestData.opt("payload");
        String captureId = call.getString("captureId");

        if (!(payloadValue instanceof JSONObject) || isBlank(captureId)) {
            call.reject(
                "payload (a JSON object) and captureId are both required.",
                "INVALID_DELIVERY_REQUEST"
            );
            return;
        }

        String payload = payloadValue.toString();
        runAsync(call, () -> {
            try {
                Configuration configuration = readConfiguration();
                call.resolve(postCommand(configuration, payload));
            } catch (ConfigurationException error) {
                call.reject(error.getMessage(), error.code);
            } catch (IOException ignored) {
                call.reject(
                    "Unable to reach the configured command endpoint.",
                    "DELIVERY_NETWORK_ERROR"
                );
            } catch (Exception ignored) {
                call.reject(
                    "Unable to deliver the capture to the command endpoint.",
                    "DELIVERY_FAILED"
                );
            }
        });
    }

    /**
     * Read Group discovery from an absolute path on the configured endpoint's origin.
     *
     * The bearer token stays here: the resolved object carries only the endpoint's JSON body,
     * cache provenance, and a non-secret error message. A cached body is served only after a
     * failed refresh, always with {@code cached} true. Redirects are refused so the credential
     * cannot follow a 3xx to another location.
     */
    @PluginMethod
    public void fetchGroups(PluginCall call) {
        String path = call.getString("path");
        if (!isSafeGroupPath(path)) {
            call.reject(
                "path must be an absolute path such as /api/groups.",
                "INVALID_GROUPS_REQUEST"
            );
            return;
        }

        runAsync(call, () -> {
            try {
                Configuration configuration = readConfiguration();
                call.resolve(fetchGroupsResponse(configuration, path));
            } catch (ConfigurationException error) {
                call.reject(error.getMessage(), error.code);
            } catch (Exception ignored) {
                call.reject(
                    "Unable to read Groups from the command endpoint.",
                    "GROUPS_UNAVAILABLE"
                );
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        worker.shutdownNow();
        super.handleOnDestroy();
    }

    private void runAsync(PluginCall call, Runnable job) {
        try {
            worker.execute(job);
        } catch (RejectedExecutionException ignored) {
            call.reject(
                "Command transport is unavailable because the plugin is shutting down.",
                "WEBHOOK_PLUGIN_UNAVAILABLE"
            );
        }
    }

    private JSObject statusResponse(Configuration configuration) {
        JSObject result = new JSObject();
        result.put("configured", configuration != null);
        result.put("webhookUrl", configuration == null ? "" : configuration.endpointUrl);
        return result;
    }

    private JSObject fetchGroupsResponse(Configuration configuration, String path) {
        String tokenScope = cacheScope(configuration.bearerToken);
        String scope = isBlank(tokenScope)
            ? null
            : tokenScope + "@" + configuration.endpointUrl;
        CachedGroups cached = cachedGroups(scope);
        JSObject result = new JSObject();
        result.put("status", 0);
        result.put("ok", false);
        result.put("cached", false);
        result.put("authRequired", false);

        String url = groupsUrl(configuration.endpointUrl, path);
        if (url == null) {
            result.put("error", "Group discovery requires an absolute path on the configured endpoint.");
            return attachCachedGroups(result, cached);
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + configuration.bearerToken);

            int status = connection.getResponseCode();
            InputStream responseStream = status >= HttpURLConnection.HTTP_BAD_REQUEST
                ? connection.getErrorStream()
                : connection.getInputStream();
            ResponseBody responseBody = readResponseBody(responseStream);
            String text = redactBearerToken(responseBody.value, configuration.bearerToken);
            result.put("status", status);

            if (status >= 200 && status <= 299) {
                JSONObject body = parseGroupsBody(text);
                if (body == null) {
                    result.put("error", "Smriti returned an unreadable Group list.");
                    return attachCachedGroups(result, cached);
                }

                String fetchedAt = nowIso8601();
                cacheGroups(scope, body.toString(), fetchedAt);
                result.put("ok", true);
                result.put("body", body);
                result.put("fetchedAt", fetchedAt);
                return result;
            }

            result.put("authRequired", status == 401 || status == 403);
            result.put("error", groupErrorMessage(text, status));
            return attachCachedGroups(result, cached);
        } catch (IOException ignored) {
            result.put("error", "Unable to reach the configured command endpoint.");
            return attachCachedGroups(result, cached);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Only a body that actually carries a Group list is accepted, so a proxy error page or a
     * version that predates discovery can never overwrite a good cache entry.
     */
    private static JSONObject parseGroupsBody(String value) {
        if (isBlank(value)) {
            return null;
        }

        try {
            JSONObject body = new JSONObject(value);
            JSONArray groups = body.optJSONArray("groups");
            return groups == null ? null : body;
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static String nowIso8601() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String groupErrorMessage(String responseBody, int status) {
        if (!isBlank(responseBody)) {
            try {
                String error = new JSONObject(responseBody).optString("error", "");
                if (!isBlank(error)) {
                    return error;
                }
            } catch (JSONException ignored) {
                // A non-JSON error page has no message worth showing.
            }
        }
        return "Smriti returned HTTP " + status + " while reading Groups.";
    }

    private static JSObject attachCachedGroups(JSObject result, CachedGroups cached) {
        if (cached == null) {
            return result;
        }

        try {
            result.put("body", new JSONObject(cached.body));
            result.put("cached", true);
            if (!isBlank(cached.fetchedAt)) {
                result.put("fetchedAt", cached.fetchedAt);
            }
        } catch (JSONException ignored) {
            // A corrupt cache entry is ignored rather than surfaced as current Groups.
        }
        return result;
    }

    private CachedGroups cachedGroups(String scope) {
        if (isBlank(scope)) {
            return null;
        }

        SharedPreferences preferences = groupCachePreferences();
        if (!scope.equals(preferences.getString(GROUP_CACHE_SCOPE_KEY, null))) {
            return null;
        }

        String body = preferences.getString(GROUP_CACHE_BODY_KEY, null);
        if (isBlank(body)) {
            return null;
        }
        return new CachedGroups(body, preferences.getString(GROUP_CACHE_FETCHED_AT_KEY, null));
    }

    private void cacheGroups(String scope, String body, String fetchedAt) {
        if (isBlank(scope) || isBlank(body)) {
            return;
        }

        groupCachePreferences()
            .edit()
            .putString(GROUP_CACHE_SCOPE_KEY, scope)
            .putString(GROUP_CACHE_BODY_KEY, body)
            .putString(GROUP_CACHE_FETCHED_AT_KEY, fetchedAt)
            .commit();
    }

    private void clearGroupCache() {
        groupCachePreferences().edit().clear().apply();
    }

    private SharedPreferences groupCachePreferences() {
        return getContext().getSharedPreferences(GROUP_CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private Configuration readConfiguration() throws ConfigurationException {
        SharedPreferences preferences = preferences();
        String storedUrl = preferences.getString(ENDPOINT_URL_KEY, null);
        String storedCiphertext = preferences.getString(TOKEN_CIPHERTEXT_KEY, null);
        String storedIv = preferences.getString(TOKEN_IV_KEY, null);

        if (storedUrl == null && storedCiphertext == null && storedIv == null) {
            throw new ConfigurationException(
                "Command transport configuration is missing.",
                "WEBHOOK_NOT_CONFIGURED"
            );
        }

        String endpointUrl = normalizedEndpointUrl(storedUrl);
        if (endpointUrl == null || isBlank(storedCiphertext) || isBlank(storedIv)) {
            clearPersistedConfiguration();
            throw new ConfigurationException(
                "Stored command transport configuration is invalid. Configure it again.",
                "WEBHOOK_CONFIGURATION_INVALID"
            );
        }

        try {
            String bearerToken = decryptToken(endpointUrl, storedCiphertext, storedIv);
            if (!isSafeBearerToken(bearerToken)) {
                clearPersistedConfiguration();
                throw new ConfigurationException(
                    "Stored command transport credentials are invalid. Configure them again.",
                    "WEBHOOK_CONFIGURATION_INVALID"
                );
            }
            return new Configuration(endpointUrl, bearerToken);
        } catch (ConfigurationException error) {
            throw error;
        } catch (Exception ignored) {
            clearPersistedConfiguration();
            throw new ConfigurationException(
                "Stored command transport credentials are unavailable. Configure them again.",
                "WEBHOOK_CONFIGURATION_UNAVAILABLE"
            );
        }
    }

    private void persistConfiguration(String endpointUrl, String bearerToken) throws GeneralSecurityException {
        EncryptedToken encryptedToken = encryptToken(endpointUrl, bearerToken);
        boolean saved = preferences()
            .edit()
            .putString(ENDPOINT_URL_KEY, endpointUrl)
            .putString(TOKEN_CIPHERTEXT_KEY, encryptedToken.ciphertext)
            .putString(TOKEN_IV_KEY, encryptedToken.iv)
            .commit();

        if (!saved) {
            throw new GeneralSecurityException("Unable to persist encrypted command transport credentials.");
        }
    }

    private boolean clearPersistedConfiguration() {
        boolean cleared = preferences()
            .edit()
            .remove(ENDPOINT_URL_KEY)
            .remove(TOKEN_CIPHERTEXT_KEY)
            .remove(TOKEN_IV_KEY)
            .commit();

        clearGroupCache();
        deleteSecretKey(KEY_ALIAS);
        return cleared;
    }

    /**
     * Deletes the retired Pebble plugin's preferences file and keystore key. Only those legacy
     * names are touched, so an upgraded device must configure the new endpoint once and later
     * startups never disturb that configuration.
     */
    private void clearLegacyPebbleState() {
        try {
            getContext().deleteSharedPreferences(LEGACY_PREFERENCES_NAME);
        } catch (Exception ignored) {
            // Without the preferences file the legacy token ciphertext is unreachable anyway.
        }

        deleteSecretKey(LEGACY_KEY_ALIAS);
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private EncryptedToken encryptToken(String endpointUrl, String bearerToken) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        cipher.updateAAD(endpointUrl.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(bearerToken.getBytes(StandardCharsets.UTF_8));
        return new EncryptedToken(
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        );
    }

    private String decryptToken(String endpointUrl, String encodedCiphertext, String encodedIv)
        throws GeneralSecurityException {
        byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
        byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
        if (iv.length == 0) {
            throw new GeneralSecurityException("Missing AES-GCM initialization vector.");
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            new GCMParameterSpec(AES_GCM_TAG_LENGTH_BITS, iv)
        );
        cipher.updateAAD(endpointUrl.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private SecretKey secretKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_NAME);
            keyStore.load(null);

            if (keyStore.containsAlias(KEY_ALIAS)) {
                Key key = keyStore.getKey(KEY_ALIAS, null);
                if (key instanceof SecretKey) {
                    return (SecretKey) key;
                }
                throw new GeneralSecurityException("Stored transport key is not an AES key.");
            }

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_NAME
            );
            keyGenerator.init(
                new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            );
            return keyGenerator.generateKey();
        } catch (GeneralSecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneralSecurityException("Unable to access AndroidKeyStore.", error);
        }
    }

    private void deleteSecretKey(String alias) {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_NAME);
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (Exception ignored) {
            // Preferences have already been cleared. A standalone key cannot decrypt any token.
        }
    }

    private JSObject postCommand(Configuration configuration, String payload)
        throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(configuration.endpointUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + configuration.bearerToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= HttpURLConnection.HTTP_BAD_REQUEST
                ? connection.getErrorStream()
                : connection.getInputStream();
            ResponseBody responseBody = readResponseBody(responseStream);
            String retryAfter = connection.getHeaderField("Retry-After");

            JSObject result = new JSObject();
            result.put("status", status);
            result.put("responseBody", redactBearerToken(responseBody.value, configuration.bearerToken));
            result.put("responseBodyTruncated", responseBody.truncated);
            if (retryAfter != null) {
                result.put("retryAfter", retryAfter);
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static ResponseBody readResponseBody(InputStream stream) throws IOException {
        if (stream == null) {
            return new ResponseBody("", false);
        }

        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4 * 1024];
            boolean truncated = false;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = MAX_RESPONSE_BODY_BYTES - output.size();
                int bytesToWrite = Math.min(read, Math.max(remaining, 0));
                if (bytesToWrite > 0) {
                    output.write(buffer, 0, bytesToWrite);
                }
                if (bytesToWrite < read) {
                    truncated = true;
                    break;
                }
            }
            return new ResponseBody(new String(output.toByteArray(), StandardCharsets.UTF_8), truncated);
        }
    }

    static String redactBearerToken(String responseBody, String bearerToken) {
        return responseBody.replace(bearerToken, "[REDACTED]");
    }

    /**
     * A discovery path is a plain absolute path: no scheme, host, query, fragment, or traversal
     * segment. Combined with {@link #groupsUrl}, it can only address the configured origin.
     */
    static boolean isSafeGroupPath(String path) {
        if (isBlank(path) || !path.startsWith("/") || path.startsWith("//")) {
            return false;
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The absolute discovery URL for {@code path} on the configured endpoint's scheme, host and
     * port. Returns null when the path is unsafe, so the caller can never widen the credential's
     * destination.
     */
    static String groupsUrl(String endpointUrl, String path) {
        if (!isSafeGroupPath(path)) {
            return null;
        }

        try {
            URI endpoint = new URI(endpointUrl).normalize();
            if (
                endpoint.getScheme() == null ||
                endpoint.getHost() == null ||
                !"https".equalsIgnoreCase(endpoint.getScheme())
            ) {
                return null;
            }

            URI target = new URI(
                endpoint.getScheme(),
                null,
                endpoint.getHost(),
                endpoint.getPort(),
                path,
                null,
                null
            ).normalize();

            if (!sameOrigin(endpoint, target)) {
                return null;
            }
            return target.toASCIIString();
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    /**
     * A stable, non-secret scope for the Group cache: the SHA-256 digest of the bearer token.
     * Callers pair it with the configured endpoint, so neither another credential nor another
     * server can read this cache entry.
     */
    static String cacheScope(String bearerToken) {
        if (isBlank(bearerToken)) {
            return null;
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bearerToken.getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray();
            char[] hex = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int value = digest[index] & 0xFF;
                hex[index * 2] = digits[value >>> 4];
                hex[index * 2 + 1] = digits[value & 0x0F];
            }
            return new String(hex);
        } catch (GeneralSecurityException ignored) {
            return null;
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme()) &&
            left.getHost().equalsIgnoreCase(right.getHost()) &&
            effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Any HTTPS endpoint is accepted; only transport-level requirements are enforced. No path is
     * hard-coded, since the caller (not the plugin) decides which backend route receives commands.
     */
    static String normalizedEndpointUrl(String rawUrl) {
        if (isBlank(rawUrl)) {
            return null;
        }

        try {
            URI uri = new URI(rawUrl.trim()).normalize();
            String path = uri.getPath();
            if (
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.isOpaque() ||
                uri.getHost() == null ||
                uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null ||
                path == null
            ) {
                return null;
            }
            return uri.toASCIIString();
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static boolean isSafeBearerToken(String value) {
        if (isBlank(value)) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character >= 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
