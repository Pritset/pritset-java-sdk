package com.pritset.sdk.exception;

import java.io.IOException;

/** Base checked exception for Pritset SDK failures. */
public abstract class PritsetException extends IOException {
    private static final long serialVersionUID = 1L;

    protected PritsetException(String message) {
        super(message);
    }
}
