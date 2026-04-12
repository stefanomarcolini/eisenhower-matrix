package io.taskmanager.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin HTTP client wrapper around the Mailpit REST API.
 *
 * Used by E2E tests to retrieve emails (e.g. password-reset links) that the
 * Core API sends via SMTP to the Mailpit capture server running in the
 * docker-compose.override.yml stack.
 *
 * Mailpit REST API reference: https://mailpit.axllent.org/docs/api-v1/
 */
public class MailpitClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public MailpitClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Polls {@code GET /api/v1/messages} until an email addressed to
     * {@code recipient} arrives, then returns its Mailpit message ID.
     *
     * @param recipient     email address (case-insensitive match on the To field)
     * @param timeoutSeconds maximum time to wait before failing the test
     */
    public String pollForEmail(String recipient, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/messages"))
                    .GET()
                    .build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            JsonNode root     = mapper.readTree(body);
            JsonNode messages = root.path("messages");
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    JsonNode toArray = msg.path("To");
                    if (toArray.isArray()) {
                        for (JsonNode to : toArray) {
                            if (recipient.equalsIgnoreCase(to.path("Address").asText())) {
                                return msg.path("ID").asText();
                            }
                        }
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        throw new AssertionError("No email for '" + recipient + "' arrived within " + timeoutSeconds + "s");
    }

    /**
     * Returns the plain-text body of the message identified by {@code messageId}.
     */
    public String getMessageBody(String messageId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/message/" + messageId))
                .GET()
                .build();
        String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        JsonNode root = mapper.readTree(body);
        // Mailpit returns plain-text body under the "Text" key
        return root.path("Text").asText();
    }

    /**
     * Extracts the first URL from {@code text} that matches {@code urlPattern}.
     *
     * @param text       email body text
     * @param urlPattern regex pattern matching the full reset URL
     */
    public String extractLink(String text, String urlPattern) {
        Matcher m = Pattern.compile(urlPattern).matcher(text);
        if (m.find()) {
            return m.group();
        }
        throw new AssertionError("No link matching '" + urlPattern + "' found in email body:\n" + text);
    }

    /**
     * Deletes all messages in the Mailpit inbox.
     * Called in {@code @BeforeEach} to prevent test cross-contamination.
     */
    public void deleteAll() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/messages"))
                .DELETE()
                .build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }
}
