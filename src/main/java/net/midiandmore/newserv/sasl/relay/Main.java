package net.midiandmore.newserv.sasl.relay;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path config = Path.of("config.json");
        Path logFile = Path.of("sasl-relay.log");
        Path pidFile = Path.of("sasl-relay.pid");
        boolean configProvided = false;
        boolean logProvided = false;
        boolean pidProvided = false;
        boolean debug = false;
        boolean daemon = false;
        boolean stop = false;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--debug" -> debug = true;
                case "--start" -> daemon = true;
                case "--stop" -> stop = true;
                case "--daemon" -> {
                    if (index + 1 < args.length && "stop".equals(args[index + 1])) {
                        index++;
                        stop = true;
                    } else {
                        daemon = true;
                        if (index + 1 < args.length && "start".equals(args[index + 1])) {
                            index++;
                        }
                    }
                }
                case "--pid-file" -> {
                    if (++index >= args.length || args[index].startsWith("-")) {
                        throw new IllegalArgumentException("--pid-file requires a path");
                    }
                    pidFile = Path.of(args[index]);
                    pidProvided = true;
                }
                case "--log-file" -> {
                    if (++index >= args.length || args[index].startsWith("--")) {
                        throw new IllegalArgumentException("--log-file requires a path");
                    }
                    logFile = Path.of(args[index]);
                    logProvided = true;
                }
                case "--help", "-h" -> {
                    System.out.println("Usage: java -jar <relay.jar> [config.json] [--debug] [--daemon [start|stop]] [--pid-file <path>] [--log-file <path>]");
                    System.out.println("--debug          Enable diagnostic logging (without credentials or SASL payloads)");
                    System.out.println("--daemon [start|stop]  Start (default) or stop the background process");
                    System.out.println("--start / --stop  Short forms for daemon start and stop");
                    System.out.println("--pid-file PATH  Daemon state file (default: sasl-relay.pid)");
                    System.out.println("--log-file PATH  Append daemon output to PATH (default: sasl-relay.log)");
                    return;
                }
                default -> {
                    if (args[index].startsWith("-") || configProvided) {
                        throw new IllegalArgumentException("Unexpected argument: " + args[index]);
                    }
                    config = Path.of(args[index]);
                    configProvided = true;
                }
            }
        }
        if (daemon && stop) {
            throw new IllegalArgumentException("Daemon start and stop cannot be combined");
        }
        if (stop && (debug || logProvided || configProvided)) {
            throw new IllegalArgumentException("Daemon stop only accepts --pid-file");
        }
        if (pidProvided && !daemon && !stop) {
            throw new IllegalArgumentException("--pid-file requires daemon start or stop");
        }
        if (logProvided && !daemon) {
            throw new IllegalArgumentException("--log-file requires --daemon");
        }
        if (daemon || stop) {
            Daemon.manage(daemon, config, logFile, pidFile, debug);
            return;
        }
        new P10SaslRelay(JsonConfig.load(config), debug).run();
    }
}
