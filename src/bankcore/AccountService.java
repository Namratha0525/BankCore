package bankcore;

import java.sql.Connection;
import java.sql.SQLException;

record AccountService(Database db, AccountFactory factory, AuditService audit) {
    Account.View create(Principal actor, Account.Kind kind) throws SQLException {
        actor.require(Principal.Role.CUSTOMER);
        return db.transaction(connection -> {
            long id = Database.insert(connection,
                    "INSERT INTO accounts (owner_id, kind) VALUES (?, ?)", actor.id(), kind.name());
            audit.append(connection, actor.id(), "ACCOUNT_CREATED", id, null);
            return factory.create(kind, id, actor.id(), 0).view();
        });
    }

    Account.View get(Principal actor, long id) throws SQLException {
        actor.require(Principal.Role.CUSTOMER);
        return db.read(connection -> owned(actor, load(connection, id, false)).view());
    }

    Account load(Connection connection, long id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM accounts WHERE id = ?" + (lock ? " FOR UPDATE" : "");
        try (var query = Database.query(connection, sql, id); var rows = query.executeQuery()) {
            if (!rows.next()) {
                throw new BankError(404, "Account unavailable");
            }
            return factory.create(Account.Kind.valueOf(rows.getString("kind")),
                    id, rows.getLong("owner_id"), rows.getLong("balance"));
        }
    }

    Account owned(Principal actor, Account account) {
        if (account.ownerId() != actor.id()) {
            throw new BankError(404, "Account unavailable");
        }
        return account;
    }

    void save(Connection connection, Account account) throws SQLException {
        Database.update(connection, "UPDATE accounts SET balance = ? WHERE id = ?",
                account.balance(), account.id());
    }
}
