package dev.eministar.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class UpdateService {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public CompletableFuture<Optional<String>> checkLatestVersionAsync(String apiUrl, String currentVersion) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(4))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FinderX-Updater")
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(body -> parseTagName(body)
                        .filter(latest -> !latest.equalsIgnoreCase(currentVersion)))
                .exceptionally(err -> Optional.empty());
    }

    private Optional<String> parseTagName(String json) {
        int idx = json.indexOf("\"tag_name\"");
        if (idx < 0) {
            return Optional.empty();
        }
        int colon = json.indexOf(':', idx);
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return Optional.empty();
        }
        String tag = json.substring(firstQuote + 1, secondQuote).trim();
        return tag.isEmpty() ? Optional.empty() : Optional.of(tag);
    }
}
