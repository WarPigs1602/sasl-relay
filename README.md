# newserv-SASL-relay

> Warning: This project was created as a vibe-coded prototype and may be replaced by a version written by humans.

Standalone P10 SASL relay for newserv. The relay connects directly to the IRC server and handles SASL authentication as a direct relay component.

## Overview

This project accepts SASL authentication requests from a P10-based IRC environment, verifies the supplied credentials against the configured backend, and returns the final authentication result. It is designed as a standalone relay for newserv and keeps the configuration separate from the surrounding server setup.

## Build

### Windows

```powershell
./mvnw.cmd package
```

### Linux

```bash
./mvnw package
```

The generated runnable artifact is:

```text
target/newserv-SASL-relay-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## Run

### Windows

```powershell
java -jar target/newserv-SASL-relay-1.0-SNAPSHOT-jar-with-dependencies.jar config.json
```

### Linux

```bash
java -jar target/newserv-SASL-relay-1.0-SNAPSHOT-jar-with-dependencies.jar config.json
```

### Debug And Daemon Modes

The optional flags can appear before or after the configuration path. Without a path, `config.json` is used. By default the relay runs in the foreground and only reports errors.

Enable diagnostic logging:

```bash
java -jar target/newserv-SASL-relay-1.0-SNAPSHOT-jar-with-dependencies.jar config.json --debug
```

Start a separate background process (Windows and Linux):

```bash
java -jar target/newserv-SASL-relay-1.0-SNAPSHOT-jar-with-dependencies.jar config.json --daemon start --debug --log-file sasl-relay.log
```

`--daemon start` (or `--start`, or the existing `--daemon`) returns after launching the process and prints its PID. A second start reports the existing daemon instead of creating another process. Output is appended to `sasl-relay.log`, or the path supplied with `--log-file`; its parent directory must already exist. Debug logging excludes passwords, authentication hashes and SASL payloads. Protect the log file because it can contain account names and hostnames.

Stop the daemon without looking up its PID (no configuration file is needed):

```bash
java -jar target/newserv-SASL-relay-1.0-SNAPSHOT-jar-with-dependencies.jar --daemon stop
```

`--stop` is the short form. Start and stop use `sasl-relay.pid` in the current working directory. Use the same `--pid-file <path>` with both commands to control an instance from a different directory or to run separate instances. The PID file also records the process start time to avoid stopping an unrelated process after PID reuse. Stale state is cleared automatically; malformed or unverifiable state is reported without stopping a process. Run both commands as the same operating-system user, and keep the state directory protected from other users. On Windows, also use the same Java installation: Java exposes executable identity rather than process arguments there. The companion `.lock` file remains on disk intentionally to serialize concurrent commands; its parent directory must already exist. Do not delete these files while the daemon is running.

Stopping removes the PID file after the process has exited. Stopping an already stopped daemon is harmless. This mode does not install a system service or provide supervision; when using systemd or another service manager, run the relay in foreground mode. JVM options for the child can be supplied through `JAVA_TOOL_OPTIONS`.

Use `--help` to display the available options. `--log-file` requires daemon start; daemon stop only accepts `--pid-file`.

## Protocol

The relay sends P10 authentication responses in the following form:

```text
<source-numeric> AUTHENTICATE <target-nick> <host> <response>
```

The connection is established in the normal server order: PASS, SERVER, service N, local EB, server EB, then EA. Authentication requests are accepted only after the burst is complete. After the password or challenge is validated, the relay performs remoteauth through the configured control service before returning the final SASL result.

## Configuration

The configuration is loaded from `config.json` and contains the database connection details needed for PLAIN and SCRAM authentication.

## Notes

- This project is intentionally kept as a standalone relay implementation.
- This code was created as a vibe-coded prototype and may be replaced by a version written by humans.
- The build is managed with Maven and the packaged jar is produced by the assembly plugin.

## License

This project is licensed under the GNU General Public License v2.0.

See the full text in the `LICENSE` file in this repository.
