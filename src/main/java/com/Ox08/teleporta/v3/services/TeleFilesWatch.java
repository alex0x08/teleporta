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
    // actual watcher implementation used
    private final FolderWatcher watcher;

    private final Object l = new Object();
    private final Queue<FileEvent> fq = new ConcurrentLinkedQueue<>();
    private final List<FileProcessHandler> ph = new ArrayList<>();
    private volatile boolean running;
    private final boolean useLockFile;
    private final Set<File> processingAfterUnlock;
    private final Map<Path,DirState> lockFiles;

    /**
     * Default constructor
     */
    public TeleFilesWatch(boolean useLockFile) {
        this.useLockFile = useLockFile;
        this.ses = Executors.newScheduledThreadPool(3); //2 tasks + 1 backup
        boolean useDumbWatcher = Boolean.parseBoolean(System.getProperty("dumbWatcher", "false"));
        if (useLockFile) {
            LOG.info("Using lock files, you need to remove 'lock' file manually to trigger uploading");
            lockFiles =new HashMap<>();
            processingAfterUnlock = new LinkedHashSet<>();
            registerUnlockProcessingWatcher();
        } else {
            lockFiles = null;
            processingAfterUnlock = null;
        }
        watcher  = useDumbWatcher ? new DumbFolderWatcher() : new NativeFolderWatcher();
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
                final List<File> files = events.containsKey(e.receiver) ?
                        events.get(e.receiver) : new ArrayList<>();
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
        watcher.start();
    }
    public void unregister(Path dir) {
        // unregister lock, if using 'lock' files
        if (useLockFile) {
            synchronized (l) {
                lockFiles.remove(dir);
            }
        }
        // for 'dumb' watcher, we don't register in WatcherService and use our own list instead.
        watcher.unregister(dir);
    }
    /**
     * Registers new watcher for specified folder
     *
     * @param dir folder that matches portal name
     */
    public void register(Path dir) {
        // register a lock file also, if this mode was enabled
        if (useLockFile && !lockFiles.containsKey(dir)) {
            synchronized (l) {
                 lockFiles.put(dir, DirState.READY);
            }
        }
        watcher.register(dir);
    }
    public interface FileProcessHandler {
        void handle(List<File> files, String receiver);
    }

    /**
     * Internal file event, stored in queue
     */
    static class FileEvent {
        private final File file; // link to file
        private final String receiver;  // receiver portal name
        private FileEvent(File f, String r) {
            this.file = f;
            this.receiver = r;
        }
    }

    /**
     * This is special background processing, dedicated to 'lock' file release
     * When user removes 'lock' file, this starts file uploading
     */
    private void registerUnlockProcessingWatcher() {
        ses.scheduleAtFixedRate(() -> {
            processingAfterUnlock.removeIf(f -> !f.exists());
            for (Map.Entry<Path,DirState> p : lockFiles.entrySet()) {
                // trigger only on 'PROCESSING' state, initiated by lock removal action
                if (p.getValue() != DirState.PROCESSING) {
                    continue;
                }
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(String.format("Processing upload %s",
                            p.getKey().toString()));
                }
                // process only first 1000 files at once
                try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(p.getKey())) {
                    int fileCounter = 0;
                    for (Path e : dirStream) {
                        if (fileCounter > 1000) {
                            break;
                        }
                        final File f = e.toFile();
                        if (processingAfterUnlock.contains(f)) {
                            continue;
                        }
                        if (!isAcceptable(f)) {
                            // ignore non-existent or non-readable
                            // ( possibly deleted before trigger happens )
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
                            processingAfterUnlock.add(f);
                        }
                        fq.add(new FileEvent(f,
                                f.getParentFile().getName()));
                        fileCounter++;
                    }
                    if (fileCounter==0) {
                        lockFiles.put(p.getKey(),DirState.READY);
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

    /**
     * Monitored folder states, used when 'lockFile' mode is enabled
     */
    enum DirState {
        LOCKED, // folder is locked, the 'lock' file has been created
        PROCESSING, // there is background file uploading files to relay, 'lock' file is removed
        READY // folder is ready for process, has no files and no 'lock' file
    }

    public interface FolderWatcher {
        void register(Path dir);
        void unregister(Path dir);
        void start();
    }

    /**
     * This registers the 'dumb' version of folder monitoring, which does not rely on WatcherService
     */
    class NativeFolderWatcher implements FolderWatcher {
        private final WatchService ws;
        private final TeleportaCommons.BidirectionalMap<WatchKey, Path>
                keys = new TeleportaCommons.BidirectionalMap<>();
        NativeFolderWatcher() {
            try {
                ws = FileSystems.getDefault().newWatchService();
            } catch (IOException ex) {
                throw new RuntimeException(String.format("Cannot create WatchService:%s",
                        ex.getMessage()), ex);
            }
        }

        @Override
        public void register(Path dir) {
            // for normal WatcherService
            if (keys.containsValue(dir)) {
                LOG.warning(String.format("cannot register key: %s  - already exists", dir));
                return;
            }
            try {
                final WatchKey key = dir.register(ws, ENTRY_CREATE,
                        ENTRY_DELETE);
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

        @Override
        public void unregister(Path dir) {
            if (!keys.containsValue(dir)) {
                return;
            }
            final WatchKey k = keys.getKey(dir);
            k.cancel();
            synchronized (l) {
                keys.remove(k);
            }
        }
        @Override
        public void start() {
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
                        if (kind == ENTRY_DELETE) {
                            // we don't process any other removals, only lock file!
                            if (!useLockFile) {
                                continue;
                            }
                            final File f = child.toFile();
                            // means 'lock' file removed
                            if ("lock".equals(f.getName())) {
                                lockFiles.put(dir,DirState.PROCESSING);
                                if (LOG.isLoggable(Level.FINE)) {
                                    LOG.fine(String.format("Lock file removed, trigger uploading: %s: %s: %s",
                                            event.kind().name(), child, name));
                                }
                            }
                        } else if (kind == ENTRY_CREATE) {
                            // don't react on events till user removes the 'lock' file
                            if (useLockFile && lockFiles.containsKey(dir)
                                    && lockFiles.get(dir) != DirState.READY) {
                                if (LOG.isLoggable(Level.FINE)) {
                                    LOG.fine(String.format("Folder is locked - no processing event %s: %s: %s",
                                            event.kind().name(), child, name));
                                }
                                continue;
                            }
                            final File f = child.toFile();
                            if (!f.exists() || !f.canRead()) {
                                // ignore non-existent ( possibly deleted before trigger happens )
                                continue;
                            }
                            if (!child.getParent().toFile().exists()) {
                                LOG.warning(String.format("parent deleted: %s",
                                        child.getParent()));
                                // ignore event if parent folder was somehow removed
                                continue;
                            }
                            // when using 'lock' file, on each CREATE event
                            // we check for 'lock' file, if not exist - create one
                            // Till this file
                            if (useLockFile) {
                                final File lock = new File(child.getParent().toFile(),"lock");
                                try {
                                    if (!lock.createNewFile()) {
                                        LOG.warning(String.format("Cannot create lock file: %s",
                                                lock.getAbsolutePath()));
                                    } else {
                                        lockFiles.put(dir,DirState.LOCKED);
                                        if (LOG.isLoggable(Level.FINE)) {
                                            LOG.fine(String.format("Created lock file %s",
                                                    lock.getAbsolutePath()));
                                        }
                                    }
                                } catch (IOException e) {
                                    LOG.log(Level.WARNING, e.getMessage(), e);
                                }
                                continue;
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
    }

    class DumbFolderWatcher implements FolderWatcher {
        private final Set<Path> paths  = new LinkedHashSet<>();
        private final Set<File> processing = new LinkedHashSet<>();
        private final Object l = new Object();
        @Override
        public void register(Path dir) {
            // register (add) path to custom list
            if (!paths.contains(dir)) {
                paths.add(dir);
                LOG.fine(String.format("register: %s", dir));
            }
        }
        @Override
        public void unregister(Path dir) {
            // for 'dumb' watcher, we don't register in WatcherService and use our own list instead.
            paths.remove(dir);
            LOG.fine(String.format("unregister: %s", dir));
        }
        @Override
        public void start() {
            LOG.warning("Using non-native dumb&slow file watcher.");
            ses.scheduleAtFixedRate(() -> {
                // cleanup processing set
                processing.removeIf(f -> !f.exists());
                for (Path p : paths) {
                    // check for 'lock' file, if file deleted - trigger uploading
                    if (useLockFile && lockFiles!=null && lockFiles.containsKey(p)
                            && lockFiles.get(p) == DirState.LOCKED) {
                        final File lock = new File(p.toFile(),"lock");
                        if (!lock.exists()) {
                            lockFiles.put(p,DirState.PROCESSING);
                            if (LOG.isLoggable(Level.FINE)) {
                                LOG.fine(String.format("Lock file removed, trigger uploading: %s",
                                        p));
                            }
                        }
                        continue;
                    }
                    // process only first 1000 files at once
                    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(p)) {
                        int fileCounter = 0;
                        for (Path e : dirStream) {
                            if (fileCounter > 1000) {
                                break;
                            }
                            final File f = e.toFile();
                            if (processing.contains(f)) {
                                continue;
                            }
                            if (useLockFile && lockFiles!=null && lockFiles.containsKey(p)
                                    && lockFiles.get(p) != DirState.READY) {
                                if (LOG.isLoggable(Level.FINE)) {
                                    LOG.fine(String.format("Folder is locked - no processing event %s: %s",
                                            p, f.getName()));
                                }
                                break;
                            }
                            // ignore packed folders - in process
                            if (f.getName().endsWith(".tmpzip")) {
                                continue;
                            }
                            // ignore caught 'folder archive' during creation process
                            if (f.isDirectory()) {
                                final File processingZip = new File(f.getParentFile(),
                                        f.getName()+".tmpzip");
                                if (processingZip.exists()) {
                                    continue;
                                }
                            }
                            if (!isAcceptable(f)) {
                                // ignore non-existent or non-readable
                                // ( possibly deleted before trigger happens )
                                continue;
                            }
                            if ( useLockFile && lockFiles!=null) {
                                if (LOG.isLoggable(Level.FINE)) {
                                    LOG.fine(String.format("Locking on %s",
                                            f.getAbsolutePath()));
                                }
                                final File lock = new File(p.toFile(),"lock");
                                try {
                                    if (!lock.createNewFile()) {
                                        LOG.warning(String.format("Cannot create lock file: %s",
                                                lock.getAbsolutePath()));
                                    } else {
                                        lockFiles.put(p,DirState.LOCKED);
                                        if (LOG.isLoggable(Level.FINE)) {
                                            LOG.fine(String.format("Created lock file %s",
                                                    lock.getAbsolutePath()));
                                        }
                                    }
                                } catch (IOException ee) {
                                    LOG.log(Level.WARNING, ee.getMessage(), ee);
                                }
                                break;
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
    }
}
