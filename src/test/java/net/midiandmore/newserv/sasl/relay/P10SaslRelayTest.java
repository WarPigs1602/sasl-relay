package net.midiandmore.newserv.sasl.relay;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class P10SaslRelayTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void debugEnabledLogsMetadataButNeverSecretPayload() throws Exception {
        checkAuthenticateLogging(true);
    }

    @Test
    public void debugDisabledDoesNotLogAuthenticate() throws Exception {
        checkAuthenticateLogging(false);
    }

    private void checkAuthenticateLogging(boolean debug) throws Exception {
        Path config = temporary.newFile("empty config.json").toPath();
        Files.writeString(config, "[]");
        P10SaslRelay relay = new P10SaslRelay(JsonConfig.load(config), debug);
        String secret = "test-only-secret-password";
        String payload = Base64.getEncoder().encodeToString(
                ("\u0000test-user\u0000" + secret).getBytes(StandardCharsets.UTF_8));
        StringWriter wire = new StringWriter();
        Field writer = P10SaslRelay.class.getDeclaredField("writer");
        writer.setAccessible(true);
        writer.set(relay, new PrintWriter(wire, true));
        Method sendAuthenticate = P10SaslRelay.class.getDeclaredMethod(
                "sendAuthenticate", String.class, String.class, String.class);
        sendAuthenticate.setAccessible(true);
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try (PrintStream capture = new PrintStream(logs, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            System.setErr(capture);
            sendAuthenticate.invoke(relay, "test-nick", "test-host", payload);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertEquals("SZ AUTHENTICATE test-nick test-host " + payload + System.lineSeparator(), wire.toString());
        String logged = logs.toString(StandardCharsets.UTF_8);
        assertFalse(logged, logged.contains(secret));
        assertFalse(logged, logged.contains(payload));
        if (debug) {
            assertEquals("[DEBUG] Sending AUTHENTICATE target=test-nick host=test-host payloadLength="
                    + payload.length() + System.lineSeparator(), logged);
        } else {
            assertEquals("", logged);
        }
    }
}
