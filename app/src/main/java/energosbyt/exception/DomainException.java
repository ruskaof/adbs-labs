package energosbyt.exception;

public final class DomainException extends RuntimeException {
    public enum Reason { NOT_FOUND, INVALID_INPUT }

    private final Reason reason;

    private DomainException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static DomainException notFound() {
        return new DomainException(Reason.NOT_FOUND, "Not found");
    }

    public static DomainException invalidInput(String message) {
        return new DomainException(Reason.INVALID_INPUT, message);
    }

    public static DomainException field(String name) {
        return invalidInput("invalid \"" + name + "\" field");
    }

    public static DomainException parameter(String name) {
        return invalidInput("invalid \"" + name + "\" parameter");
    }
}
