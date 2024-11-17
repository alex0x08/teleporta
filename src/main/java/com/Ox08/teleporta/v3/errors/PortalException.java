package com.Ox08.teleporta.v3.errors;
/**
 * Portal exception, used as wrapper for all errors, raised in portal mode 
 * 
 * @author Alex Chernyshev <alex3.145@gmail.com> 
 * @since 3.0
 */
public class PortalException extends TeleportationException {
    private static final long serialVersionUID = 1L;
    public PortalException(int code, String message, Exception parent) {
        super(code, message, parent);
    }
}
