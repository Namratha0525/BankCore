package bankcore;

final class BankError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    BankError(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() { return status; }
}
