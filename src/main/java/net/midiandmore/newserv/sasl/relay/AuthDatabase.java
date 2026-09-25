package net.midiandmore.newserv.sasl.relay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

final class AuthDatabase {
    private final JsonConfig config;
    private Connection connection;

    AuthDatabase(JsonConfig config) {
        this.config = config;
    }

    synchronized Optional<String> password(String username) {
        try {
            if (connection == null || connection.isClosed()) {
                String ssl = config.get("dbssl", "false");
                String url = "jdbc:postgresql://%s/%s?ssl=%s".formatted(
                        config.get("dbhost", "127.0.0.1"), config.get("db", "chanserv"), ssl);
                connection = DriverManager.getConnection(url, config.get("dbuser", "postgres"), config.get("dbpassword", ""));
            }
            try (var statement = connection.prepareStatement(
                    "SELECT password FROM chanserv.users WHERE LOWER(username) = LOWER(?)")) {
                statement.setString(1, username);
                try (var result = statement.executeQuery()) {
                    return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
                }
            }
        } catch (Exception ex) {
            System.err.println("Database authentication lookup failed: " + ex.getMessage());
            return Optional.empty();
        }
    }
}
