package com.Ox08.teleporta.v3.messages;


import com.Ox08.teleporta.v3.errors.PortalException;
import com.Ox08.teleporta.v3.errors.TeleportationException;

import java.lang.reflect.InvocationTargetException;
/**
 * class represents System Error with localized messages and exception handling
 *
 * @author Alex Chernyshev <alex3.145@gmail.com>
 * @since 1.0
 */
public class TeleportaError extends AbstractI18nMessageStore {
    private TeleportaError() {
        super("i18n/teleportaErrors");
    }

    @SuppressWarnings("unchecked")
    private <T extends TeleportationException> T createException(int code, Object... params) {
        return createExceptionImpl((Class<T>) PortalException.class,  code, null, null, params);
    }
    /**
     * создать объект исключения со сформированным сообщением об ошибке
     *
     * @param <T>     тип возвращаемоего исключения
     * @param clazz   класс исключения
     * @param code    код ошибки
     * @param message доп. сообщение
     * @param parent  родительское исключение
     * @param params  параметры
     * @return сформированное исключение
     */
    private <T extends TeleportationException> T createExceptionImpl(
            Class<T> clazz,
            int code,
            String message, Exception parent, Object... params) {
        final String errorMsg = getErrorMessage(code, message, parent, true, params);
        try {

                return clazz.getConstructor(int.class, String.class, Exception.class)
                        .newInstance(code, errorMsg, parent);

        } catch (NoSuchMethodException
                 | SecurityException
                 | InstantiationException
                 | IllegalAccessException
                 | IllegalArgumentException
                 | InvocationTargetException ex) {
            throw new RuntimeException(ex);
        }
    }
    /**
     * сформировать сообщение об ошибке
     *
     * @param code    код ошибки
     * @param message дополнительное сообщение
     * @param parent  исключение
     * @param prefix  использовать ли префикс с кодом ошибки
     * @param params  дополнительные параметры ( для подстановки в шаблон )
     * @return
     */
    private String getErrorMessage(int code,
                                   String message, Exception parent, boolean prefix, Object... params) {
        // пытаемся найти текст ошибки по коду
        String errorMsg = getMessage("teleporta.system.error." + String.format("0x%x", code));
        // если не нашли - формируем 'неизвестную ошибку'
        if (errorMsg == null) {
            errorMsg = getMessage("teleporta.system.error.0x6000");
            code = 0x6000;
        }
        // если нет доп. сообщения
        if (message == null) {
            // если нет исключения
            if (parent == null) {
                // формируем выходное сообщение, подставляем в шаблон переданные параметры
                errorMsg = formatMessage(errorMsg, params);
            } else {
                // если есть исключение - добавляем в набор параметров 
                // сообщение об ошибке первым аргументом
                final Object[] pparams;
                if (parent instanceof TeleportationException
                        && parent.getCause() != null) {
                    pparams = prepareParams(params, getMessage(parent.getCause()));
                } else {
                    pparams = prepareParams(params, getMessage(parent));
                }
                // формируем выходное сообщение
                errorMsg = formatMessage(errorMsg, pparams);
            }
        } else {
            // если есть доп. сообщение
            if (parent == null) {
                // формируем выходное сообщение, используем доп. сообщение как шаблон
                errorMsg = formatMessage(message, params);
            } else {
                // если есть исключение - добавляем в набор параметров 
                // сообщение об ошибке первым аргументом
                final Object[] pparams;
                if (parent instanceof TeleportationException) {
                    pparams = prepareParams(params, getMessage(parent.getCause()));
                } else {
                    pparams = prepareParams(params, getMessage(parent));
                }
                // формируем выходное сообщение
                errorMsg = formatMessage(message, pparams);
            }
        }
        // если используется префикс
        if (prefix) {
            errorMsg = String.format("[%s] %s", String.format("0x%x", code), errorMsg);
        }
        return errorMsg;
    }
    /**
     * Добавление указанного сообщения первым аргументом в указанном массиве
     * параметров. все остальные параметры сдвигаются влево
     *
     * @param params  массив параметров
     * @param message добаляемое сообщение
     * @return новый массив параметров
     */
    private Object[] prepareParams(Object[] params, String message) {
        final Object[] pparams;
        if (params.length > 0) {
            pparams = new Object[params.length + 1];
            pparams[0] = message;
            System.arraycopy(params, 0, pparams, 1, params.length);
        } else {
            pparams = new Object[]{message};
        }
        return pparams;
    }
    /**
     * Get a detail message from an IOException. Most, but not all, instances of
     * IOException provide a non-null result for getLocalizedMessage(). But some
     * instances return null: in these cases, fallover to getMessage(), and if
     * even that is null, return the name of the exception itself.
     *
     * @param e an IOException
     * @return a string to include in a compiler diagnostic
     */
    private static String getMessage(Throwable e) {
        String s = e.getLocalizedMessage();
        if (s != null) {
            return s;
        }
        s = e.getMessage();
        if (s != null) {
            return s;
        }
        return e.toString();
    }
    // синглтон класса
    private static final TeleportaError INSTANCE = new TeleportaError();
    public static TeleportaError instance() {
        return INSTANCE;
    }
    /**
     * получить сформированное сообщение об ошибке
     *
     * @param code   код ошибки
     * @param params параметры
     * @return сформированное сообщение
     */
    public static String messageFor(int code, Object... params) {
        return INSTANCE.getErrorMessage(code, null, null, true, params);
    }
    /**
     * получить сформированное сообщение об ошибке * с настройкой префикса
     *
     * @param code   код ошибки
     * @param prefix использовать ли префикс - true/false
     * @param params параметры
     * @return сформированное сообщение
     */
    public static String messageForPrefix(int code, boolean prefix, Object... params) {
        return INSTANCE.getErrorMessage(code, null, null, prefix, params);
    }

    public static void println(int code,Object... params) {
        System.out.println(messageFor(code,params));
    }
    public static void printErr(int code,Object... params) {
        System.out.println(messageFor(code,params));
    }


    public static TeleportationException withError(int code, Exception parent, Object... params) {
        return INSTANCE.createExceptionImpl(TeleportationException.class,  code, null, parent, params);
    }
    public static TeleportationException withError(int code, Object... params) {
        return INSTANCE.createExceptionImpl(TeleportationException.class,  code, null, null, params);
    }
    public static PortalException portalWithCode(int code, Object... params) {
        return INSTANCE.createException(code, params);
    }
    public static PortalException portalWithError(int code, Exception parent, Object... params) {
        return INSTANCE.createExceptionImpl(PortalException.class,  code, null, parent, params);
    }
}
