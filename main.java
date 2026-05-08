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
