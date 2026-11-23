package bankcore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

record Database(String url, String user, String password) {
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    <T> T read(Work<T> work) throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            return work.run(connection);
        }
    }

    <T> T transaction(Work<T> work) throws SQLException {
        return read(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        });
    }

    static PreparedStatement query(Connection connection, String sql, Object... values)
            throws SQLException {
        return bind(connection.prepareStatement(sql), values);
    }

    static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = query(connection, sql, values)) {
            statement.executeUpdate();
        }
    }

    static long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = bind(
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), values)) {
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Insert returned no ID");
                }
                return keys.getLong(1);
            }
        }
    }

    private static PreparedStatement bind(PreparedStatement statement, Object[] values)
            throws SQLException {
        try {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }
}
