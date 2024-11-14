package com.Ox08.teleporta.v3.services;
import com.Ox08.teleporta.v3.Main;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.messages.TeleportaSysMessage;

import java.awt.*;
import java.awt.datatransfer.*;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * All actions,related to clipboard
 * @since 1.0
 * @author 0x08
 */
public class TeleClipboard {
    private static final int MAX_CLIPBOARD_LEN = 500 * 1024 * 1024; // maximum size for data, to being processed

    private static final Logger LOG = Logger.getLogger("TC");
    private final TeleFlavorListener listener;
    private final Clipboard cb;
    private final ProcessCbUpdateHandler handler;
    public TeleClipboard(ProcessCbUpdateHandler h) {
        listener = new TeleFlavorListener();
        handler = h;
        this.cb = Toolkit.getDefaultToolkit().getSystemClipboard();
    }
    /**
     * Must be synchronized, due to clipboard nature
     * @param data
     *          updated clipboard text data
     */
    public synchronized void setClipboard(String data) {
        if (data==null || data.isEmpty()) {
            // do not react on empty clipboard, ever
            return;
        }
        if (data.length() > MAX_CLIPBOARD_LEN) {
            // clipboard data overload
            LOG.warning(TeleportaError.messageFor(0x7223,data.length(), MAX_CLIPBOARD_LEN));
            return;
        }
        this.listener.ignoreEvent();
        this.cb.setContents(new StringSelection(data), null);
    }
    public synchronized void start() {
        cb.addFlavorListener(listener);
    }
    public synchronized void stop() {
        cb.removeFlavorListener(listener);
    }
    /**
     * Flavor listener, that accepts clipboard changes
     */
    class TeleFlavorListener implements FlavorListener {
        private transient boolean ignoreEvent;
        public void ignoreEvent() {
            this.ignoreEvent = true;
        }
        @Override
        public void flavorsChanged(FlavorEvent e) {
            if (ignoreEvent) {
                ignoreEvent = false;
                return;
            }
            // we support only strings for now
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                try {
                    final String data = cb.getData(DataFlavor.stringFlavor).toString();
                    if (data==null || data.isEmpty()) {
                        return;
                    }
                    if (data.length() > MAX_CLIPBOARD_LEN) {
                        LOG.warning(TeleportaError.messageFor(0x7223,data.length(), MAX_CLIPBOARD_LEN));
                        return;
                    }
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(String.format(TeleportaSysMessage.of(
                                "teleporta.system.message.receivedClipboard", data.length())));
                    }
                    handler.handle(data);

                } catch (Exception ex) {
                    LOG.log(Level.WARNING, ex.getMessage(), ex);
                }
            }
        }
    }
    public interface ProcessCbUpdateHandler {
        void handle(String data);
    }
}
