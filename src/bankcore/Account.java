package bankcore;

abstract class Account {
    enum Kind { SAVINGS, CURRENT }

    @FunctionalInterface
    interface FeePolicy {
        long feeFor(long amount);
    }

    private final long id;
    private final long ownerId;
    private final FeePolicy feePolicy;
    private long balance;

    Account(long id, long ownerId, long balance, FeePolicy feePolicy) {
        this.id = id;
        this.ownerId = ownerId;
        this.balance = balance;
        this.feePolicy = feePolicy;
    }

    long id() { return id; }
    long ownerId() { return ownerId; }
    long balance() { return balance; }
    abstract Kind kind();
    abstract long minimumRemainingBalance();

    long transferFee(long amount) {
        return feePolicy.feeFor(amount);
    }

    void credit(long amount) {
        try {
            balance = Math.addExact(balance, amount);
        } catch (ArithmeticException error) {
            throw new BankError(422, "Balance limit exceeded");
        }
    }

    void debit(long amount, long fee) {
        long available = balance - minimumRemainingBalance();
        if (available < amount || available - amount < fee) {
            throw new BankError(422, "Insufficient available funds");
        }
        balance -= amount + fee;
    }

    record View(long id, Kind kind, long balance) {}

    View view() {
        return new View(id, kind(), balance);
    }
}

final class SavingsAccount extends Account {
    SavingsAccount(long id, long ownerId, long balance, FeePolicy feePolicy) {
        super(id, ownerId, balance, feePolicy);
    }

    @Override Kind kind() { return Kind.SAVINGS; }
    @Override long minimumRemainingBalance() { return 100; }
}

final class CurrentAccount extends Account {
    CurrentAccount(long id, long ownerId, long balance, FeePolicy feePolicy) {
        super(id, ownerId, balance, feePolicy);
    }

    @Override Kind kind() { return Kind.CURRENT; }
    @Override long minimumRemainingBalance() { return 0; }
}

final class AccountFactory {
    private final Account.FeePolicy savingsFee;
    private final Account.FeePolicy currentFee;

    AccountFactory(Account.FeePolicy savingsFee, Account.FeePolicy currentFee) {
        this.savingsFee = savingsFee;
        this.currentFee = currentFee;
    }

    Account create(Account.Kind kind, long id, long ownerId, long balance) {
        return switch (kind) {
            case SAVINGS -> new SavingsAccount(id, ownerId, balance, savingsFee);
            case CURRENT -> new CurrentAccount(id, ownerId, balance, currentFee);
        };
    }
}
