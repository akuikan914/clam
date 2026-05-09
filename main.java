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
            boolean autoStart,
            List<String> command,
            Path workDir,
            Map<String, String> env,
            Pattern crashSignature,
            int crashScanLines,
            int crashScanBytes,
            int rebuildWindowSeconds,
            int rebuildMaxAttempts,
            int rebuildAttemptSpacingMs,
            boolean rebuildAggressiveGc,
            int journalFlushMs,
            int snapshotRingSize
        ) {
            this.httpPort = httpPort;
            this.uiRefreshMs = uiRefreshMs;
            this.heartbeatMs = heartbeatMs;
            this.minUptimeMs = minUptimeMs;
            this.backoffMinMs = backoffMinMs;
            this.backoffMaxMs = backoffMaxMs;
            this.maxRestartsPerHour = maxRestartsPerHour;
            this.autoStart = autoStart;
            this.command = command;
            this.workDir = workDir;
            this.env = env;
            this.crashSignature = crashSignature;
            this.crashScanLines = crashScanLines;
            this.crashScanBytes = crashScanBytes;
            this.rebuildWindowSeconds = rebuildWindowSeconds;
            this.rebuildMaxAttempts = rebuildMaxAttempts;
            this.rebuildAttemptSpacingMs = rebuildAttemptSpacingMs;
            this.rebuildAggressiveGc = rebuildAggressiveGc;
            this.journalFlushMs = journalFlushMs;
            this.snapshotRingSize = snapshotRingSize;
        }

        static Config loadOrCreate(Path path, Args args, RuntimeEnv env, RollingLog log, Journal journal) {
            if (Files.exists(path)) {
                try {
                    String raw = Files.readString(path, StandardCharsets.UTF_8);
                    Map<String, Object> m = Json2.parseObject(raw);
                    Config cfg = fromMap(m, args, env);
                    log.info("Loaded config: " + path.toAbsolutePath());
                    journal.write(Event.info("clam.config.load", "Loaded config").with("path", path.toString()));
                    return cfg;
                } catch (Exception e) {
                    log.warn("Config load failed. Using defaults. Error: " + e.getMessage());
                    journal.write(Event.warn("clam.config.load_failed", "Config load failed; using defaults").with("error", e.toString()));
                }
            }

            Config created = defaults(args, env);
            try {
                Files.writeString(path, Json.pretty(created.toMap()), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("Wrote default config: " + path.toAbsolutePath());
                journal.write(Event.info("clam.config.created", "Created default config").with("path", path.toString()));
            } catch (Exception e) {
                log.warn("Could not write default config: " + e.getMessage());
                journal.write(Event.warn("clam.config.write_failed", "Could not write default config").with("error", e.toString()));
            }
            return created;
        }

        static Config defaults(Args args, RuntimeEnv env) {
            int port = args.httpPort != null ? args.httpPort : DEFAULT_HTTP_PORT;
            int ui = DEFAULT_UI_REFRESH_MS;
            int hb = DEFAULT_HEARTBEAT_MS;
            int minUp = DEFAULT_MIN_UPTIME_MS;
            int boMin = DEFAULT_BACKOFF_MIN_MS;
            int boMax = DEFAULT_BACKOFF_MAX_MS;
            int maxR = DEFAULT_MAX_RESTARTS_PER_HOUR;
            boolean autoStart = true;

            List<String> cmd = args.cmd != null ? args.cmd : suggestedCommand(env);
            Path wd = Paths.get(System.getProperty("user.dir", "."));
            Map<String, String> e = new LinkedHashMap<>();
            e.put("CLAM_BUILD", BUILD_TOKEN);
            e.put("CLAM_MODE", "watchdog");
            e.put("CLAM_HEARTBEAT_MS", Integer.toString(hb));

            Pattern crashSig = Pattern.compile("(?i)(fatal|panic|out of memory|segmentation fault|unhandled|exception)");
            int scanLines = 120;
            int scanBytes = 48_000;

            int rebuildWindowSeconds = 3600;
            int rebuildMaxAttempts = 5;
            int rebuildAttemptSpacingMs = 3500;
            boolean rebuildAggressiveGc = false;

            int journalFlushMs = 1500;
            int snapshotRingSize = 256;

            return new Config(
                port, ui, hb, minUp, boMin, boMax, maxR, autoStart,
                cmd, wd, e,
                crashSig, scanLines, scanBytes,
                rebuildWindowSeconds, rebuildMaxAttempts, rebuildAttemptSpacingMs, rebuildAggressiveGc,
                journalFlushMs, snapshotRingSize
            );
        }

        static List<String> suggestedCommand(RuntimeEnv env) {
            // This is intentionally conservative: a placeholder that works out-of-the-box.
            // The user can set the real clawbot command in the UI.
            if (env.windows) return List.of("cmd", "/c", "ping", "127.0.0.1", "-n", "6");
            return List.of("sh", "-lc", "sleep 5");
        }

        static Config fromMap(Map<String, Object> m, Args args, RuntimeEnv env) {
            int port = asInt(m.get("httpPort"), args.httpPort != null ? args.httpPort : DEFAULT_HTTP_PORT);
            int ui = asInt(m.get("uiRefreshMs"), DEFAULT_UI_REFRESH_MS);
            int hb = asInt(m.get("heartbeatMs"), DEFAULT_HEARTBEAT_MS);
            int minUp = asInt(m.get("minUptimeMs"), DEFAULT_MIN_UPTIME_MS);
            int boMin = asInt(m.get("backoffMinMs"), DEFAULT_BACKOFF_MIN_MS);
            int boMax = asInt(m.get("backoffMaxMs"), DEFAULT_BACKOFF_MAX_MS);
            int maxR = asInt(m.get("maxRestartsPerHour"), DEFAULT_MAX_RESTARTS_PER_HOUR);
            boolean autoStart = asBool(m.get("autoStart"), true);

            List<String> cmd = asStringList(m.get("command"), args.cmd != null ? args.cmd : suggestedCommand(env));
            Path wd = Paths.get(asString(m.get("workDir"), System.getProperty("user.dir", ".")));

            Map<String, String> e = asStringMap(m.get("env"));
            if (!e.containsKey("CLAM_BUILD")) e.put("CLAM_BUILD", BUILD_TOKEN);
            if (!e.containsKey("CLAM_MODE")) e.put("CLAM_MODE", "watchdog");
            if (!e.containsKey("CLAM_HEARTBEAT_MS")) e.put("CLAM_HEARTBEAT_MS", Integer.toString(hb));

            Pattern crashSig = Pattern.compile(asString(m.get("crashSignature"), "(?i)(fatal|panic|out of memory|segmentation fault|unhandled|exception)"));
            int scanLines = asInt(m.get("crashScanLines"), 120);
            int scanBytes = asInt(m.get("crashScanBytes"), 48_000);

            int rebuildWindowSeconds = asInt(m.get("rebuildWindowSeconds"), 3600);
            int rebuildMaxAttempts = asInt(m.get("rebuildMaxAttempts"), 5);
            int rebuildAttemptSpacingMs = asInt(m.get("rebuildAttemptSpacingMs"), 3500);
            boolean rebuildAggressiveGc = asBool(m.get("rebuildAggressiveGc"), false);

            int journalFlushMs = asInt(m.get("journalFlushMs"), 1500);
            int snapshotRingSize = asInt(m.get("snapshotRingSize"), 256);

            return new Config(
                port, ui, hb, minUp, boMin, boMax, maxR, autoStart,
                cmd, wd, e,
                crashSig, scanLines, scanBytes,
                rebuildWindowSeconds, rebuildMaxAttempts, rebuildAttemptSpacingMs, rebuildAggressiveGc,
                journalFlushMs, snapshotRingSize
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("httpPort", httpPort);
            m.put("uiRefreshMs", uiRefreshMs);
            m.put("heartbeatMs", heartbeatMs);
            m.put("minUptimeMs", minUptimeMs);
            m.put("backoffMinMs", backoffMinMs);
            m.put("backoffMaxMs", backoffMaxMs);
            m.put("maxRestartsPerHour", maxRestartsPerHour);
            m.put("autoStart", autoStart);
            m.put("command", command);
            m.put("workDir", workDir.toString());
            m.put("env", env);
            m.put("crashSignature", crashSignature.pattern());
            m.put("crashScanLines", crashScanLines);
            m.put("crashScanBytes", crashScanBytes);
            m.put("rebuildWindowSeconds", rebuildWindowSeconds);
            m.put("rebuildMaxAttempts", rebuildMaxAttempts);
            m.put("rebuildAttemptSpacingMs", rebuildAttemptSpacingMs);
            m.put("rebuildAggressiveGc", rebuildAggressiveGc);
            m.put("journalFlushMs", journalFlushMs);
            m.put("snapshotRingSize", snapshotRingSize);
            return m;
        }

        private static int asInt(Object o, int d) {
            if (o instanceof Number n) return n.intValue();
            if (o instanceof String s) {
                try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
            }
            return d;
        }

        private static boolean asBool(Object o, boolean d) {
            if (o instanceof Boolean b) return b;
            if (o instanceof String s) return s.trim().equalsIgnoreCase("true");
            return d;
        }

        private static String asString(Object o, String d) {
            if (o == null) return d;
            return String.valueOf(o);
        }

        @SuppressWarnings("unchecked")
        private static List<String> asStringList(Object o, List<String> d) {
            if (o instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object x : list) out.add(String.valueOf(x));
                return List.copyOf(out);
            }
            return d;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, String> asStringMap(Object o) {
            Map<String, String> out = new LinkedHashMap<>();
            if (o instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------------
    // Clock + time helpers
    // ---------------------------------------------------------------------
    static final class Clock {
        long nowMs() { return System.currentTimeMillis(); }
        Instant now() { return Instant.now(); }
    }

    // ---------------------------------------------------------------------
    // Structured event for journal/UI/HTTP
    // ---------------------------------------------------------------------
    static final class Event {
        final String level;
        final String type;
        final String message;
        final long atMs;
        final Map<String, Object> fields;

        private Event(String level, String type, String message, long atMs, Map<String, Object> fields) {
            this.level = level;
            this.type = type;
            this.message = message;
            this.atMs = atMs;
            this.fields = fields;
        }

        static Event info(String type, String message) { return new Event("info", type, message, System.currentTimeMillis(), new LinkedHashMap<>()); }
        static Event warn(String type, String message) { return new Event("warn", type, message, System.currentTimeMillis(), new LinkedHashMap<>()); }
        static Event error(String type, String message) { return new Event("error", type, message, System.currentTimeMillis(), new LinkedHashMap<>()); }

        Event with(String k, Object v) {
            if (k != null) fields.put(k, v);
            return this;
        }

        String toNdjson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("atMs", atMs);
            m.put("level", level);
            m.put("type", type);
            m.put("message", message);
            if (!fields.isEmpty()) m.put("fields", fields);
            return Json.stringify(m);
        }
    }

    // ---------------------------------------------------------------------
    // Rolling log file
    // ---------------------------------------------------------------------
    static final class RollingLog {
        private final Path path;
        private final long rotateBytes;
        private final int keep;
        private final Object lock = new Object();
        private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        RollingLog(Path path, long rotateBytes, int keep) {
            this.path = path;
            this.rotateBytes = rotateBytes;
            this.keep = Math.max(1, keep);
            ensureDir(path.toAbsolutePath().getParent());
        }

        void info(String msg) { write("INFO", msg, null); }
        void warn(String msg) { write("WARN", msg, null); }
        void error(String msg, Throwable t) { write("ERROR", msg, t); }

        private void write(String level, String msg, Throwable t) {
            synchronized (lock) {
                rotateIfNeeded();
                String line = fmt.format(new Date()) + " [" + level + "] " + msg + "\n";
                try {
                    Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (t != null) {
                        StringWriter sw = new StringWriter();
                        t.printStackTrace(new PrintWriter(sw));
                        Files.writeString(path, sw.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    }
                } catch (IOException ignored) {}
            }
        }

        private void rotateIfNeeded() {
            try {
                if (Files.exists(path)) {
                    long size = Files.size(path);
                    if (size < rotateBytes) return;
                }
                // rotate: log -> log.1 -> log.2 -> ...
                for (int i = keep - 1; i >= 1; i--) {
                    Path src = Paths.get(path.toString() + "." + i);
                    Path dst = Paths.get(path.toString() + "." + (i + 1));
                    if (Files.exists(src)) {
                        try {
                            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    }
                }
                if (Files.exists(path)) {
                    try {
                        Files.move(path, Paths.get(path.toString() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // Journal (NDJSON)
    // ---------------------------------------------------------------------
    static final class Journal {
        private final Path path;
        private final RollingLog log;
        private final Object lock = new Object();

        Journal(Path path, RollingLog log) {
            this.path = path;
            this.log = log;
            ensureDir(path.toAbsolutePath().getParent());
        }

        void write(Event e) {
            if (e == null) return;
            String line = e.toNdjson();
            if (line.length() > MAX_EVENT_BYTES) {
                // keep the head/tail; avoid huge writes
                String head = line.substring(0, MAX_EVENT_BYTES / 2);
                String tail = line.substring(line.length() - MAX_EVENT_BYTES / 2);
                line = head + "...(truncated)..." + tail;
            }
            synchronized (lock) {
                try {
                    Files.writeString(path, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    log.warn("Journal write failed: " + ex.getMessage());
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Event bus and ring buffer for snapshots
    // ---------------------------------------------------------------------
    static final class EventBus {
        private final RollingLog log;
        private final Journal journal;
        private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

        EventBus(RollingLog log, Journal journal) {
            this.log = log;
            this.journal = journal;
        }

        void subscribe(Consumer<Event> cb) { if (cb != null) listeners.add(cb); }
        void unsubscribe(Consumer<Event> cb) { listeners.remove(cb); }

        void publish(Event e) {
            if (e == null) return;
            journal.write(e);
            if ("error".equals(e.level)) log.error(e.type + ": " + e.message, null);
            else if ("warn".equals(e.level)) log.warn(e.type + ": " + e.message);
            else log.info(e.type + ": " + e.message);
            for (Consumer<Event> cb : listeners) {
                try { cb.accept(e); } catch (Exception ignored) {}
            }
        }
    }

    // ---------------------------------------------------------------------
    // Watchdog engine
    // ---------------------------------------------------------------------
    static final class WatchdogEngine {
        private final Config config;
        private final Clock clock;
        private final RollingLog log;
        private final Journal journal;
        private final EventBus bus;

        private final ScheduledExecutorService scheduler;
        private final ExecutorService ioPool;
        private final AtomicBoolean running = new AtomicBoolean(false);

        private final RestartLimiter limiter;
        private final Backoff backoff;

        private final Object procLock = new Object();
        private volatile Process process;
        private volatile long procStartedMs;
        private volatile long procExitedMs;
        private volatile Integer lastExitCode;
        private volatile String lastCrashHint;

        private final RingBuffer<String> stdoutRing;
        private final RingBuffer<String> stderrRing;
        private final RingBuffer<Event> eventRing;

        private volatile int rebuildAttempts;
        private volatile long rebuildWindowStartMs;

        WatchdogEngine(Config config, Clock clock, RollingLog log, Journal journal, EventBus bus) {
            this.config = config;
            this.clock = clock;
            this.log = log;
            this.journal = journal;
            this.bus = bus;
            this.scheduler = Executors.newScheduledThreadPool(3, new NamedThreadFactory("clam-sched"));
            this.ioPool = Executors.newCachedThreadPool(new NamedThreadFactory("clam-io"));
            this.limiter = new RestartLimiter(config.maxRestartsPerHour);
            this.backoff = new Backoff(config.backoffMinMs, config.backoffMaxMs, 0.22, 0.12);
            this.stdoutRing = new RingBuffer<>(config.snapshotRingSize);
            this.stderrRing = new RingBuffer<>(config.snapshotRingSize);
            this.eventRing = new RingBuffer<>(config.snapshotRingSize);
            bus.subscribe(e -> eventRing.add(e));
        }

        void start() {
            if (!running.compareAndSet(false, true)) return;

            bus.publish(Event.info("clam.engine.start", "Engine starting")
                .with("version", APP_VERSION)
                .with("buildStamp", BUILD_STAMP)
                .with("pid", RuntimeEnv.detect().pid));

            scheduler.scheduleAtFixedRate(this::tickHeartbeatSafe, 200, config.heartbeatMs, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::tickMonitorSafe, 350, 500, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::tickRebuildSafe, 700, 900, TimeUnit.MILLISECONDS);

            if (config.autoStart) {
                scheduler.execute(this::ensureRunningSafe);
            }
        }

        void stop() {
            if (!running.compareAndSet(true, false)) return;
            bus.publish(Event.warn("clam.engine.stop", "Engine stopping"));
            synchronized (procLock) {
                if (process != null) {
                    try {
                        process.destroy();
                    } catch (Exception ignored) {}
                }
            }
            scheduler.shutdownNow();
            ioPool.shutdownNow();
        }

        Snapshot snapshot() {
            Process p = process;
            long now = clock.nowMs();
            boolean alive = p != null && p.isAlive();

            List<String> outTail = stdoutRing.tail(LOG_TAIL_LINES);
            List<String> errTail = stderrRing.tail(LOG_TAIL_LINES);
            List<Event> evTail = eventRing.tail(250);

            String cmd = config.command != null ? String.join(" ", config.command) : "(none)";
            long upMs = (procStartedMs > 0 && alive) ? (now - procStartedMs) : 0;
            long downMs = (!alive && procExitedMs > 0) ? (now - procExitedMs) : 0;

            return new Snapshot(
                alive,
                procStartedMs,
                procExitedMs,
                lastExitCode,
                lastCrashHint,
                rebuildAttempts,
                rebuildWindowStartMs,
                limiter.countLastHour(now),
                backoff.lastDelayMs(),
                cmd,
                config.workDir.toString(),
                outTail,
                errTail,
                evTail
            );
        }

        void setCommand(List<String> cmd, Path wd, Map<String, String> env) {
            if (cmd == null || cmd.isEmpty()) return;
            bus.publish(Event.info("clam.engine.command", "Command updated").with("cmd", cmd));
            // Config is immutable; this method is intentionally limited.
            // For UI editing, we request restart with an explicit new Config by writing config file and reloading.
        }

        void manualStart() { scheduler.execute(this::ensureRunningSafe); }
        void manualStop() { scheduler.execute(this::stopProcessSafe); }
        void manualRestart() { scheduler.execute(this::restartSafe); }

        private void tickHeartbeatSafe() { try { tickHeartbeat(); } catch (Exception e) { bus.publish(Event.warn("clam.tick.heartbeat", "Heartbeat tick failed").with("error", e.toString())); } }
        private void tickMonitorSafe() { try { tickMonitor(); } catch (Exception e) { bus.publish(Event.warn("clam.tick.monitor", "Monitor tick failed").with("error", e.toString())); } }
        private void tickRebuildSafe() { try { tickRebuild(); } catch (Exception e) { bus.publish(Event.warn("clam.tick.rebuild", "Rebuild tick failed").with("error", e.toString())); } }
        private void ensureRunningSafe() { try { ensureRunning(); } catch (Exception e) { bus.publish(Event.error("clam.ensure", "Ensure running failed").with("error", e.toString())); } }
        private void stopProcessSafe() { try { stopProcess(); } catch (Exception e) { bus.publish(Event.warn("clam.stop", "Stop failed").with("error", e.toString())); } }
        private void restartSafe() { try { stopProcess(); sleep(200); ensureRunning(); } catch (Exception e) { bus.publish(Event.error("clam.restart", "Restart failed").with("error", e.toString())); } }

        private void tickHeartbeat() {
            long now = clock.nowMs();
            Process p = process;
            boolean alive = p != null && p.isAlive();

            if (alive) {
                bus.publish(Event.info("clam.heartbeat", "clawbot alive")
                    .with("uptimeMs", now - procStartedMs)
                    .with("pidHint", pidHint(p)));
            } else {
                bus.publish(Event.warn("clam.heartbeat", "clawbot not running")
                    .with("downMs", procExitedMs > 0 ? (now - procExitedMs) : -1)
                    .with("lastExitCode", lastExitCode)
                    .with("lastCrashHint", lastCrashHint));
            }
        }

        private void tickMonitor() {
            if (!running.get()) return;
            Process p = process;
            if (p == null) return;
            if (p.isAlive()) return;

            long now = clock.nowMs();
            long lifetime = procStartedMs > 0 ? (now - procStartedMs) : 0;
            if (lifetime >= config.minUptimeMs) {
                // healthy run ended; reset backoff
                backoff.reset();
            }

            // capture exit code
            Integer code = null;
            try { code = p.exitValue(); } catch (IllegalThreadStateException ignored) {}
            lastExitCode = code;
            procExitedMs = now;

            bus.publish(Event.warn("clam.process.exit", "Target process exited")
                .with("exitCode", code)
                .with("lifetimeMs", lifetime));

            // if died quickly, schedule rebuild logic
            if (lifetime < config.minUptimeMs) {
                String hint = scanCrashHint();
                lastCrashHint = hint;
                bus.publish(Event.warn("clam.process.crash_hint", "Crash hint scanned")
                    .with("hint", hint == null ? "(none)" : hint));
            }

            // restart if possible
            scheduler.execute(this::ensureRunningSafe);
        }

        private void tickRebuild() {
            long now = clock.nowMs();
            // Rebuild is a soft loop: if we are crashing too often, apply a repair policy.
            int restarts = limiter.countLastHour(now);
            if (restarts < Math.max(3, config.maxRestartsPerHour / 4)) return;

            if (rebuildWindowStartMs == 0 || now - rebuildWindowStartMs > config.rebuildWindowSeconds * 1000L) {
                rebuildWindowStartMs = now;
                rebuildAttempts = 0;
            }

            if (rebuildAttempts >= config.rebuildMaxAttempts) return;

            // if currently running, don't rebuild
            Process p = process;
            if (p != null && p.isAlive()) return;

            // attempt "autofix": a sequence of local actions
            rebuildAttempts++;
            bus.publish(Event.warn("clam.rebuild.start", "Starting rebuild attempt " + rebuildAttempts)
                .with("restartsLastHour", restarts)
                .with("lastCrashHint", lastCrashHint));

            // simple policies that don't require domain-specific knowledge:
            // - rotate leftover temp files in state dir (already handled by rolling log)
            // - optional GC on Windows to encourage releasing file handles
            if (config.rebuildAggressiveGc) {
                System.gc();
                bus.publish(Event.info("clam.rebuild.gc", "Requested GC"));
            }

            // Wait a little before restart attempt (avoid thrash)
            sleep(config.rebuildAttemptSpacingMs);
            scheduler.execute(this::ensureRunningSafe);
        }

        private void ensureRunning() {
            if (!running.get()) return;
            synchronized (procLock) {
                if (process != null && process.isAlive()) return;
            }

            long now = clock.nowMs();
            if (!limiter.allow(now)) {
                bus.publish(Event.error("clam.restart.blocked", "Restart rate limited")
                    .with("maxPerHour", config.maxRestartsPerHour)
                    .with("count", limiter.countLastHour(now)));
                return;
            }

            long delay = backoff.nextDelayMs();
            if (delay > 0) {
                bus.publish(Event.warn("clam.restart.backoff", "Restart backoff delay")
                    .with("delayMs", delay));
                sleep(delay);
            }

            ProcessBuilder pb = new ProcessBuilder(config.command);
            pb.directory(config.workDir.toFile());
            Map<String, String> env = pb.environment();
            env.putAll(config.env);

            // stdout/stderr capture
            pb.redirectErrorStream(false);

            bus.publish(Event.info("clam.process.start", "Starting target process")
                .with("cmd", config.command)
                .with("workDir", config.workDir.toString()));

            try {
                Process p = pb.start();
                synchronized (procLock) {
                    process = p;
                    procStartedMs = now;
                    procExitedMs = 0;
                    lastExitCode = null;
                }

                limiter.noteRestart(now);
                lastCrashHint = null;

                ioPool.submit(() -> pump("stdout", p.getInputStream(), stdoutRing, bus));
                ioPool.submit(() -> pump("stderr", p.getErrorStream(), stderrRing, bus));

            } catch (IOException e) {
                bus.publish(Event.error("clam.process.start_failed", "Failed to start target process")
                    .with("error", e.toString()));
            }
        }

        private void stopProcess() {
            synchronized (procLock) {
                if (process == null) return;
                Process p = process;
                if (p.isAlive()) {
                    bus.publish(Event.warn("clam.process.stop", "Stopping target process"));
                    try { p.destroy(); } catch (Exception ignored) {}
                }
                process = null;
            }
        }

        private static void pump(String name, InputStream in, RingBuffer<String> ring, EventBus bus) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    ring.add(line);
                    if (line.length() > 0 && line.length() < 280) {
                        if (line.toLowerCase(Locale.ROOT).contains("error") || line.toLowerCase(Locale.ROOT).contains("fatal")) {
                            bus.publish(Event.warn("clam.stream." + name, "Stream flagged line").with("line", line));
                        }
                    }
                }
            } catch (IOException ignored) {
                // stream closes on process exit
            }
        }

        private String scanCrashHint() {
            // heuristic: look at stderr tail for signature lines
            List<String> tail = stderrRing.tail(config.crashScanLines);
            for (int i = tail.size() - 1; i >= 0; i--) {
                String line = tail.get(i);
                if (line == null) continue;
                if (line.length() > config.crashScanBytes) continue;
                if (config.crashSignature.matcher(line).find()) return line.trim();
            }
            // fallback: stdout tail
            tail = stdoutRing.tail(config.crashScanLines / 2);
            for (int i = tail.size() - 1; i >= 0; i--) {
                String line = tail.get(i);
                if (line == null) continue;
                if (config.crashSignature.matcher(line).find()) return line.trim();
            }
            return null;
        }

        private static String pidHint(Process p) {
            try {
                // Java 9+ gives pid() on Process
                long pid = p.pid();
                return Long.toString(pid);
            } catch (Throwable ignored) {
                return "?";
            }
        }
    }

    // ---------------------------------------------------------------------
    // Snapshot for UI/HTTP
    // ---------------------------------------------------------------------
    static final class Snapshot {
        final boolean alive;
        final long startedMs;
        final long exitedMs;
        final Integer exitCode;
        final String crashHint;
        final int rebuildAttempts;
        final long rebuildWindowStartMs;
        final int restartsLastHour;
        final long backoffMs;
        final String cmd;
        final String workDir;
        final List<String> stdoutTail;
        final List<String> stderrTail;
        final List<Event> eventsTail;

        Snapshot(boolean alive, long startedMs, long exitedMs, Integer exitCode, String crashHint,
                 int rebuildAttempts, long rebuildWindowStartMs, int restartsLastHour, long backoffMs,
                 String cmd, String workDir,
                 List<String> stdoutTail, List<String> stderrTail, List<Event> eventsTail) {
            this.alive = alive;
            this.startedMs = startedMs;
            this.exitedMs = exitedMs;
            this.exitCode = exitCode;
            this.crashHint = crashHint;
            this.rebuildAttempts = rebuildAttempts;
            this.rebuildWindowStartMs = rebuildWindowStartMs;
            this.restartsLastHour = restartsLastHour;
            this.backoffMs = backoffMs;
            this.cmd = cmd;
            this.workDir = workDir;
            this.stdoutTail = stdoutTail;
            this.stderrTail = stderrTail;
            this.eventsTail = eventsTail;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("app", APP_NAME);
            m.put("version", APP_VERSION);
            m.put("buildStamp", BUILD_STAMP);
            m.put("alive", alive);
            m.put("startedMs", startedMs);
            m.put("exitedMs", exitedMs);
            m.put("exitCode", exitCode);
            m.put("crashHint", crashHint);
            m.put("rebuildAttempts", rebuildAttempts);
            m.put("rebuildWindowStartMs", rebuildWindowStartMs);
            m.put("restartsLastHour", restartsLastHour);
            m.put("backoffMs", backoffMs);
            m.put("cmd", cmd);
            m.put("workDir", workDir);
            m.put("stdoutTail", stdoutTail);
            m.put("stderrTail", stderrTail);

            List<Map<String, Object>> ev = new ArrayList<>();
            for (Event e : eventsTail) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("atMs", e.atMs);
                em.put("level", e.level);
                em.put("type", e.type);
                em.put("message", e.message);
                if (!e.fields.isEmpty()) em.put("fields", e.fields);
                ev.add(em);
            }
            m.put("events", ev);
            return m;
        }
    }

    // ---------------------------------------------------------------------
    // Backoff with jitter (mainstream exponential)
    // ---------------------------------------------------------------------
    static final class Backoff {
        private final int minMs;
        private final int maxMs;
        private final double factor;
        private final double jitter;
        private int step;
        private final Random rnd;
        private long last;

        Backoff(int minMs, int maxMs, double factor, double jitter) {
            this.minMs = Math.max(0, minMs);
            this.maxMs = Math.max(this.minMs, maxMs);
            this.factor = factor <= 0 ? 0.2 : factor;
            this.jitter = Math.max(0, Math.min(0.45, jitter));
            this.step = 0;
            this.rnd = new Random(seedFromMachine());
        }

        long nextDelayMs() {
            if (step == 0) {
                step = 1;
                last = 0;
                return 0;
            }
            double exp = Math.pow(1.0 + factor, step - 1);
            long base = (long) (minMs * exp);
            base = Math.max(minMs, Math.min(maxMs, base));
            long j = (long) (base * jitter);
            long delta = (j > 0) ? (rnd.nextInt((int) (2L * j + 1)) - j) : 0;
            long out = Math.max(0, Math.min(maxMs, base + delta));
            last = out;
            step = Math.min(step + 1, 50);
            return out;
        }

        void reset() { step = 0; last = 0; }
        long lastDelayMs() { return last; }

        private static long seedFromMachine() {
            long t = System.nanoTime();
            long h = 1469598103934665603L;
            h ^= t; h *= 1099511628211L;
            h ^= (long) Runtime.getRuntime().availableProcessors(); h *= 1099511628211L;
            h ^= (long) Objects.hashCode(System.getProperty("user.name", "")); h *= 1099511628211L;
            return h;
        }
    }

    // ---------------------------------------------------------------------
    // Restart limiter (sliding window)
    // ---------------------------------------------------------------------
    static final class RestartLimiter {
        private final int maxPerHour;
        private final Deque<Long> restarts = new ArrayDeque<>();

        RestartLimiter(int maxPerHour) {
            this.maxPerHour = Math.max(1, maxPerHour);
        }

        synchronized boolean allow(long nowMs) {
            trim(nowMs);
            return restarts.size() < maxPerHour;
        }

        synchronized void noteRestart(long nowMs) {
            trim(nowMs);
            restarts.addLast(nowMs);
        }

        synchronized int countLastHour(long nowMs) {
            trim(nowMs);
            return restarts.size();
        }

        private void trim(long nowMs) {
            long cut = nowMs - 3600_000L;
            while (!restarts.isEmpty() && restarts.peekFirst() < cut) restarts.removeFirst();
        }
    }

    // ---------------------------------------------------------------------
    // Lightweight HTTP status server (no deps)
    // ---------------------------------------------------------------------
    static final class StatusServer {
        private final int port;
        private final Supplier<Snapshot> snapshotSupplier;
        private final RollingLog log;
        private final Journal journal;
        private final EventBus bus;
        private volatile ServerSocket server;
        private volatile Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(false);

        interface Supplier<T> { T get(); }

        StatusServer(int port, Supplier<Snapshot> snapshotSupplier, RollingLog log, Journal journal, EventBus bus) {
            this.port = port <= 0 ? DEFAULT_HTTP_PORT : port;
            this.snapshotSupplier = snapshotSupplier;
            this.log = log;
            this.journal = journal;
            this.bus = bus;
        }

        void start() {
            if (!running.compareAndSet(false, true)) return;
            thread = new Thread(this::serveLoop, "clam-http");
            thread.setDaemon(true);
            thread.start();
            bus.publish(Event.info("clam.http.start", "HTTP status server started").with("port", port));
        }

        void stop() {
            running.set(false);
            try { if (server != null) server.close(); } catch (IOException ignored) {}
        }

        private void serveLoop() {
            try (ServerSocket ss = new ServerSocket()) {
                this.server = ss;
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("127.0.0.1", port));
                while (running.get()) {
                    try {
                        Socket s = ss.accept();
                        s.setSoTimeout(2500);
                        handle(s);
                    } catch (SocketException se) {
                        if (!running.get()) break;
                    } catch (Exception e) {
                        bus.publish(Event.warn("clam.http.accept", "Accept failed").with("error", e.toString()));
                    }
                }
            } catch (Exception e) {
                bus.publish(Event.error("clam.http.bind", "Could not bind HTTP server").with("error", e.toString()).with("port", port));
            }
        }

        private void handle(Socket s) {
            try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                String req = readLine(in);
                if (req == null) return;
                // read and discard headers
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.isEmpty()) break;
                }

                String[] parts = req.split("\\s+");
                String method = parts.length > 0 ? parts[0] : "";
                String path = parts.length > 1 ? parts[1] : "/";

                if (!"GET".equalsIgnoreCase(method)) {
                    writeResponse(out, 405, "text/plain; charset=utf-8", "Method Not Allowed");
                    return;
                }

                if ("/".equals(path) || "/status".equals(path)) {
                    Snapshot snap = snapshotSupplier.get();
                    String body = Json.stringify(snap.toMap());
                    writeResponse(out, 200, "application/json; charset=utf-8", body);
                    return;
                }
                if ("/health".equals(path)) {
                    Snapshot snap = snapshotSupplier.get();
                    String body = snap.alive ? "ok" : "down";
                    writeResponse(out, snap.alive ? 200 : 503, "text/plain; charset=utf-8", body);
                    return;
                }
                writeResponse(out, 404, "text/plain; charset=utf-8", "Not Found");
            } catch (Exception e) {
                bus.publish(Event.warn("clam.http.handle", "Handle failed").with("error", e.toString()));
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b == -1) break;
                if (b == '\n') break;
                if (b != '\r') bos.write(b);
                if (bos.size() > 8192) break;
            }
            if (bos.size() == 0) return null;
            return bos.toString(StandardCharsets.UTF_8);
        }

        private static void writeResponse(OutputStream out, int code, String ct, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String status = switch (code) {
                case 200 -> "OK";
                case 404 -> "Not Found";
                case 405 -> "Method Not Allowed";
                case 503 -> "Service Unavailable";
                default -> "OK";
            };
            String hdr = ""
                + "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + ct + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";
            out.write(hdr.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }
    }

    // ---------------------------------------------------------------------
    // UI (Swing)
    // ---------------------------------------------------------------------
    static final class ClamUI {
        private final Path configPath;
        private final Config config;
        private final RuntimeEnv env;
        private final WatchdogEngine engine;
        private final StatusServer http;
        private final RollingLog log;
        private final Journal journal;
        private final EventBus bus;

        private JFrame frame;
        private JTextArea stdoutArea;
        private JTextArea stderrArea;
        private JTextArea eventsArea;
        private JLabel statusLabel;
        private JLabel uptimeLabel;
        private JLabel restartsLabel;
        private JLabel backoffLabel;
        private JTextField cmdField;
        private JTextField wdField;
        private JTextField portField;
        private JCheckBox headlessHint;
        private Timer timer;

        private final AtomicInteger uiTick = new AtomicInteger(0);

        ClamUI(Path configPath, Config config, RuntimeEnv env, WatchdogEngine engine, StatusServer http,
               RollingLog log, Journal journal, EventBus bus) {
            this.configPath = configPath;
            this.config = config;
            this.env = env;
            this.engine = engine;
            this.http = http;
            this.log = log;
            this.journal = journal;
            this.bus = bus;
        }

        void show() {
            frame = new JFrame(APP_NAME + " — claw autofix & rebuild (" + APP_VERSION + ")");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setMinimumSize(new Dimension(1020, 760));

            JPanel root = new JPanel(new BorderLayout(10, 10));
            root.setBorder(new EmptyBorder(10, 10, 10, 10));
            root.add(buildTopBar(), BorderLayout.NORTH);
            root.add(buildCenter(), BorderLayout.CENTER);
            root.add(buildBottom(), BorderLayout.SOUTH);

            frame.setContentPane(root);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            timer = new Timer(config.uiRefreshMs, this::tickUI);
            timer.start();

            bus.publish(Event.info("clam.ui.open", "UI opened").with("configPath", configPath.toString()));
        }

        private JComponent buildTopBar() {
            JPanel p = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.fill = GridBagConstraints.HORIZONTAL;

            statusLabel = new JLabel("status: unknown");
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));

            uptimeLabel = new JLabel("uptime: —");
            restartsLabel = new JLabel("restarts/h: —");
            backoffLabel = new JLabel("backoff: —");

            JButton start = new JButton("Start");
            JButton stop = new JButton("Stop");
            JButton restart = new JButton("Restart");
            JButton copyStatus = new JButton("Copy status JSON");

            start.addActionListener(e -> engine.manualStart());
            stop.addActionListener(e -> engine.manualStop());
            restart.addActionListener(e -> engine.manualRestart());
            copyStatus.addActionListener(e -> copySnapshotToClipboard());

            int x = 0;
            c.gridx = x++; c.gridy = 0; c.weightx = 1; p.add(statusLabel, c);
            c.gridx = x++; c.gridy = 0; c.weightx = 0; p.add(start, c);
            c.gridx = x++; c.gridy = 0; c.weightx = 0; p.add(stop, c);
            c.gridx = x++; c.gridy = 0; c.weightx = 0; p.add(restart, c);
            c.gridx = x++; c.gridy = 0; c.weightx = 0; p.add(copyStatus, c);

            x = 0;
            c.gridx = x++; c.gridy = 1; c.weightx = 1; p.add(uptimeLabel, c);
            c.gridx = x++; c.gridy = 1; c.weightx = 1; p.add(restartsLabel, c);
            c.gridx = x++; c.gridy = 1; c.weightx = 1; c.gridwidth = 3; p.add(backoffLabel, c);
            c.gridwidth = 1;

            return p;
        }

        private JComponent buildCenter() {
            JTabbedPane tabs = new JTabbedPane();
            stdoutArea = makeArea();
            stderrArea = makeArea();
            eventsArea = makeArea();
