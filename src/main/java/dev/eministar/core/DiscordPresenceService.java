package dev.eministar.core;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DiscordPresenceService {
    private static final String DEFAULT_APP_ID = "1471517548544786534";
    private static final int MAX_LEN = 120;

    private final DiscordRPC rpc = DiscordRPC.INSTANCE;
    private final long startedAt = Instant.now().getEpochSecond();
    private ScheduledExecutorService callbacksExecutor;
    private volatile boolean running;
    private volatile boolean enabled = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            stop();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void start() {
        if (!enabled || running || !isWindows()) {
            return;
        }
        try {
            String appId = System.getenv().getOrDefault("FINDERX_DISCORD_APP_ID", DEFAULT_APP_ID);
            DiscordEventHandlers handlers = new DiscordEventHandlers();
            rpc.Discord_Initialize(appId, handlers, true, "");
            running = true;
            updateIdle("FinderX ready");

            callbacksExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "discord-rpc-callbacks");
                t.setDaemon(true);
                return t;
            });
            callbacksExecutor.scheduleAtFixedRate(() -> {
                try {
                    rpc.Discord_RunCallbacks();
                } catch (Throwable ignored) {
                }
            }, 0, 2, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            running = false;
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        try {
            rpc.Discord_ClearPresence();
            rpc.Discord_Shutdown();
        } catch (Throwable ignored) {
        }
        if (callbacksExecutor != null) {
            callbacksExecutor.shutdownNow();
            callbacksExecutor = null;
        }
        running = false;
    }

    public void updateIdle(String state) {
        if (!running) {
            return;
        }
        try {
            DiscordRichPresence presence = new DiscordRichPresence();
            presence.startTimestamp = startedAt;
            presence.details = cap("FinderX");
            presence.state = cap(state);
            presence.largeImageKey = "app-logo";
            presence.largeImageText = "FinderX";
            rpc.Discord_UpdatePresence(presence);
        } catch (Throwable ignored) {
        }
    }

    public void updateIndexing(String root, long files) {
        if (!running) {
            return;
        }
        try {
            DiscordRichPresence presence = new DiscordRichPresence();
            presence.startTimestamp = startedAt;
            presence.details = cap("Indexing " + root);
            presence.state = cap(files + " files indexed");
            presence.largeImageKey = "app-logo";
            presence.largeImageText = "FinderX";
            rpc.Discord_UpdatePresence(presence);
        } catch (Throwable ignored) {
        }
    }

    public void updateSearch(String query, int resultCount, String root) {
        if (!running) {
            return;
        }
        try {
            DiscordRichPresence presence = new DiscordRichPresence();
            presence.startTimestamp = startedAt;
            presence.details = cap("Searching on " + root);
            presence.state = cap("\"" + query + "\" - " + resultCount + " results");
            presence.largeImageKey = "app-logo";
            presence.largeImageText = "FinderX";
            rpc.Discord_UpdatePresence(presence);
        } catch (Throwable ignored) {
        }
    }

    private static String cap(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_LEN ? value : value.substring(0, MAX_LEN);
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
