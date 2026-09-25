package net.midiandmore.newserv.sasl.relay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class P10SaslRelay {
    private static final int SCRAM_ITERATIONS = 4096;
    private static final String SASL_MECHANISMS = "PLAIN SCRAM-SHA-1 SCRAM-SHA-256 SCRAM-SHA-512";
    private final JsonConfig config;
    private final AuthDatabase database;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> remoteAuth = new ConcurrentHashMap<>();
    private final Map<String, String> userNumerics = new ConcurrentHashMap<>();
    private PrintWriter writer;
    private volatile boolean burstComplete;

    P10SaslRelay(JsonConfig config) {
        this.config = config;
        this.database = new AuthDatabase(config);
    }

    void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (Exception ex) {
                System.err.println("P10 relay disconnected: " + ex.getMessage());
            } finally {
                writer = null;
                    burstComplete = false;
                sessions.clear();
                    userNumerics.clear();
                remoteAuth.values().forEach(future -> future.complete(null));
                remoteAuth.clear();
            }
            Thread.sleep(10_000);
        }
    }

    private void connect() throws Exception {
        try (Socket socket = new Socket(config.get("host", "127.0.0.1"), config.intValue("port", 4400));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            send("PASS :%s", config.get("password", ""));
            send("SERVER %s 2 %d %d J10 %s]]] +hs6n :%s", config.get("servername", "sasl"), now(), now(),
                    config.get("numeric", "SZ"), config.get("description", "SASL relay"));
            send("%s N %s 2 %d %s %s +oikr - %s:%s:%s U]AEB %sAAA :%s",
                    numeric(), config.get("nick", "A"), now(), config.get("identd", "sasl"),
                    config.get("servername", "sasl"), config.get("account", "A"), now(), config.get("id", "666"),
                    numeric(), config.get("description", "SASL relay"));
            send("%s EB", numeric());
            String line;
            while ((line = reader.readLine()) != null) {
                if (handlePingPong(line)) {
                    continue;
                }
                if (isEndOfBurst(line)) {
                    burstComplete = true;
                    send("%s EA", numeric());
                    continue;
                }
                rememberUserNumeric(line);
                handle(line);
            }
        }
    }

    private void handle(String line) {
        String normalized = stripTags(line.trim());
        String prefix = "";
        if (normalized.startsWith(":")) {
            int prefixEnd = normalized.indexOf(' ');
            if (prefixEnd < 0) {
                return;
            }
            prefix = normalized.substring(1, prefixEnd);
            normalized = normalized.substring(prefixEnd + 1).trim();
        }
        String[] tokens = normalized.split(" ", 5);
        if (line.contains("REMOTEAUTH")) {
            completeRemoteAuth(line);
            return;
        }
        if (!burstComplete) {
            return;
        }

        String targetNick = null;
        String host = null;
        String payload = null;

        if (tokens.length >= 4 && "AUTHENTICATE".equalsIgnoreCase(tokens[0])) {
            targetNick = tokens[1];
            host = tokens[2];
            payload = tokens[3];
        } else if (tokens.length >= 5 && "AUTHENTICATE".equalsIgnoreCase(tokens[1])) {
            targetNick = tokens[2];
            host = tokens[3];
            payload = tokens[4];
        } else if (tokens.length >= 3 && "AUTHENTICATE".equalsIgnoreCase(tokens[0])) {
            targetNick = tokens[1];
            payload = tokens[2];
        } else {
            return;
        }

        if (payload == null) {
            return;
        }
        if (payload.startsWith(":")) {
            payload = payload.substring(1);
        }
        if ("*".equals(payload)) {
            sessions.remove(targetNick);
            return;
        }
        if (payload.isEmpty() || "+".equals(payload)) {
            payload = "";
        }
        if (host == null || host.isBlank()) {
            host = targetNick;
        }
        System.err.println("Received AUTHENTICATE target=" + targetNick + " host=" + host + " payloadLength=" + payload.length());
        String finalTargetNick = targetNick;
        String finalHost = host;
        String finalPayload = payload;
        new Thread(() -> processAuthenticate(finalTargetNick, finalHost, finalPayload), "sasl-" + finalTargetNick).start();
    }

    private String stripTags(String line) {
        if (!line.startsWith("@")) {
            return line;
        }
        int tagsEnd = line.indexOf(' ');
        return tagsEnd < 0 ? "" : line.substring(tagsEnd + 1).trim();
    }

    private boolean handlePingPong(String line) {
        String candidate = stripTags(line.trim());
        if (candidate.isEmpty()) {
            return false;
        }
        if (candidate.startsWith(":")) {
            int prefixEnd = candidate.indexOf(' ');
            if (prefixEnd >= 0) {
                candidate = candidate.substring(prefixEnd + 1).trim();
            }
        }
        String[] tokens = candidate.split(" ");
        if (tokens.length == 0) {
            return false;
        }

        int commandIndex = -1;
        for (int index = 0; index < tokens.length; index++) {
            if ("G".equalsIgnoreCase(tokens[index]) || "PING".equalsIgnoreCase(tokens[index])) {
                commandIndex = index;
                break;
            }
        }
        if (commandIndex < 0) {
            return false;
        }

        String target = commandIndex + 1 < tokens.length ? tokens[commandIndex + 1] : config.get("servername", "sasl");
        String payload = commandIndex + 2 < tokens.length ? tokens[commandIndex + 2] : target;
        if (target.startsWith(":")) {
            target = target.substring(1);
        }
        if (payload.startsWith(":")) {
            payload = payload.substring(1);
        }
        if (target.isBlank()) {
            target = config.get("servername", "sasl");
        }
        if (payload.isBlank()) {
            payload = target;
        }

        send("%s Z %s :%s", numeric(), target, payload);
        return true;
    }

    private boolean isEndOfBurst(String line) {
        String[] tokens = line.split(" ");
        return tokens.length >= 2 && "EB".equalsIgnoreCase(tokens[1]);
    }

    private void rememberUserNumeric(String line) {
        String normalized = stripTags(line.trim());
        String[] tokens = normalized.split(" ");
        int commandIndex = -1;
        for (int index = 0; index < tokens.length; index++) {
            if ("N".equalsIgnoreCase(tokens[index])) {
                commandIndex = index;
                break;
            }
        }
        if (commandIndex < 0 || tokens.length <= commandIndex + 1) {
            return;
        }
        String numeric = commandIndex > 0 ? tokens[commandIndex - 1] : tokens[0];
        for (int index = tokens.length - 1; index > commandIndex; index--) {
            if (tokens[index].startsWith(":")) {
                numeric = tokens[index - 1];
                break;
            }
        }
        for (int index = commandIndex + 1; index <= commandIndex + 2 && index < tokens.length; index++) {
            String candidate = tokens[index];
            if (candidate.startsWith(":")) {
                continue;
            }
            if (!candidate.equals(numeric)) {
                userNumerics.put(candidate, numeric);
            }
        }
        if (tokens.length > commandIndex + 2 && tokens[commandIndex + 2].startsWith(":")) {
            userNumerics.put(tokens[commandIndex + 1], numeric);
        }
    }

    private void processAuthenticate(String targetNick, String host, String payload) {
        Session session = sessions.computeIfAbsent(targetNick, ignored -> new Session());
        session.host = host != null && !host.isBlank() ? host : targetNick;
        try {
            if (session.mechanism == null) {
                start(session, targetNick, payload);
                return;
            }
            if (session.mechanism.equalsIgnoreCase(payload) && session.serverFirst == null) {
                System.err.println("Ignoring duplicate mechanism request for " + targetNick + ": " + payload);
                return;
            }
            if ("PLAIN".equals(session.mechanism)) {
                finishPlain(session, targetNick, payload);
            } else {
                finishScram(session, targetNick, payload);
            }
        } catch (Exception ex) {
            sessions.remove(targetNick);
            sendAuthenticate(targetNick, session.host, "*");
            System.err.println("SASL exchange failed for " + targetNick + ": " + ex.getMessage());
        }
    }

    private void start(Session session, String targetNick, String mechanism) {
        if (!mechanism.equalsIgnoreCase("PLAIN") && !mechanism.equalsIgnoreCase("SCRAM-SHA-1")
                && !mechanism.equalsIgnoreCase("SCRAM-SHA-256") && !mechanism.equalsIgnoreCase("SCRAM-SHA-512")) {
            sendAuthenticateMechanisms(targetNick, session.host);
            return;
        }
        session.mechanism = mechanism.toUpperCase();
        sendAuthenticate(targetNick, session.host, "+");
    }

    private void finishPlain(Session session, String targetNick, String payload) {
        String[] values = new String(java.util.Base64.getDecoder().decode(payload), StandardCharsets.UTF_8).split("\u0000", -1);
        if (values.length != 3 || values[1].isBlank() || values[2].isEmpty()) {
            throw new IllegalArgumentException("Invalid PLAIN credentials");
        }
        String authzid = values[0].isBlank() ? targetNick : values[0];
        String username = values[1];
        String host = session.host;
        System.err.println("Sending remoteauth for " + targetNick + " account " + username + " host=" + host);
        RemoteAuthResult result = remoteAuth(targetNick, username, values[2], authzid, username, host);
        if (result != null) {
            sendAuthSuccess(targetNick, session.host, username, result);
            sendAuthenticate(targetNick, session.host, "+");
        } else {
            sendAuthenticate(targetNick, session.host, "F");
        }
        sessions.remove(targetNick);
    }

    private void finishScram(Session session, String targetNick, String payload) throws GeneralSecurityException {
        String message = new String(java.util.Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        if (session.serverFirst == null) {
            if (!message.startsWith("n,,")) {
                throw new IllegalArgumentException("Invalid SCRAM client-first GS2 header");
            }
            Map<String, String> attributes = attributes(message.substring(3));
            session.username = Scram.unescape(required(attributes, "n"));
                session.password = database.password(session.username).orElseThrow(
                    () -> new IllegalArgumentException("No SCRAM password found for account " + session.username));
            session.hash = session.mechanism.endsWith("512") ? "SHA-512" : session.mechanism.endsWith("256") ? "SHA-256" : "SHA-1";
            String nonce = required(attributes, "r");
            byte[] salt = new byte[18];
            random.nextBytes(salt);
            session.nonce = nonce + Scram.base64(randomBytes(18));
            session.clientFirstBare = message.substring(3);
            session.serverFirst = "r=" + session.nonce + ",s=" + Scram.base64(salt) + ",i=" + SCRAM_ITERATIONS;
            System.err.println("SCRAM client-first target=" + targetNick + " account=" + session.username
                    + " hash=" + session.hash);
                String serverFirstPayload = Scram.base64(session.serverFirst.getBytes(StandardCharsets.UTF_8));
                System.err.println("Sending SCRAM server-first target=" + targetNick
                    + " payloadLength=" + serverFirstPayload.length());
                sendAuthenticate(targetNick, session.host, serverFirstPayload);
            return;
        }
        System.err.println("SCRAM client-final target=" + targetNick + " account=" + session.username);
        Map<String, String> attributes = attributes(message);
        String proof = required(attributes, "p");
        if (!session.nonce.equals(required(attributes, "r")) || !"biws".equals(required(attributes, "c"))) {
            throw new IllegalArgumentException("Invalid SCRAM final message");
        }
        String withoutProof = message.substring(0, message.lastIndexOf(",p="));
        String authMessage = session.clientFirstBare + "," + session.serverFirst + "," + withoutProof;
        byte[] salt = java.util.Base64.getDecoder().decode(session.serverFirst.split(",s=")[1].split(",i=")[0]);
        byte[] salted = Scram.saltedPassword(session.password, salt, SCRAM_ITERATIONS, session.hash);
        byte[] clientKey = Scram.hmac(session.hash, salted, "Client Key");
        byte[] storedKey = java.security.MessageDigest.getInstance(session.hash).digest(clientKey);
        byte[] signature = Scram.hmac(session.hash, storedKey, authMessage);
        if (!Scram.equals(Scram.xor(java.util.Base64.getDecoder().decode(proof), signature), clientKey)) {
            throw new IllegalArgumentException("Invalid SCRAM proof");
        }
        byte[] serverKey = Scram.hmac(session.hash, salted, "Server Key");
        sendAuthenticate(targetNick, session.host, Scram.base64(("v=" + Scram.base64(Scram.hmac(session.hash, serverKey, authMessage))).getBytes(StandardCharsets.UTF_8)));
        RemoteAuthResult result = remoteAuth(targetNick, session.username, session.password,
            targetNick, session.username, session.host);
        if (result != null) {
            System.err.println("SCRAM remoteauth succeeded for " + targetNick);
            sendAuthSuccess(targetNick, session.host, session.username, result);
        } else {
            System.err.println("SCRAM remoteauth failed for " + targetNick);
            sendAuthenticate(targetNick, session.host, "F");
        }
        sessions.remove(targetNick);
    }

        private synchronized RemoteAuthResult remoteAuth(String targetNick, String account, String password,
            String authzid, String username, String host) {
        long timestamp = now();
        String requestId = targetNick + "-" + timestamp + "-" + System.nanoTime();
        CompletableFuture<String> response = new CompletableFuture<>();
        remoteAuth.put(requestId, response);
        String hash = md5("%s %s %s".formatted(account.toLowerCase(), password, timestamp));
        String controlNumeric = config.get("controlNumeric", "");
        if (controlNumeric.isBlank()) {
            controlNumeric = userNumerics.get(config.get("controlNick", "N"));
        }
        if (controlNumeric == null || controlNumeric.isBlank()) {
            System.err.println("Cannot send remoteauth: no P10 numeric for control nick "
                    + config.get("controlNick", "N"));
            throw new IllegalStateException("Control nick has no known P10 numeric");
        }
        System.err.println("P10 remoteauth target=" + controlNumeric + " account=" + account + " nick=" + targetNick);
        send("%sAAA P %s :remoteauth %s %s %d %s %s %s", numeric(), controlNumeric,
            account.toLowerCase(), hash, timestamp, authzid, username, host);
        try {
            String result = response.get(2, TimeUnit.SECONDS);
            if (result == null || !result.startsWith("REMOTEAUTH OK")) {
                return null;
            }
            String[] fields = result.substring("REMOTEAUTH OK".length()).trim().split(" ");
            if (fields.length < 3) {
                return null;
            }
            return new RemoteAuthResult(fields[1], fields[2]);
        } catch (Exception ex) {
            return null;
        } finally {
            remoteAuth.remove(requestId);
        }
    }

    private void completeRemoteAuth(String line) {
        String result = line.substring(line.indexOf(':') + 1);
        for (Map.Entry<String, CompletableFuture<String>> entry : remoteAuth.entrySet()) {
            if (!entry.getValue().isDone()) {
                entry.getValue().complete(result);
                return;
            }
        }
    }

    private String md5(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append("%02x".formatted(item & 0xff));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 is unavailable", ex);
        }
    }

    private boolean verify(String username, String password) {
        return database.password(username).map(password::equals).orElse(false);
    }

    private Map<String, String> attributes(String message) {
        Map<String, String> result = new HashMap<>();
        for (String item : message.split(",")) {
            int separator = item.indexOf('=');
            if (separator <= 0 || result.put(item.substring(0, separator), item.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("Invalid SCRAM attributes");
            }
        }
        return result;
    }

    private String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing SCRAM attribute " + name);
        }
        return value;
    }

    private byte[] randomBytes(int length) {
        byte[] result = new byte[length];
        random.nextBytes(result);
        return result;
    }

    private void sendAuthenticate(String targetNick, String host, String payload) {
        System.err.println("Sending AUTHENTICATE target=" + targetNick + " host=" + host + " payloadLength=" + payload.length());
        send("%s AUTHENTICATE %s %s %s", numeric(), targetNick, host, payload);
    }

    private void sendAuthenticateMechanisms(String targetNick, String host) {
        System.err.println("Sending SASL mechanisms target=" + targetNick + " host=" + host);
        send("%s AUTHENTICATE %s %s M :%s", numeric(), targetNick, host, SASL_MECHANISMS);
    }

    private void sendAuthSuccess(String targetNick, String host, String account, RemoteAuthResult result) {
        System.err.println("Sending SASL success target=" + targetNick + " host=" + host + " account=" + account
                + " time=" + result.timestamp() + " id=" + result.id());
        send("%s AUTHENTICATE %s %s S %s %s %s", numeric(), targetNick, host, account,
            result.timestamp(), result.id());
    }

    private void send(String format, Object... args) {
        if (writer != null) {
            writer.println(format.formatted(args));
        }
    }

    private String numeric() {
        return config.get("numeric", "SZ");
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }

    private record RemoteAuthResult(String timestamp, String id) {
    }

    private static final class Session {
        private String mechanism;
        private String username;
        private String password;
        private String hash;
        private String nonce;
        private String clientFirstBare;
        private String serverFirst;
        private String host;
    }
}
