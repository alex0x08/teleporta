package com.Ox08.teleporta.v3.messages;
/**
 * Системное сообщение шлюза
 *
 * @author Alex Chernyshev <alex3.145@gmail.com>
 *
 * @since 3.0
 */
public class TeleportaSysMessage extends AbstractI18nMessageStore {
    // приватный конструктор 
    private TeleportaSysMessage() {
        super("i18n/teleportaMessages");
    }
    /**
     * Сформировать сообщение для ключа из бандла с подстановкой параметров
     *
     * @param key ключ в бандле
     * @param params параметры
     * @return сформированное сообщние
     */
    private String createMessage(String key, Object... params) {
        final String raw = getMessage(key);
        return raw == null ? key : formatMessage(raw, params);
    }
    public static String of(String template, Object... params) {
        return INSTANCE.createMessage(template, params);
    }

    public static void println(String template,Object... params) {
        System.out.println(of(template,params));
    }
    public static void printErr(String template,Object... params) {
        System.out.println(of(template,params));
    }

    // синглтон
    private static final TeleportaSysMessage INSTANCE = new TeleportaSysMessage();
    public static TeleportaSysMessage instance() {
        return INSTANCE;
    }
}
