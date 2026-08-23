package com.pm.drovi_backend.common;

import lombok.Getter;

/**
 * A failure the caller is allowed to be told about, carrying the code the client will
 * switch on.
 *
 * <p>Anything thrown that is <em>not</em> one of these is treated as a bug and reported as
 * {@link ErrorCode#INTERNAL} with no detail — which is why deliberate failures must use
 * this type rather than a bare {@code RuntimeException}.
 */
@Getter
public class DroviException extends RuntimeException {

    private final ErrorCode errorCode;

    public DroviException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static DroviException notFound(String message) {
        return new DroviException(ErrorCode.NOT_FOUND, message);
    }

    public static DroviException forbidden(String message) {
        return new DroviException(ErrorCode.FORBIDDEN, message);
    }
}
