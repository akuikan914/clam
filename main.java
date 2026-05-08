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

    private static void idleForever() {
        final Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Args
    // ---------------------------------------------------------------------
    static final class Args {
        final boolean showHelp;
        final boolean headless;
        final Integer httpPort;
        final Path configPath;
        final Path stateDir;
        final Path logPath;
        final List<String> cmd;

        private Args(boolean showHelp, boolean headless, Integer httpPort, Path configPath, Path stateDir, Path logPath, List<String> cmd) {
            this.showHelp = showHelp;
            this.headless = headless;
            this.httpPort = httpPort;
            this.configPath = configPath;
            this.stateDir = stateDir;
            this.logPath = logPath;
            this.cmd = cmd;
        }

        static Args parse(String[] args) {
            boolean help = false;
            boolean headless = false;
            Integer port = null;
            Path configPath = null;
            Path stateDir = null;
            Path logPath = null;
            List<String> cmd = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("-h") || a.equals("--help")) {
                    help = true;
                } else if (a.equals("--headless")) {
                    headless = true;
                } else if (a.equals("--port") && i + 1 < args.length) {
                    port = safeParseInt(args[++i]);
                } else if (a.equals("--config") && i + 1 < args.length) {
                    configPath = Paths.get(args[++i]);
                } else if (a.equals("--state") && i + 1 < args.length) {
                    stateDir = Paths.get(args[++i]);
                } else if (a.equals("--log") && i + 1 < args.length) {
                    logPath = Paths.get(args[++i]);
                } else if (a.equals("--")) {
                    for (int j = i + 1; j < args.length; j++) cmd.add(args[j]);
                    break;
                } else {
                    // treat as command if it looks like one (first token)
                    cmd.add(a);
                }
            }

            return new Args(help, headless, port, configPath, stateDir, logPath, cmd.isEmpty() ? null : List.copyOf(cmd));
        }

        static String usage() {
            return ""
                + "clam - AI claw autofix and rebuild watchdog\n"
                + "\n"
                + "Usage:\n"
                + "  java Clam [--headless] [--port N] [--config PATH] [--state PATH] [--log PATH] [-- <command...>]\n"
                + "\n"
                + "Examples:\n"
                + "  java Clam --headless --port 48123 -- cmd /c node clawbot.js\n"
                + "  java Clam -- -- python clawbot.py\n"
                + "\n"
                + "UI mode launches by default (omit --headless).\n";
        }

        private static Integer safeParseInt(String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
        }
    }

    // ---------------------------------------------------------------------
    // Runtime environment detection
    // ---------------------------------------------------------------------
    static final class RuntimeEnv {
        final String osName;
        final boolean windows;
        final boolean mac;
        final boolean linux;
        final String javaVersion;
        final String pid;
        final String user;

        private RuntimeEnv(String osName, boolean windows, boolean mac, boolean linux, String javaVersion, String pid, String user) {
            this.osName = osName;
            this.windows = windows;
            this.mac = mac;
            this.linux = linux;
            this.javaVersion = javaVersion;
            this.pid = pid;
            this.user = user;
        }

        static RuntimeEnv detect() {
            String os = System.getProperty("os.name", "unknown");
            String osLower = os.toLowerCase(Locale.ROOT);
            boolean win = osLower.contains("win");
            boolean mac = osLower.contains("mac");
            boolean lin = osLower.contains("nux") || osLower.contains("linux");
            String jv = System.getProperty("java.version", "?");
            String user = System.getProperty("user.name", "?");
            String pid = "?";
            try {
                // Works on HotSpot: "pid@host"
                String name = ManagementFactory.getRuntimeMXBean().getName();
                int at = name.indexOf('@');
                pid = at > 0 ? name.substring(0, at) : name;
            } catch (Exception ignored) {}
            return new RuntimeEnv(os, win, mac, lin, jv, pid, user);
        }
    }

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------
    static final class Config {
        final int httpPort;
        final int uiRefreshMs;
        final int heartbeatMs;
        final int minUptimeMs;
        final int backoffMinMs;
        final int backoffMaxMs;
        final int maxRestartsPerHour;
        final boolean autoStart;

        final List<String> command;
        final Path workDir;
        final Map<String, String> env;

        final Pattern crashSignature;
        final int crashScanLines;
        final int crashScanBytes;

        final int rebuildWindowSeconds;
        final int rebuildMaxAttempts;
        final int rebuildAttemptSpacingMs;
        final boolean rebuildAggressiveGc;

        final int journalFlushMs;
        final int snapshotRingSize;

        private Config(
            int httpPort,
            int uiRefreshMs,
            int heartbeatMs,
            int minUptimeMs,
            int backoffMinMs,
            int backoffMaxMs,
            int maxRestartsPerHour,
