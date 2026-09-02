package io.github.iaarencibia.notifications.application.port.out;

/**
 * Raised when a notification cannot be stored because its idempotency key belongs to one that
 * already exists.
 *
 * <p>It exists so that the storage technology does not leak inward. The adapter catches whatever
 * its own stack raises for a uniqueness violation and hands this over instead, which keeps the
 * layer above free of Spring and Hibernate -- the same restriction that lets the README claim a
 * move to a Jakarta EE container would touch adapters and nothing else.
 *
 * <p>It also narrows the meaning. A data integrity failure could be any constraint on the table;
 * this names the one the caller has a recovery for, so a use case cannot accidentally treat a
 * genuine defect as a repeated request.
 */
public class IdempotencyKeyAlreadyUsedException extends RuntimeException {

    private final String idempotencyKey;

    /**
     * @param idempotencyKey the key that was already taken
     * @param cause          what the storage adapter caught, kept so a genuine defect misread as
     *                       this one can still be traced
     */
    public IdempotencyKeyAlreadyUsedException(String idempotencyKey, Throwable cause) {
        super("idempotency key already used: " + idempotencyKey, cause);
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * @return the key that was already taken, so the caller can look up what it created
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }
}
