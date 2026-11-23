package bankcore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Main {
    private static final Gson JSON = new Gson();
    private static final int MAX_BODY = 8192;

    public static void main(String[] args) throws IOException, SQLException {
        var db = new Database(env("DB_URL"), env("DB_USER"), env("DB_PASSWORD"));
        var auth = new Authentication(db);
        auth.seed(env("ALICE_TOKEN"), env("BOB_TOKEN"), env("AUDITOR_TOKEN"));
        var audit = new AuditService(db);
        var accounts = new AccountService(db, new AccountFactory(amount -> 0, amount -> 25), audit);
        var transfers = new TransferService(db, accounts, audit, List.of(
                movement -> System.out.printf("Committed %s #%d%n", movement.kind(), movement.id())));
        var server = HttpServer.create(new InetSocketAddress(
                Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"))), 64);
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.createContext("/", exchange -> {
            try (exchange) {
                try {
                    route(exchange, auth, accounts, transfers, audit);
                } catch (BankError error) {
                    respond(exchange, error.status(), Map.of("error", error.getMessage()));
                } catch (SQLException error) {
                    System.err.printf("Database error: %s / %d%n", error.getSQLState(), error.getErrorCode());
                    respond(exchange, 503, Map.of("error", "Database operation unavailable"));
                } catch (RuntimeException error) {
                    error.printStackTrace();
                    respond(exchange, 500, Map.of("error", "Unexpected application error"));
                }
            }
        });
        server.start();
    }

    private static void route(HttpExchange exchange, Authentication auth, AccountService accounts,
                              TransferService transfers, AuditService audit)
            throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (method.equals("GET") && path.equals("/health")) {
            respond(exchange, 200, Map.of("status", "up"));
            return;
        }
        Principal actor = auth.authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
        if (method.equals("POST") && path.equals("/accounts")) {
            JsonObject body = body(exchange);
            Account.Kind kind;
            try {
                kind = Account.Kind.valueOf(body.get("kind").getAsString());
            } catch (RuntimeException error) {
                throw new BankError(400, "kind must be SAVINGS or CURRENT");
            }
            respond(exchange, 201, accounts.create(actor, kind));
            return;
        }
        if (method.equals("GET") && path.equals("/audit")) {
            respond(exchange, 200, audit.recent(actor));
            return;
        }
        if (method.equals("POST") && path.equals("/transfers")) {
            JsonObject data = body(exchange);
            movement(exchange, transfers, actor, new Transfer(
                    positive(data, "sourceId"), positive(data, "targetId"), positive(data, "amount")));
            return;
        }
        if (path.matches("/accounts/[1-9][0-9]*(/(history|deposits|withdrawals))?")) {
            String[] parts = path.split("/");
            long id;
            try {
                id = Long.parseLong(parts[2]);
            } catch (NumberFormatException error) {
                throw new BankError(400, "Invalid account ID");
            }
            if (method.equals("GET") && parts.length == 3) {
                respond(exchange, 200, accounts.get(actor, id));
                return;
            }
            if (method.equals("GET") && parts.length == 4 && parts[3].equals("history")) {
                respond(exchange, 200, transfers.history(actor, id));
                return;
            }
            if (method.equals("POST") && parts.length == 4) {
                String action = parts[3];
                if (action.equals("deposits") || action.equals("withdrawals")) {
                    long amount = positive(body(exchange), "amount");
                    movement(exchange, transfers, actor, action.equals("deposits")
                            ? new Deposit(id, amount) : new Withdrawal(id, amount));
                    return;
                }
            }
        }
        throw new BankError(404, "Endpoint unavailable");
    }

    private static void movement(HttpExchange exchange, TransferService transfers,
                                 Principal actor, MoneyCommand command)
            throws IOException, SQLException {
        var result = transfers.execute(actor,
                exchange.getRequestHeaders().getFirst("Idempotency-Key"), command);
        respond(exchange, result.replayed() ? 200 : 201, result);
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new BankError(413, "JSON body too large");
        }
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new BankError(400, "Expected a JSON object");
        }
    }

    private static long positive(JsonObject body, String field) {
        try {
            var value = body.get(field).getAsJsonPrimitive();
            if (!value.isNumber() || !value.getAsString().matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(value.getAsString());
        } catch (RuntimeException error) {
            throw new BankError(400, field + " must be a positive integer");
        }
    }

    private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing setting: " + name);
        }
        return value;
    }
}
