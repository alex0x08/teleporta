package com.Ox08.teleporta.v3.errors;
/**
 * Ошибка шлюза режима выполнения
 *
 * @author Alex Chernyshev <alex3.145@gmail.com>
 * @since 1.0
 */
public class TeleportationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int code; // код ошибки ( например 0x008 )

    public TeleportationException(int code, String message, Exception parent) {
        super(message, parent);
        this.code = code;
        fetchTrace(parent);
    }
    final void fetchTrace(Throwable e) {
        if (e != null) {
            if (e instanceof TeleportationException && e.getCause() != null) {
                fetchTrace(e.getCause());
                return;
            }
            setStackTrace(e.getStackTrace());
        }
    }
    public int getCode() {
        return code;
    }
}
