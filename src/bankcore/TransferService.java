package bankcore;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

sealed interface MoneyCommand permits Deposit, Withdrawal, Transfer {
    Long source();
    Long target();
    long amount();
    String kind();
}

record Deposit(Long target, long amount) implements MoneyCommand {
    public Long source() { return null; }
    public String kind() { return "DEPOSIT"; }
}

record Withdrawal(Long source, long amount) implements MoneyCommand {
    public Long target() { return null; }
    public String kind() { return "WITHDRAWAL"; }
}

record Transfer(Long source, Long target, long amount) implements MoneyCommand {
    public String kind() { return "TRANSFER"; }
}

record Movement(long id, String kind, Long source, Long target,
                long amount, long fee, String createdAt) {
    static Movement from(ResultSet row) throws SQLException {
        return new Movement(row.getLong("id"), row.getString("kind"),
                row.getObject("source_id", Long.class), row.getObject("target_id", Long.class),
                row.getLong("amount"), row.getLong("fee"),
                row.getTimestamp("created_at").toInstant().toString());
    }
}

@FunctionalInterface
interface MovementObserver {
    void committed(Movement movement);
}

record TransferService(Database db, AccountService accounts, AuditService audit,
                       List<MovementObserver> observers) {
    TransferService {
        observers = List.copyOf(observers);
    }

    record Result(Movement movement, boolean replayed) {}

    Result execute(Principal actor, String key, MoneyCommand command) throws SQLException {
        actor.require(Principal.Role.CUSTOMER);
        if (key == null || !key.matches("[!-~]{1,128}")) {
            throw new BankError(400, "Idempotency-Key must contain 1–128 printable ASCII characters");
        }
        if (command.amount() <= 0) {
            throw new BankError(422, "Amount must be positive");
        }
        Long sourceId = command.source(), targetId = command.target();
        if (sourceId != null && sourceId.equals(targetId)) {
            throw new BankError(422, "Source and destination must differ");
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);
        String fingerprint = Authentication.digest(
                command.kind() + "|" + sourceId + "|" + targetId + "|" + command.amount());

        Result result = db.transaction(connection -> {
            Long existing = claim(connection, actor.id(), keyBytes, fingerprint);
            if (existing != null) {
                return new Result(load(connection, existing), true);
            }

            Account source = null;
            Account target = null;
            if (sourceId != null && targetId != null && sourceId > targetId) {
                target = accounts.load(connection, targetId, true);
                source = accounts.owned(actor, accounts.load(connection, sourceId, true));
            } else {
                if (sourceId != null) {
                    source = accounts.owned(actor, accounts.load(connection, sourceId, true));
                }
                if (targetId != null) {
                    target = accounts.load(connection, targetId, true);
                }
            }
            long fee = 0;
            if (source != null) {
                fee = target != null ? source.transferFee(command.amount()) : 0;
                source.debit(command.amount(), fee);
                accounts.save(connection, source);
            }
            if (target != null) {
                if (source == null) {
                    accounts.owned(actor, target);
                }
                target.credit(command.amount());
                accounts.save(connection, target);
            }
            long id = Database.insert(connection, """
                INSERT INTO movements (actor_id, kind, source_id, target_id, amount, fee)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actor.id(), command.kind(), sourceId, targetId, command.amount(), fee);
            audit.append(connection, actor.id(), command.kind(), null, id);
            Database.update(connection,
                    "UPDATE requests SET movement_id = ? WHERE actor_id = ? AND request_key = ?",
                    id, actor.id(), keyBytes);
            return new Result(load(connection, id), false);
        });
        if (!result.replayed()) {
            for (MovementObserver observer : observers) {
                try {
                    observer.committed(result.movement());
                } catch (RuntimeException error) {
                    System.err.println("Observer failed for movement " + result.movement().id());
                }
            }
        }
        return result;
    }

    private Long claim(Connection connection, long actorId, byte[] key, String fingerprint)
            throws SQLException {
        Database.update(connection, """
            INSERT INTO requests (actor_id, request_key, fingerprint)
            VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE fingerprint = fingerprint
            """, actorId, key, fingerprint);
        try (var query = Database.query(connection, """
                SELECT fingerprint, movement_id FROM requests
                WHERE actor_id = ? AND request_key = ? FOR UPDATE
                """, actorId, key); var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Missing idempotency record");
            }
            if (!fingerprint.equals(rows.getString("fingerprint"))) {
                throw new BankError(409, "Idempotency key already used for a different request");
            }
            return rows.getObject("movement_id", Long.class);
        }
    }

    private Movement load(Connection connection, long id) throws SQLException {
        try (var query = Database.query(connection, "SELECT * FROM movements WHERE id = ?", id);
             var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new SQLException("Missing movement record");
            }
            return Movement.from(rows);
        }
    }

    List<Movement> history(Principal actor, long accountId) throws SQLException {
        actor.require(Principal.Role.CUSTOMER);
        return db.read(connection -> {
            accounts.owned(actor, accounts.load(connection, accountId, false));
            var result = new ArrayList<Movement>();
            try (var query = Database.query(connection, """
                    SELECT * FROM movements WHERE source_id = ? OR target_id = ?
                    ORDER BY id DESC LIMIT 100
                    """, accountId, accountId); var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(Movement.from(rows));
                }
            }
            return result;
        });
    }
}
