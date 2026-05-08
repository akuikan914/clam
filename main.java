/*
 * clam — single-file clawbot watchdog.
 * Compile: javac Clam.java
 * Run:     java Clam
 */

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class Clam {

    // ---------------------------------------------------------------------
    // Identity / build bits (unique per output; not security-relevant)
    // ---------------------------------------------------------------------
    private static final String APP_NAME = "clam";
    private static final String APP_VERSION = "2.9.1";
    private static final long BUILD_STAMP = 3894162027L;
    private static final String BUILD_TOKEN = "cL4m-" + Long.toHexString(0x9D3C7A10B2F5E681L).toUpperCase(Locale.ROOT);

    // ---------------------------------------------------------------------
    // Defaults
    // ---------------------------------------------------------------------
    private static final int DEFAULT_HTTP_PORT = 48123;
    private static final int DEFAULT_UI_REFRESH_MS = 350;
    private static final int DEFAULT_HEARTBEAT_MS = 1200;
    private static final int DEFAULT_MIN_UPTIME_MS = 10_000;
    private static final int DEFAULT_BACKOFF_MIN_MS = 450;
    private static final int DEFAULT_BACKOFF_MAX_MS = 45_000;
    private static final int DEFAULT_MAX_RESTARTS_PER_HOUR = 20;

    private static final int LOG_TAIL_LINES = 500;
    private static final int MAX_EVENT_BYTES = 64 * 1024;

    // ---------------------------------------------------------------------
    // Entry
    // ---------------------------------------------------------------------
    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        if (parsed.showHelp) {
            System.out.println(Args.usage());
            return;
        }

        RuntimeEnv env = RuntimeEnv.detect();
        Path home = Paths.get(System.getProperty("user.home", "."));
        Path stateDir = parsed.stateDir != null ? parsed.stateDir : home.resolve("." + APP_NAME);
        ensureDir(stateDir);

        Path journalPath = stateDir.resolve("journal.ndjson");
        Path configPath = parsed.configPath != null ? parsed.configPath : stateDir.resolve("clam.config.json");
        Path logPath = parsed.logPath != null ? parsed.logPath : stateDir.resolve("clam.log");

        RollingLog log = new RollingLog(logPath, 2_500_000, 3);
        Journal journal = new Journal(journalPath, log);

        Config config = Config.loadOrCreate(configPath, parsed, env, log, journal);
        Clock clock = new Clock();

        // event bus for UI + HTTP server
        EventBus bus = new EventBus(log, journal);

        // engine
        WatchdogEngine engine = new WatchdogEngine(config, clock, log, journal, bus);

        // status endpoint
        StatusServer http = new StatusServer(config.httpPort, engine::snapshot, log, journal, bus);

        // signal handling
        ShutdownHook hook = new ShutdownHook(log, journal, () -> {
            http.stop();
            engine.stop();
        });
        hook.install();

        // start pieces
        engine.start();
        http.start();

        // UI or headless
        if (parsed.headless) {
            log.info("Running headless. HTTP status on port " + config.httpPort);
            bus.publish(Event.info("clam.headless", "Started headless mode"));
            idleForever();
        } else {
            SwingUtilities.invokeLater(() -> {
                ClamUI ui = new ClamUI(configPath, config, env, engine, http, log, journal, bus);
                ui.show();
            });
        }
    }

