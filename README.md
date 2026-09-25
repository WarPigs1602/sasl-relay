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
