package bankcore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

record AuditService(Database db) {
    record Entry(long id, long actorId, String action, Long accountId,
                 Long movementId, String createdAt) {}

    void append(Connection connection, long actorId, String action,
                Long accountId, Long movementId) throws SQLException {
        Database.update(connection, """
            INSERT INTO audit_log (actor_id, action, account_id, movement_id)
            VALUES (?, ?, ?, ?)
            """, actorId, action, accountId, movementId);
    }

    List<Entry> recent(Principal actor) throws SQLException {
        actor.require(Principal.Role.AUDITOR);
        return db.read(connection -> {
            var entries = new ArrayList<Entry>();
            try (var query = connection.prepareStatement(
                    "SELECT * FROM audit_log ORDER BY id DESC LIMIT 100");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    entries.add(new Entry(rows.getLong("id"), rows.getLong("actor_id"),
                            rows.getString("action"), rows.getObject("account_id", Long.class),
                            rows.getObject("movement_id", Long.class),
                            rows.getTimestamp("created_at").toInstant().toString()));
                }
            }
            return entries;
        });
    }
}
