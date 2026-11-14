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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * Native, device-local transport for Shruti's capture commands.
 *
 * This plugin knows nothing about the endpoint's command vocabulary: the WebView hands it an
 * arbitrary JSON document and a capture id, and it POSTs that document to whatever HTTPS
 * endpoint the user configured. It is deliberately generic so a new backend route (for example
 * Smriti's {@code /api/command}) needs no native change.
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
                call.resolve(postCommand(configuration, payload, captureId));
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

    private JSObject postCommand(Configuration configuration, String payload, String captureId)
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
        connection.setRequestProperty("Idempotency-Key", captureId);
        connection.setRequestProperty("X-Capture-Id", captureId);
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

    private static String redactBearerToken(String responseBody, String bearerToken) {
        return responseBody.replace(bearerToken, "[REDACTED]");
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
