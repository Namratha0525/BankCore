package bankcore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Set;

record Principal(long id, Role role) {
    enum Role { CUSTOMER, AUDITOR }

    void require(Role expected) {
        if (role != expected) {
            throw new BankError(403, "Permission denied");
        }
    }
}

record Authentication(Database db) {
    void seed(String alice, String bob, String auditor) throws SQLException {
        if (Set.of(alice, bob, auditor).stream().anyMatch(token -> token.length() < 12)) {
            throw new IllegalArgumentException("Demo tokens must be distinct and at least 12 characters");
        }
        db.transaction(connection -> {
            String sql = """
                INSERT INTO users (id, name, role, token_hash) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """;
            Database.update(connection, sql, 1, "Alice", "CUSTOMER", digest(alice));
            Database.update(connection, sql, 2, "Bob", "CUSTOMER", digest(bob));
            Database.update(connection, sql, 3, "Auditor", "AUDITOR", digest(auditor));
            return null;
        });
    }

    Principal authenticate(String authorization) throws SQLException {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BankError(401, "Bearer token required");
        }
        return db.read(connection -> {
            try (var query = Database.query(connection,
                    "SELECT id, role FROM users WHERE token_hash = ?",
                    digest(authorization.substring(7))); var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BankError(401, "Invalid bearer token");
                }
                return new Principal(rows.getLong("id"),
                        Principal.Role.valueOf(rows.getString("role")));
            }
        });
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
