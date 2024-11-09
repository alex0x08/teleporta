package com.Ox08.teleporta.v3.services;
import com.Ox08.teleporta.v3.TeleportaCommons;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardWatchEventKinds.*;
/**
 * This class makes file system montoring and teleport new files via relay
 *
 * @author Alex Chernyshev <alex3.145@gmail.com>
 * @since 1.0
 */
public class TeleFilesWatch {
    private final static Logger LOG = Logger.getLogger("TC");
    private final ScheduledExecutorService ses;
    private final TeleportaCommons.BidirectionalMap<WatchKey, Path>
            keys;
    private final WatchService ws;
    private final Object l = new Object();
    private final Queue<FileEvent> fq = new ConcurrentLinkedQueue<>();
    private final List<FileProcessHandler> ph = new ArrayList<>();
    private volatile boolean running;
    private final boolean useDumbWatcher;
    private final Set<Path> paths;
    private final Set<File> processing;
    public TeleFilesWatch() {
        this.ses = Executors.newScheduledThreadPool(3); //2 tasks + 1 backup
        useDumbWatcher = Boolean.parseBoolean(System.getProperty("dumbWatcher", "false"));
        if (useDumbWatcher) {
            ws = null;
            keys = null;
            paths = new TreeSet<>();
            processing = new TreeSet<>();
        } else {
            paths = null;
            processing = null;
            try {
                ws = FileSystems.getDefault().newWatchService();
                keys = new TeleportaCommons.BidirectionalMap<>();
            } catch (IOException ex) {
                throw new RuntimeException(String.format("Cannot create WatchService:%s",
                        ex.getMessage()), ex);
            }
        }
    }
    public void registerHandler(FileProcessHandler fph) {
        ph.add(fph);
    }
    public synchronized void start() {
        if (running) {
            LOG.warning("WatchService is already running!");
            return;
        }
        running = true;
        // this task will loop over events queue and make actual teleportation
        ses.scheduleAtFixedRate(() -> {
            if (fq.isEmpty()) {
                return;
            }
            final Map<String, List<File>> events = new HashMap<>();
            for (FileEvent e = fq.poll();
                 e != null; e = fq.poll()) {
                final List<File> files = events.containsKey(e.receiver) ? events.get(e.receiver) : new ArrayList<>();
                if (!e.file.exists()) {
                    continue;
                }
                files.add(e.file);
                events.put(e.receiver, files);
            }
            for (Map.Entry<String, List<File>> e : events.entrySet()) {
                for (FileProcessHandler h : ph) {
                    h.handle(e.getValue(), e.getKey());
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
        // this task uses java's WatchService for fs monitoring
        if (useDumbWatcher) {
            LOG.warning("Using non-native dumb&slow file watcher.");
            registerDumbWatcher();
        } else {
            registerNativeWatcher();
        }
    }
    public void unregister(Path dir) {
        if (useDumbWatcher) {
            paths.remove(dir);
            LOG.fine(String.format("unregister: %s", dir));
            return;
        }
        if (!keys.containsValue(dir)) {
            return;
        }
        final WatchKey k = keys.getKey(dir);
        k.cancel();
        synchronized (l) {
            keys.remove(k);
        }
    }
    /**
     * Registers new watcher for specified folder
     *
     * @param dir folder that matches portal name
     */
    public void register(Path dir) {
        if (useDumbWatcher && !paths.contains(dir)) {
            paths.add(dir);
            LOG.fine(String.format("register: %s", dir));
            return;
        }
        if (keys.containsValue(dir)) {
            LOG.warning(String.format("cannot register key: %s  - already exists", dir));
            return;
        }
        try {
            final WatchKey key = dir.register(ws, ENTRY_CREATE,
                    ENTRY_DELETE, ENTRY_MODIFY);
            if (LOG.isLoggable(Level.FINE)) {
                final Path prev = keys.get(key);
                if (prev == null) {
                    LOG.fine(String.format("register: %s", dir));
                } else if (!dir.equals(prev)) {
                    LOG.fine(String.format("update: %s -> %s", prev, dir));
                }
            }
            synchronized (l) {
                keys.put(key, dir);
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
    public interface FileProcessHandler {
        void handle(List<File> files, String receiver);
    }
    static class FileEvent {
        private final File file;
        private final String receiver;
        private FileEvent(File f, String r) {
            this.file = f;
            this.receiver = r;
        }
    }
    private void registerNativeWatcher() {
        /*
         * Linux's implementation of WatchService based on inotify.
         * In summary a background thread polls inotify plus a socket used for
         * the wakeup mechanism. Requests to add or remove a watch, or close the
         * watch service, cause the thread to wake up and process the request.
         * Events are processed by the thread which causes it to signal/queue
         * the corresponding watch keys.
         */
        // wait for key to be signalled
        // ignore
        // Context for directory entry event is the file name of entry
        // ignore non-existent ( possibly deleted before trigger happens )
        // print out event
        ses.submit(() -> {
            for (; ; ) {
                // wait for key to be signalled
                final WatchKey key;
                try {
                    key = ws.take();
                } catch (InterruptedException x) {
                    // ignore
                    return;
                }
                final Path dir = keys.get(key);
                if (dir == null) {
                    LOG.warning("WatchKey not recognized!");
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    final WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) {
                        continue;
                    }
                    // Context for directory entry event is the file name of entry
                    @SuppressWarnings("unchecked") final WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    final Path name = ev.context(),
                            child = dir.resolve(name);
                    if (kind == ENTRY_CREATE) {
                        final File f = child.toFile();
                        if (!f.exists() || !f.canRead()) {
                            // ignore non-existent ( possibly deleted before trigger happens )
                            continue;
                        }
                        if (!child.getParent().toFile().exists()) {
                            LOG.warning(String.format("parent deleted: %s",
                                    child.getParent()));
                        }
                        // ignore packed folders - in process
                        if (f.getName().endsWith(".tmpzip")) {
                            continue;
                        }
                        // print out event
                        if (LOG.isLoggable(Level.FINE)) {
                            LOG.fine(String.format("%s: %s: %s",
                                    event.kind().name(), child, name));
                        }
                        fq.add(new FileEvent(child.toFile(),
                                child.getParent().toFile().getName()));
                    }
                }
                key.reset();
            }
        });
    }
    private void registerDumbWatcher() {
        ses.scheduleAtFixedRate(() -> {
            processing.removeIf(f -> !f.exists());
            for (Path p : paths) {
                // process only first 1000 files at once
                try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(p)) {
                    int fileCounter = 1;
                    for (Path e : dirStream) {
                        if (fileCounter > 1000) {
                            break;
                        }
                        final File f = e.toFile();
                        if (processing.contains(f)) {
                            continue;
                        }
                        if (!isAcceptable(f)) {
                            // ignore non-existent or non-readable ( possibly deleted before trigger happens )
                            continue;
                        }

                        // ignore packed folders - in process
                        if (f.getName().endsWith(".tmpzip")) {
                            continue;
                        }
                        // print out event
                        if (LOG.isLoggable(Level.FINE)) {
                            LOG.fine(String.format("adding %s", f.getName()));
                        }
                        synchronized (l) {
                            processing.add(f);
                        }
                        fq.add(new FileEvent(f,
                                f.getParentFile().getName()));
                        fileCounter++;
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
    }
    /**
     * Checks if file or directory is acceptable to transfer
     * @param f
     *          target file
     * @return
     */
    public static boolean isAcceptable(File f) {
        // if file does not exist (could be fast deleted) - ignore
        if (!f.exists()) {
            return false;
        }
        // if file is directory and cannot be read
        if (f.isDirectory() && f.canRead()) {
            return true;
        }
        // if file is empty
        if (f.isFile() && f.length() == 0) {
            return false;
        }
        //
        if (f.isFile()) {
            // last but most important check - try to open and read fist byte
            // if all ok - file is ready to transfer
            try (FileInputStream in = new FileInputStream(f)) {
                return in.read() != -1;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;

    }

}
