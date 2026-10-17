package net.midiandmore.newserv.sasl.relay;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Daemon {
    private Daemon() {
    }

    static void manage(boolean start, Path config, Path logFile, Path pidFile, boolean debug) throws Exception {
        pidFile = pidFile.toAbsolutePath().normalize();
        Path lockFile = pidFile.resolveSibling(pidFile.getFileName() + ".lock");
        if (sameFile(pidFile, lockFile) || (start && (sameFile(pidFile, logFile) || sameFile(lockFile, logFile)
                || sameFile(pidFile, config) || sameFile(lockFile, config) || sameFile(logFile, config)))) {
            throw new IOException("Configuration, log, PID and lock files must have distinct paths");
        }
        // Keep the lock file: deleting it would let concurrent callers lock different files.
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Another daemon start/stop command is in progress");
            }
            ProcessHandle running = readProcess(pidFile);
            if (start && running != null) {
                System.out.println("Relay daemon already running: PID=" + running.pid());
                return;
            }
            if (!start) {
                if (running == null) {
                    Files.deleteIfExists(pidFile);
                    System.out.println("Relay daemon is not running");
                    return;
                }
                if (!running.destroy() && running.isAlive()) {
                    throw new IOException("Cannot stop relay daemon: PID=" + running.pid());
                }
                try {
                    running.onExit().get(10, TimeUnit.SECONDS);
                } catch (TimeoutException ex) {
                    if (!running.destroyForcibly() && running.isAlive()) {
                        throw new IOException("Cannot terminate relay daemon: PID=" + running.pid());
                    }
                    running.onExit().get(5, TimeUnit.SECONDS);
                }
                Files.deleteIfExists(pidFile);
                System.out.println("Relay daemon stopped: PID=" + running.pid());
                return;
            }
            JsonConfig.load(config);
            Files.deleteIfExists(pidFile);
            String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                    config.toAbsolutePath().toString()));
            if (debug) {
                command.add("--debug");
            }
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toAbsolutePath().toFile()))
                    .start();
            boolean stateCreated = false;
            try {
                process.getOutputStream().close();
                Instant started = process.info().startInstant().orElseThrow(
                        () -> new IOException("Cannot determine daemon process start time"));
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    throw new IOException("Relay daemon exited during startup; check " + logFile.toAbsolutePath());
                }
                try (var writer = Files.newBufferedWriter(pidFile, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    stateCreated = true;
                    writer.write(process.pid() + "\n" + started + "\n");
                }
            } catch (Exception ex) {
                try {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        Instant started = process.info().startInstant().orElseThrow(
                                () -> new IOException("Cannot recover daemon state: PID=" + process.pid()));
                        Files.writeString(pidFile, process.pid() + "\n" + started + "\n",
                                stateCreated ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE);
                        throw new IOException("Daemon still running; state retained: PID=" + process.pid());
                    }
                    if (stateCreated) {
                        Files.deleteIfExists(pidFile);
                    }
                } catch (Exception cleanup) {
                    ex.addSuppressed(cleanup);
                }
                throw ex;
            }
            System.out.println("Relay background process started: PID=" + process.pid()
                    + " log=" + logFile.toAbsolutePath());
        }
    }

    private static boolean sameFile(Path first, Path second) throws IOException {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize())
                || (Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second));
    }

    private static ProcessHandle readProcess(Path pidFile) throws IOException {
        if (!Files.exists(pidFile)) {
            return null;
        }
        long pid;
        Instant started;
        try {
            List<String> state = Files.readAllLines(pidFile);
            if (state.size() != 2) {
                throw new IllegalArgumentException("Expected PID and start time");
            }
            pid = Long.parseLong(state.get(0));
            started = Instant.parse(state.get(1));
            if (pid <= 0) {
                throw new IllegalArgumentException("PID must be positive");
            }
        } catch (RuntimeException ex) {
            throw new IOException("Invalid daemon state file: " + pidFile, ex);
        }
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        if (process == null || !process.isAlive()) {
            return null;
        }
        ProcessHandle.Info info = process.info();
        Instant actualStart = info.startInstant().orElseThrow(
                () -> new IOException("Cannot verify daemon process start time: PID=" + pid));
        if (!started.equals(actualStart)) {
            return null;
        }
        String[] arguments = info.arguments().orElse(null);
        // Windows exposes the executable, but not process arguments through ProcessHandle.
        boolean relay;
        if (arguments != null) {
            relay = Arrays.asList(arguments).contains(Main.class.getName());
        } else {
            String executable = info.command().orElseThrow(
                    () -> new IOException("Cannot verify daemon executable: PID=" + pid));
            String expected = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
            relay = Files.isSameFile(Path.of(executable),
                    Path.of(System.getProperty("java.home"), "bin", expected));
        }
        if (!relay) {
            throw new IOException("State file does not identify a relay daemon: " + pidFile);
        }
        return process;
    }
}
