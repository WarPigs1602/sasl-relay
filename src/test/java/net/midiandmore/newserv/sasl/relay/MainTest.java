package net.midiandmore.newserv.sasl.relay;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class MainTest {
    private static final Pattern PID = Pattern.compile("Relay background process started: PID=(\\d+) log=(.+)");
    private static final long TIMEOUT_SECONDS = 15;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private final Set<ProcessHandle> children = new LinkedHashSet<>();
    private final List<Path> launcherOutputs = new ArrayList<>();

    @After
    public void cleanupChildren() throws Exception {
        try {
            for (Path output : launcherOutputs) {
                trackPrintedPid(output, children);
            }
        } finally {
            terminate(children);
        }
    }

    @Test
    public void helpWorksWithoutConfiguration() throws Exception {
        for (String option : List.of("--help", "-h")) {
            Result result = cli(option);
            assertEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains("Usage: java -jar"));
            for (String advertised : List.of("--debug", "--daemon", "--log-file", "--pid-file", "--start", "--stop")) {
                assertTrue(result.output(), result.output().contains(advertised));
            }
            assertFalse(Files.exists(temporary.getRoot().toPath().resolve("config.json")));
        }
    }

    @Test
    public void rejectsUnknownOptionsAndMultipleConfigurationPaths() throws Exception {
        assertRejected("Unexpected argument: --unknown", "--unknown");
        assertRejected("Unexpected argument: -x", "-x");
        assertRejected("Unexpected argument: second.json", "first.json", "second.json");
    }

    @Test
    public void logFileRequiresAPath() throws Exception {
        assertRejected("--log-file requires a path", "--log-file");
        assertRejected("--log-file requires a path", "--daemon", "--log-file");
        assertRejected("--log-file requires a path", "--log-file", "--debug");
        assertRejected("--log-file requires a path", "--log-file", "--daemon");
    }

    @Test
    public void logFileRequiresDaemonBeforeLoadingConfiguration() throws Exception {
        assertRejected("--log-file requires --daemon", "--log-file", "output.log");
        assertRejected("--log-file requires --daemon", "missing.json", "--debug", "--log-file", "output.log");
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("output.log")));
    }

    @Test
    public void foregroundDebugFlagControlsConnectionDiagnostics() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        Path config = temporary.newFile("foreground config.json").toPath();
        Files.writeString(config, "[{\"name\":\"host\",\"value\":\"127.0.0.1\"},"
                + "{\"name\":\"port\",\"value\":\"" + port + "\"}]");
        for (boolean debug : List.of(false, true)) {
            Path output = temporary.newFile().toPath();
            Process process = launch(output, debug
                    ? new String[]{"--debug", config.toString()}
                    : new String[]{config.toString()});
            try {
                String log = awaitText(output, "P10 relay disconnected:", process.toHandle());
                assertEquals(log, debug, log.contains("[DEBUG] Connecting to 127.0.0.1:" + port));
                if (!debug) {
                    assertFalse(log, log.contains("[DEBUG]"));
                }
                assertTrue(process.isAlive());
            } finally {
                terminate(Set.of(process.toHandle()));
            }
        }
    }

    @Test
    public void daemonPropagatesDebugAndAppendsToExplicitLog() throws Exception {
        checkDaemon(true, true);
    }

    @Test
    public void daemonDefaultsToNonDebugAndDefaultLogFile() throws Exception {
        checkDaemon(false, false);
    }

    @Test
    public void daemonStartStopAndRestartUseDefaultPidFile() throws Exception {
        checkLifecycle(null, List.of("--daemon", "start"), List.of("--daemon", "stop"));
    }

    @Test
    public void aliasesUseRelativePidFileWithSpaces() throws Exception {
        checkLifecycle("custom daemon state.pid", List.of("--start"), List.of("--stop"));
    }

    @Test
    public void stopWithoutConfigurationWaitsForExitAndRemovesAbsolutePidFile() throws Exception {
        Path pid = temporary.newFolder("state directory").toPath().resolve("daemon state.pid");
        ProcessHandle daemon = startDaemon(pid, "--daemon", "start");
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("config.json")));
        Result stopped = cli("--daemon", "stop", "--pid-file", pid.toString());
        assertEquals(stopped.output(), 0, stopped.exitCode());
        assertTrue(stopped.output(), stopped.output().contains("Relay daemon stopped: PID=" + daemon.pid()));
        assertFalse("Stop returned before daemon exited", daemon.isAlive());
        assertFalse("Stop must remove the PID file after exit", Files.exists(pid));
        Result repeated = cli("--stop", "--pid-file", pid.toString());
        assertEquals(repeated.output(), 0, repeated.exitCode());
        assertTrue(repeated.output(), repeated.output().contains("Relay daemon is not running"));
    }

    @Test
    public void rejectsConflictingLifecycleOptionsBeforeLoadingConfiguration() throws Exception {
        for (String[] options : List.of(
                new String[]{"--start", "--stop"},
                new String[]{"--daemon", "start", "--daemon", "stop"},
                new String[]{"--daemon", "--stop"},
                new String[]{"--daemon", "stop", "--start"})) {
            assertRejected("Daemon start and stop cannot be combined", options);
        }
        for (String[] options : List.of(
                new String[]{"--stop", "missing.json"},
                new String[]{"--daemon", "stop", "--debug"},
                new String[]{"--stop", "--log-file", "output.log"})) {
            assertRejected("Daemon stop only accepts --pid-file", options);
        }
        assertRejected("--pid-file requires daemon start or stop", "--pid-file", "state.pid");
        assertRejected("--pid-file requires a path", "--pid-file");
        assertRejected("--pid-file requires a path", "--start", "--pid-file", "--debug");
        assertRejected("--pid-file requires a path", "--stop", "--pid-file", "-h");
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("state.pid")));
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("output.log")));
    }

    @Test
    public void malformedPidFilesFailSafelyForStartAndStop() throws Exception {
        ProcessHandle unrelated = unrelatedProcess();
        Path pid = temporary.newFile("malformed.pid").toPath();
        Path config = transientConfig();
        for (String state : List.of("", "not-a-pid\n" + Instant.now() + "\n",
                "0\n" + Instant.now() + "\n", "-1\n" + Instant.now() + "\n",
                unrelated.pid() + "\nnot-a-time\n",
                unrelated.pid() + "\n" + startTime(unrelated) + "\nextra\n")) {
            for (String option : List.of("--start", "--stop")) {
                Files.writeString(pid, state);
                List<String> args = new ArrayList<>(List.of(option, "--pid-file", pid.toString()));
                if (option.equals("--start")) {
                    args.add(config.toString());
                }
                Result result = cli(args.toArray(String[]::new));
                assertNotEquals(result.output(), 0, result.exitCode());
                assertTrue(result.output(), result.output().contains("Invalid daemon state file:"));
                assertEquals("Malformed state must not be deleted or replaced", state, Files.readString(pid));
                assertTrue("Malformed state must not kill another process", unrelated.isAlive());
            }
        }
    }

    @Test
    public void reusedLivePidWithDifferentStartTimeNeverKillsUnrelatedProcess() throws Exception {
        ProcessHandle unrelated = unrelatedProcess();
        Path pid = temporary.newFile("reused.pid").toPath();
        String stale = unrelated.pid() + "\n" + startTime(unrelated).minusSeconds(1) + "\n";
        Files.writeString(pid, stale);
        Result stopped = cli("--stop", "--pid-file", pid.toString());
        assertEquals(stopped.output(), 0, stopped.exitCode());
        assertTrue(stopped.output(), stopped.output().contains("Relay daemon is not running"));
        assertFalse(Files.exists(pid));
        assertTrue("PID reuse must not kill an unrelated live process", unrelated.isAlive());
        Files.writeString(pid, stale);
        ProcessHandle daemon = startDaemon(pid, "--start");
        assertNotEquals(unrelated.pid(), daemon.pid());
        assertTrue("Starting over stale state must leave the unrelated process alive", unrelated.isAlive());
    }

    @Test
    public void matchingUnrelatedLiveProcessIsNeverTerminated() throws Exception {
        ProcessHandle unrelated = unrelatedProcess();
        Path pid = temporary.newFile("unrelated.pid").toPath();
        String state = unrelated.pid() + "\n" + startTime(unrelated) + "\n";
        Files.writeString(pid, state);
        for (String option : List.of("--stop", "--start")) {
            Result result = cli(option, "--pid-file", pid.toString());
            assertNotEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains("State file does not identify a relay daemon:"));
            assertEquals(state, Files.readString(pid));
            assertTrue("Forged state must not kill another process", unrelated.isAlive());
        }
    }

    @Test
    public void exitedProcessStateIsRemovedAndCanBeRestarted() throws Exception {
        ProcessHandle exited = unrelatedProcess();
        String state = exited.pid() + "\n" + startTime(exited) + "\n";
        terminate(Set.of(exited));
        Path pid = temporary.newFile("exited.pid").toPath();
        Files.writeString(pid, state);
        Result stopped = cli("--daemon", "stop", "--pid-file", pid.toString());
        assertEquals(stopped.output(), 0, stopped.exitCode());
        assertFalse(Files.exists(pid));
        Files.writeString(pid, state);
        startDaemon(pid, "--daemon", "start");
    }

    @Test
    public void fileLockRejectsConcurrentLifecycleCommandsWithoutChangingState() throws Exception {
        Path pid = temporary.getRoot().toPath().resolve("locked.pid");
        Path lock = pid.resolveSibling(pid.getFileName() + ".lock");
        Path config = transientConfig();
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertTrue(held.isValid());
            assertRejectedLock(cli("--start", config.toString(), "--pid-file", pid.toString()));
            assertRejectedLock(cli("--stop", "--pid-file", pid.toString()));
            assertFalse(Files.exists(pid));
            assertFalse(Files.exists(temporary.getRoot().toPath().resolve("sasl-relay.log")));
        }
        startDaemon(pid, "--start");
    }

    private static void assertRejectedLock(Result result) {
        assertNotEquals(result.output(), 0, result.exitCode());
        assertTrue(result.output(), result.output().contains("Another daemon start/stop command is in progress"));
    }

    @Test
    public void conflictingLifecyclePathsPreserveExistingFilesWithoutStartingDaemon() throws Exception {
        Path config = transientConfig();
        String configuration = Files.readString(config);
        Path shared = temporary.newFile("shared daemon log.pid").toPath();
        String marker = "existing log contents\n";
        Files.writeString(shared, marker);
        Path separatePid = temporary.getRoot().toPath().resolve("separate.pid");
        Path sharedLock = shared.resolveSibling(shared.getFileName() + ".lock");
        for (Path[] paths : List.of(
                new Path[]{shared, shared},
                new Path[]{shared, shared.getParent().resolve(".").resolve(shared.getFileName())},
                new Path[]{separatePid, config},
                new Path[]{config, shared},
                new Path[]{shared, sharedLock})) {
            Result result = cli("--start", config.toString(),
                    "--pid-file", paths[0].toString(), "--log-file", paths[1].toString());
            assertNotEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains(
                    "Configuration, log, PID and lock files must have distinct paths"));
            assertFalse(result.output(), PID.matcher(result.output()).find());
            assertEquals(marker, Files.readString(shared));
            assertEquals(configuration, Files.readString(config));
            assertFalse(Files.exists(separatePid));
            assertFalse("Validation must happen before lock creation", Files.exists(sharedLock));
        }
    }

    @Test
    public void pidAndLockFileAliasesAreRejectedBeforeStateChanges() throws Exception {
        Path pid = temporary.newFile("aliased.pid").toPath();
        String state = "existing state\n";
        Files.writeString(pid, state);
        Path lock = pid.resolveSibling(pid.getFileName() + ".lock");
        Files.createLink(lock, pid);
        Path config = transientConfig();
        for (String operation : List.of("--start", "--stop")) {
            List<String> args = new ArrayList<>(List.of(operation, "--pid-file", pid.toString()));
            if (operation.equals("--start")) {
                args.add(config.toString());
            }
            Result result = cli(args.toArray(String[]::new));
            assertNotEquals(result.output(), 0, result.exitCode());
            assertTrue(result.output(), result.output().contains("must have distinct paths"));
            assertEquals(state, Files.readString(pid));
            assertEquals(state, Files.readString(lock));
        }
    }

    private void checkLifecycle(String pidArgument, List<String> start, List<String> stop) throws Exception {
        Path config = transientConfig();
        Path pid = temporary.getRoot().toPath().resolve(pidArgument == null ? "sasl-relay.pid" : pidArgument);
        List<String> startArgs = new ArrayList<>(start);
        startArgs.add(config.toString());
        List<String> stopArgs = new ArrayList<>(stop);
        if (pidArgument != null) {
            startArgs.addAll(List.of("--pid-file", pidArgument));
            stopArgs.addAll(List.of("--pid-file", pidArgument));
        }
        ProcessHandle first = assertStarted(cli(startArgs.toArray(String[]::new)), pid);
        String initialState = Files.readString(pid);
        List<String> duplicateArgs = new ArrayList<>(List.of(start.contains("--start") ? "--daemon" : "--start"));
        // A duplicate start must not reload a configuration that is no longer present.
        duplicateArgs.add("missing.json");
        if (pidArgument != null) {
            duplicateArgs.addAll(List.of("--pid-file", pidArgument));
        }
        Result duplicate = cli(duplicateArgs.toArray(String[]::new));
        assertEquals(duplicate.output(), 0, duplicate.exitCode());
        assertTrue(duplicate.output(), duplicate.output().contains("Relay daemon already running: PID=" + first.pid()));
        assertFalse(duplicate.output(), PID.matcher(duplicate.output()).find());
        assertEquals("Duplicate start changed daemon identity", initialState, Files.readString(pid));
        assertTrue(first.isAlive());
        Result stopped = cli(stopArgs.toArray(String[]::new));
        assertEquals(stopped.output(), 0, stopped.exitCode());
        assertTrue(stopped.output(), stopped.output().contains("Relay daemon stopped: PID=" + first.pid()));
        assertFalse("Stop must wait for process exit", first.isAlive());
        assertFalse(Files.exists(pid));
        Result repeated = cli(stopArgs.toArray(String[]::new));
        assertEquals(repeated.output(), 0, repeated.exitCode());
        assertTrue(repeated.output(), repeated.output().contains("Relay daemon is not running"));
        ProcessHandle restarted = assertStarted(cli(startArgs.toArray(String[]::new)), pid);
        assertFalse("Restart must replace daemon identity", initialState.equals(Files.readString(pid)));
        Result finalStop = cli(stopArgs.toArray(String[]::new));
        assertEquals(finalStop.output(), 0, finalStop.exitCode());
        assertFalse(restarted.isAlive());
        assertFalse(Files.exists(pid));
    }

    private ProcessHandle startDaemon(Path pid, String... options) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(options));
        args.addAll(List.of(transientConfig().toString(), "--pid-file", pid.toString()));
        return assertStarted(cli(args.toArray(String[]::new)), pid);
    }

    private ProcessHandle assertStarted(Result result, Path pid) throws Exception {
        assertEquals(result.output(), 0, result.exitCode());
        Matcher matcher = PID.matcher(result.output());
        assertTrue(result.output(), matcher.find());
        long identity = Long.parseLong(matcher.group(1));
        assertFalse("Multiple daemons launched: " + result.output(), matcher.find());
        ProcessHandle child = ProcessHandle.of(identity).orElseThrow(
                () -> new AssertionError("Daemon is not alive: " + identity));
        children.add(child);
        List<String> state = Files.readAllLines(pid);
        assertEquals("State must contain PID and start time", 2, state.size());
        assertEquals(identity, Long.parseLong(state.get(0)));
        assertEquals(startTime(child), Instant.parse(state.get(1)));
        awaitText(temporary.getRoot().toPath().resolve("sasl-relay.log"), "P10 relay disconnected:", child);
        assertTrue(child.isAlive());
        return child;
    }

    private Path transientConfig() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        Path config = temporary.newFile().toPath();
        Files.writeString(config, "[{\"name\":\"host\",\"value\":\"127.0.0.1\"},"
                + "{\"name\":\"port\",\"value\":\"" + port + "\"}]");
        return config;
    }

    private ProcessHandle unrelatedProcess() throws Exception {
        Path output = temporary.newFile().toPath();
        Process process = launchClass(output, UnrelatedProcess.class.getName());
        awaitText(output, "unrelated process ready", process.toHandle());
        return process.toHandle();
    }

    private static Instant startTime(ProcessHandle process) {
        return process.info().startInstant().orElseThrow(
                () -> new AssertionError("Process start time unavailable: " + process.pid()));
    }

    public static final class UnrelatedProcess {
        public static void main(String[] args) throws Exception {
            System.out.println("unrelated process ready");
            System.out.flush();
            Thread.sleep(TimeUnit.MINUTES.toMillis(2));
        }
    }

    private void checkDaemon(boolean debug, boolean explicitLog) throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        Path config = temporary.newFile("daemon config.json").toPath();
        Files.writeString(config, "[{\"name\":\"host\",\"value\":\"127.0.0.1\"},"
                + "{\"name\":\"port\",\"value\":\"" + port + "\"}]");
        Path log = temporary.getRoot().toPath().resolve(explicitLog ? "daemon output.log" : "sasl-relay.log");
        String marker = "existing log contents\n";
        Files.writeString(log, marker);
        Path output = temporary.newFile("launcher output.txt").toPath();
        List<String> args = new ArrayList<>(List.of(config.toString(), "--daemon"));
        if (debug) {
            args.add("--debug");
        }
        if (explicitLog) {
            args.addAll(List.of("--log-file", log.toString()));
        }
        Set<ProcessHandle> tracked = new LinkedHashSet<>();
        Process launcher = launch(output, args.toArray(String[]::new));
        tracked.add(launcher.toHandle());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (launcher.isAlive() && System.nanoTime() < deadline) {
                launcher.descendants().forEach(tracked::add);
                trackPrintedPid(output, tracked);
                launcher.waitFor(20, TimeUnit.MILLISECONDS);
            }
            assertFalse("Daemon launcher did not exit", launcher.isAlive());
            String stdout = Files.readString(output);
            assertEquals(stdout, 0, launcher.exitValue());
            Matcher match = PID.matcher(stdout);
            assertTrue(stdout, match.find());
            long pid = Long.parseLong(match.group(1));
            assertEquals(log.toAbsolutePath().toString(), match.group(2).trim());
            assertFalse("Launcher printed multiple daemon PIDs: " + stdout, match.find());
            ProcessHandle child = ProcessHandle.of(pid).orElseThrow(
                    () -> new AssertionError("Daemon PID is not alive: " + pid));
            tracked.add(child);
            assertNotEquals(launcher.pid(), child.pid());
            String contents = awaitText(log, "P10 relay disconnected:", child);
            assertTrue(contents, contents.startsWith(marker));
            assertEquals(contents, debug, contents.contains("[DEBUG] Connecting to 127.0.0.1:" + port));
            if (!debug) {
                assertFalse(contents, contents.contains("[DEBUG]"));
            }
            assertFalse("Daemon recursively launched another daemon: " + contents,
                    contents.contains("Relay background process started:"));
            assertTrue("Daemon exited unexpectedly", child.isAlive());
            List<ProcessHandle> descendants = child.descendants().collect(Collectors.toList());
            tracked.addAll(descendants);
            assertTrue("Daemon must not launch subprocesses", descendants.isEmpty());
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertTrue(child.info().command().orElse("").toLowerCase(java.util.Locale.ROOT)
                        .endsWith("javaw.exe"));
            }
        } finally {
            // Recover the PID even when an assertion fails before normal PID parsing.
            try {
                trackPrintedPid(output, tracked);
            } finally {
                terminate(tracked);
            }
        }
    }

    private void assertRejected(String message, String... args) throws Exception {
        Result result = cli(args);
        assertNotEquals(result.output(), 0, result.exitCode());
        assertTrue(result.output(), result.output().contains("IllegalArgumentException: " + message));
        assertFalse(result.output(), result.output().contains("NoSuchFileException"));
    }

    private Result cli(String... args) throws Exception {
        Path output = temporary.newFile().toPath();
        Process process = launch(output, args);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (process.isAlive() && System.nanoTime() < deadline) {
                process.descendants().forEach(children::add);
                trackPrintedPid(output, children);
                process.waitFor(20, TimeUnit.MILLISECONDS);
            }
            assertFalse("CLI did not exit: " + Files.readString(output), process.isAlive());
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            try {
                trackPrintedPid(output, children);
            } finally {
                // A daemon can still be reported as a descendant of its exited launcher on Windows.
                if (process.isAlive()) {
                    terminate(Set.of(process.toHandle()));
                }
            }
        }
    }

    private Process launch(Path output, String... args) throws Exception {
        return launchClass(output, Main.class.getName(), args);
    }

    private Process launchClass(Path output, String mainClass, String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        // The child runs in a config-free directory, so classpath entries must be absolute.
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path")).split(Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classpath, mainClass));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).directory(temporary.getRoot())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        children.add(process.toHandle());
        launcherOutputs.add(output);
        try {
            process.getOutputStream().close();
            return process;
        } catch (Exception ex) {
            terminate(Set.of(process.toHandle()));
            throw ex;
        }
    }

    private static String awaitText(Path file, String expected, ProcessHandle process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        String contents;
        do {
            contents = Files.readString(file, StandardCharsets.UTF_8);
            if (contents.contains(expected)) {
                return contents;
            }
            assertTrue("Process exited before logging " + expected + ": " + contents, process.isAlive());
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for " + expected + ": " + contents);
    }

    private static void trackPrintedPid(Path output, Set<ProcessHandle> tracked) throws Exception {
        Matcher matcher = PID.matcher(Files.readString(output));
        while (matcher.find()) {
            ProcessHandle.of(Long.parseLong(matcher.group(1))).ifPresent(tracked::add);
        }
    }

    private static void terminate(Set<ProcessHandle> processes) throws Exception {
        Set<ProcessHandle> tree = new LinkedHashSet<>(processes);
        processes.forEach(process -> process.descendants().forEach(tree::add));
        // Stop parents first so a broken recursive daemon cannot keep spawning children.
        tree.forEach(process -> {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        });
        for (ProcessHandle process : tree) {
            if (process.isAlive()) {
                process.onExit().get(5, TimeUnit.SECONDS);
            }
            assertFalse("Subprocess survived cleanup: " + process.pid(), process.isAlive());
        }
    }

    private record Result(int exitCode, String output) {
    }
}
