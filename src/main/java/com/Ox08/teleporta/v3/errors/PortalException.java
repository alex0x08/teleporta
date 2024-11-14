package com.Ox08.teleporta.v3.errors;
/**
 * Ошибка вебсервиса шлюза
 *
 * @author Alex Chernyshev <alex3.145@gmail.com> *
 * @since 3.0
 */
public class PortalException extends TeleportationException {
    private static final long serialVersionUID = 1L;
    public PortalException(int code, String message, Exception parent) {
        super(code, message, parent);
    }
}
