package com.Ox08.teleporta.v3.services;
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
     */
    public synchronized void setClipboard(String data) {
        if (data==null || data.isEmpty()) {
            return;
        }
        if (data.length() > MAX_CLIPBOARD_LEN) {
            LOG.warning("Clipboard data overload");
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("Setting clipboard %d", data.length()));
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
                        LOG.warning("Clipboard data overload (2)");
                        return;
                    }
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(String.format("Received clipboard %d", data.length()));
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
