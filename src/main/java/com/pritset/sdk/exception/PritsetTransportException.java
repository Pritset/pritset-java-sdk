package com.pritset.sdk.exception;

/** A transport failure for which no usable Pritset API response was received. */
public final class PritsetTransportException extends PritsetException {
    private static final long serialVersionUID = 1L;

    private final boolean timeout;
    private final boolean interrupted;

    public PritsetTransportException(String message, boolean timeout, boolean interrupted) {
        super(message);
        this.timeout = timeout;
        this.interrupted = interrupted;
    }

    public boolean isTimeout() {
        return timeout;
    }

    public boolean isInterrupted() {
        return interrupted;
    }
}
