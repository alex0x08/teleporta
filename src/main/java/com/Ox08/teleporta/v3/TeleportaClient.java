package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.services.TeleClipboard;
import com.Ox08.teleporta.v3.services.TeleFilesWatch;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static com.Ox08.teleporta.v3.TeleportaCommons.*;
/**
 * Teleporta Client
 *
 * @author 0x08
 * @since 1.0
 */
public class TeleportaClient {
    private static final Logger LOG = Logger.getLogger("TC");
    // shared executor, used for parallel files download
    private static final ScheduledExecutorService ses = Executors.newScheduledThreadPool(11);
    private final TeleCrypt tc;
    private TeleClipboard clip;
    private final ClientRuntimeContext ctx;
    private boolean pollRunning,  // if poll enabled and running
            networkError; // if network error raised
    final TeleFilesWatch watch;
    public TeleportaClient(ClientRuntimeContext ctx) throws NoSuchAlgorithmException {
        this.tc = new TeleCrypt();
        if (ctx.allowClipboard) {
            clip = new TeleClipboard(data -> {
                try {
                    sendClipboard(data);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            });
        }
        this.watch = new TeleFilesWatch();
        this.ctx = ctx;
        this.ctx.keyPair = tc.generateKeys();
    }
    public static void main(String[] args) throws Exception {
        setDebugLogging();
        init("http://127.0.0.1:8989/testaaaatest22222222aaaaaaaaaaaaaaaaaaaaaa", true);
    }
    public static void init(String relayUrl, boolean allowClipboard) throws Exception {
        // create teleporta client's home folder
        final File teleportaHome = checkCreateHomeFolder("teleporta"),
                inputDir = new File(teleportaHome, "from"), // for incoming files
                outputDir = new File(teleportaHome, "to"); // for outgoing files
        checkCreateFolder(inputDir);
        if (outputDir.exists() && outputDir.isDirectory()) {
            deleteRecursive(outputDir,false);
        } else {
            checkCreateFolder(outputDir);
        }
        // do we need to create desktop link?
        final boolean createDesktopLink =
                Boolean.parseBoolean(System.getProperty("createDesktopLink", "true"));
        // no need to create desktop link if we're on headless system
        if (createDesktopLink && !GraphicsEnvironment.isHeadless()) {
            try {
                final File homeFolder = javax.swing.filechooser.FileSystemView
                        .getFileSystemView().getHomeDirectory(),
                        desktop = new File(homeFolder, "Desktop");
                final File lnk;
                if (desktop.exists() && desktop.isDirectory()) {
                    lnk = new File(desktop, "Teleporta");
                } else {
                    lnk = new File(homeFolder, "Teleporta");
                }
                if (!lnk.exists()) {
                    Files.createSymbolicLink(lnk.toPath(), teleportaHome.toPath());
                }
            } catch (IOException ex) {
                LOG.log(Level.WARNING, ex.getMessage(), ex);
            }
        }
        // build teleporta client context
        final ClientRuntimeContext ctx = new ClientRuntimeContext(new URL(relayUrl),
                teleportaHome, allowClipboard);
        // build client
        final TeleportaClient c = new TeleportaClient(ctx);
        // register on relay
        c.register();

        if (ctx.sessionId == null || ctx.sessionId.isEmpty()) {
            System.err.printf("Cannot register on relay: %s%n", relayUrl);
            return;
        }
        if (ctx.relayPublicKey == null || ctx.relayPublicKey.isEmpty()) {
            System.err.printf("Empty relay key: %s%n", relayUrl);
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("registered session %s", ctx.sessionId));
        }
        // load portals list, don't touch watches initially, because output folder should be empty
        c.reloadPortals(false);
        // register watchers for each portal
        if (!ctx.portalNames.isEmpty()) {
            for (String n : ctx.portalNames.keySet()) {
                final File f = new File(outputDir, n);
                checkCreateFolder(f);
                c.watch.register(f.toPath());
            }
        }
        // register handler for new file events
        c.watch.registerHandler((files, receiver_name) -> {
            if (!ctx.portalNames.containsKey(receiver_name)) {
                LOG.warning(String.format("unknown portal: %s", receiver_name));
                return;
            }
            final String id = ctx.portalNames.get(receiver_name);
            try {
                for (File f : files) {
                    ses.submit(() -> {
                        try {
                            c.sendFile(f, id);
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, e.getMessage(), e);
                        }
                    });
                }
            } catch (Exception e) { // MUST catch *all* exceptions there
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
        });
        // start filesystem watcher
        c.watch.start();
        // if work with clipboard is enabled - start it
        if (c.clip != null) {
            c.clip.start();
        }
        c.pollRunning = true;
        // schedule poll for changes
        ses.scheduleAtFixedRate(() -> {
            if (!c.pollRunning) {
                return;
            }
            try {
                final String[] files = c.getPending();
                c.networkError = false; // first successful request turns this switch off
                if (files != null) {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(String.format("found %d pending files", files.length));
                    }
                    for (String file : files) {
                        ses.submit(() -> {
                            try {
                                c.downloadFile(file);
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, e.getMessage(), e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                // don't log all the time
                if (c.networkError) {
                    return;
                }
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, e.getMessage(), e);
                } else {
                    LOG.warning(e.getMessage());
                }
                c.networkError = true;
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    /**
     * Get pending files
     * @return
     *          array with file ids
     * @throws IOException
     *      on i/o errors
     */
    public String[] getPending() throws IOException {
        final String part = decodeUrl(ctx.relayUrl, "poll");
        final URL u = new URL(ctx.relayUrl, String.format("%s/%s?to=%s&ts=%d",
                ctx.relayUrl.getPath(),
                part,
                ctx.sessionId,
                System.currentTimeMillis()));
        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        int code = http.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                register();
                reloadPortals(true);
            }
            LOG.warning(String.format("incorrect relay response %d ", code));
            return null;
        }
        final Properties props = new Properties();
        try (BufferedInputStream in = new BufferedInputStream(http.getInputStream(), 512);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final byte[] key = new byte[256];
            final int c = in.read(key);
            // note: this is correct case, because we don't respond at all, if there are no pending files or events
            if (c <= 0) {
                http.disconnect();
                return null;
            }
            if (c != 256) {
                LOG.warning("Key corrupted!");
                http.disconnect();
                return null;
            }
            byte[] dec = tc.decryptKey(key, ctx.keyPair.getPrivate());
            final SecretKeySpec rkey = new SecretKeySpec(dec, "AES");
            tc.decryptData(rkey, in, bout);
            try (ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray())) {
                props.load(bin);
            }
        }
        // props.load(http.getInputStream());
        if (props.isEmpty()) {
            return null;
        }
        if (props.containsKey("reloadPortals")) {
            reloadPortals(true);
        }
        if (props.containsKey("updateClipboard")) {
            downloadClipboard();
        }
        if (!props.containsKey("files")) {
            return null;
        }
        return props.getProperty("files").split(",");
    }
    /**
     * Reload portals from relay
     * @param updateWatcher
     *          if true - filesystem watchers will be updated
     * @throws IOException
     *          on i/o errors
     */
    public void reloadPortals(boolean updateWatcher) throws IOException {
        final Properties portals = getPortals();
        if (portals.isEmpty()) {
            return;
        }
        int total = Integer.parseInt(portals.getProperty("total"));
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("found %d portals", total));
        }
        final File outputDir = new File(ctx.storageDir, "to");
        final Set<String> prevPortals = new HashSet<>(ctx.portalNames.keySet());
        ctx.portals.clear();
        ctx.portalNames.clear();
        for (int t = 1; t <= total; t++) {
            final String id = portals.getProperty(String.format("portal.%d.id", t)),
                    name = portals.getProperty(String.format("portal.%d.name", t)),
                    publicKey = portals.getProperty(String.format("portal.%d.publicKey", t));
            ctx.portals.put(id, new TeleportaCommons.RegisteredPortal(name, publicKey));
            ctx.portalNames.put(name, id);
            if (updateWatcher && !prevPortals.contains(name)) {
                final File f = new File(outputDir, name);
                checkCreateFolder(f);
                watch.register(f.toPath());
            }
        }
        if (updateWatcher && !ctx.portalNames.isEmpty()) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("updating watchers for %d portals",
                        ctx.portalNames.size()));
            }
            for (String n : prevPortals) {
                if (!ctx.portalNames.containsKey(n)) {
                    final File f = new File(outputDir, n);
                    watch.unregister(f.toPath());
                    deleteRecursive(f, true);
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(String.format("removed non-existing portal %s ", n));
                    }
                }
            }
        }
    }
    /**
     * Load portals list
     * @return
     *          properties, filled with portals list
     * @throws IOException
     *          on i/o and network errors
     */
    private Properties getPortals() throws IOException {
        final Properties out = new Properties();
        final String part = decodeUrl(ctx.relayUrl, "get-portals");
        final URL u = new URL(ctx.relayUrl, String.format("%s/%s?to=%s&ts=%d",
                ctx.relayUrl.getPath(),
                part,
                ctx.sessionId,
                System.currentTimeMillis()));
        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        final int code = http.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            LOG.warning(String.format("incorrect relay response: %d", code));
            http.disconnect();
            return out;
        }
        try (BufferedInputStream in = new BufferedInputStream(http.getInputStream(), 512);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            byte[] key = new byte[256];
            if (in.read(key) != 256) {
                LOG.warning("Key corrupted!");
                http.disconnect();
                return out;
            }
            byte[] dec = tc.decryptKey(key, ctx.keyPair.getPrivate());
            final SecretKeySpec rkey = new SecretKeySpec(dec, "AES");
            tc.decryptData(rkey, in, bout);
            try (ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray())) {
                out.load(bin);
            }
            return out;
        } finally {
            http.disconnect();
        }
    }
    /**
     * Send clipboard to relay
     * @param data
     *          data from clipboard
     * @throws IOException
     *          on i/o errors
     */
    public void sendClipboard(String data) throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("sending clipboard contents %d ", data.length()));
        }
        final String part = decodeUrl(ctx.relayUrl, "cb-upload");
        final URL u = new URL(ctx.relayUrl, String.format("%s/%s?from=%s",
                ctx.relayUrl.getPath(),
                part,
                ctx.sessionId));
        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        final SecretKey key;
        try (OutputStream out = http.getOutputStream();
             ByteArrayInputStream in = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))) {
            key = tc.generateFileKey();
            final byte[] foreignPk = fromHex(ctx.relayPublicKey);
            final PublicKey pk = tc.restorePublicKey(foreignPk);
            final byte[] enc = tc.encryptKey(key.getEncoded(), pk);
            out.write(enc);
            out.flush();
            tc.encryptData(key, in, out);
            out.flush();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
        // MUST be called, otherwise request will not be executed!
        int code = http.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            LOG.warning(String.format("incorrect relay response: %d", code));
            http.disconnect();
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("clipboard sent: %s ", data.length()));
        }
        http.disconnect();
    }
    /**
     * Send file to relay
     * @param file
     *          source file
     * @param receiverId
     *              remote portal's id
     * @throws IOException
     *          on i/o errors
     */
    public void sendFile(File file, String receiverId) throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("sending file file %s ", file.getAbsolutePath()));
        }
        final String part = decodeUrl(ctx.relayUrl, "file-upload");
        final URL u = new URL(ctx.relayUrl, String.format("%s/%s?from=%s&to=%s",
                ctx.relayUrl.getPath(),
                part,
                ctx.sessionId, receiverId));
        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        // build metadata
        final Properties props = new Properties();
        props.setProperty("name", file.getName());
        props.setProperty("from", ctx.sessionId);
        props.setProperty("type", file.isDirectory() ? "folder" : "file");
        final SecretKey key;
        try {
            key = tc.generateFileKey(); // generate session key (AES)
            final TeleportaCommons.RegisteredPortal p = ctx.portals.get(receiverId);
            final byte[] foreignPk = fromHex(p.publicKey);
            final PublicKey pk = tc.restorePublicKey(foreignPk);
            final byte[] enc = tc.encryptKey(key.getEncoded(), pk);
            props.setProperty("fileKey", toHex(enc, 0, 0));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // note: if file is directory - we create temp archive and then
        // encrypt & send it instead of each file in folder
        final File packedF = file.isDirectory() ? packFolder(file) : null;
        try (ZipOutputStream zout = new ZipOutputStream(http.getOutputStream());
             //read packed folder instead of file
             FileInputStream in = new FileInputStream(packedF != null ? packedF : file)) {
            zout.putNextEntry(new ZipEntry("meta.properties"));
            props.store(zout, "");
            zout.putNextEntry(new ZipEntry("file.content"));
            tc.encryptData(key, in, zout);
            zout.closeEntry();
            zout.flush();

            // MUST be called, otherwise request will not be executed!
            int code = http.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                LOG.warning(String.format("incorrect relay response: %d", code));
                http.disconnect();
                return;
            }
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("file sent: %s ", file.getAbsolutePath()));
            }

        } finally {
            if (file.isFile() && !file.delete()) {
                LOG.warning(String.format("cannot delete file: %s",
                        file.getAbsolutePath()));
            } else if (file.isDirectory()) {
                deleteRecursive(file, true);
            }
            if (packedF != null && !packedF.delete()) {
                LOG.warning(String.format("cannot delete temp file: %s",
                        packedF.getAbsolutePath()));
            }
        }

        http.disconnect();
    }
    /**
     * Download clipboard update
     * @throws IOException
     *          on i/o errors
     */
    public void downloadClipboard() throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("downloading clipboard ");
        }
        final String part = decodeUrl(ctx.relayUrl, "cb-download");
        final URL u = new URL(ctx.relayUrl, String.format("%s/%s?to=%s",
                ctx.relayUrl.getPath(),
                part,
                ctx.sessionId));
        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        int code = http.getResponseCode(); // execute request
        if (code != HttpURLConnection.HTTP_OK) {
            LOG.warning(String.format("incorrect relay response: %d", code));
            http.disconnect();
            return;
        }
        try (BufferedInputStream bin = new BufferedInputStream(http.getInputStream(), 4096);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            byte[] key = new byte[256];
            if (bin.read(key) != 256) {
                LOG.warning("Key corrupted!");
                http.disconnect();
                return;
            }
            final byte[] decKey = tc.decryptKey(key,
                    ctx.keyPair.getPrivate());
            final SecretKeySpec rkey = new SecretKeySpec(decKey, "AES");
            tc.decryptData(rkey, bin, bout);
            clip.setClipboard(bout.toString());
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("clipboard set: %d ", bout.size()));
            }
        }
        http.disconnect();
    }
    /**
     * Download file from relay
     * @param fileId
     *          unique file id (generated on relay)
     * @throws IOException
     *          on I/O errors
     */
    public void downloadFile(String fileId) throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("downloading file %s ", fileId));
        }
        final String part = decodeUrl(ctx.relayUrl, "file-download");
        final URL u = new URL(ctx.relayUrl, String.format("%s/%s?to=%s&file=%s",
                ctx.relayUrl.getPath(),
                part,
                ctx.sessionId, fileId));
        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        int code = http.getResponseCode();
        // check for basic HTTP codes first
        if (code != HttpURLConnection.HTTP_OK) {
            LOG.warning(String.format("incorrect relay response: %d", code));
            http.disconnect();
            return;
        }
        final Properties props = new Properties();
        // we do unpack & decrypt on the fly, without any temp files
        try (ZipInputStream zin = new ZipInputStream(http.getInputStream())) {
            for (ZipEntry ze; (ze = zin.getNextEntry()) != null; ) {
                if (ze.isDirectory() || ze.getName().isEmpty()) {
                    continue;
                }
                // note on order:
                // we read zip in exactly same order as
                // we create it, so there is no case when file content
                // will be read *before* metadata
                if (props.isEmpty() && "meta.properties".equalsIgnoreCase(ze.getName())) {
                    props.load(zin);
                } else if ("file.content".equalsIgnoreCase(ze.getName())) {
                    final String from = props.getProperty("from"), // sender id
                            name = props.getProperty("name"), // original file name
                            type = props.getProperty("type"), // content type (file or folder)
                            fileKey = props.getProperty("fileKey"); // unique file key
                    if (!ctx.portals.containsKey(from)) {
                        LOG.warning(String.format("portal not found : %s", from));
                        continue;
                    }
                    // get sender's portal
                    final TeleportaCommons.RegisteredPortal p = ctx.portals.get(from);
                    // build target folder, based on sender's portal name
                    final File f = Paths.get(ctx.storageDir.getAbsolutePath(),
                            "from", p.name).toFile();
                    // create folder tree
                    TeleportaCommons.checkCreateFolder(f);
                    // create target file
                    final File out = new File(f, name);
                    // if it's already exist - delete
                    if (out.exists() && !out.delete()) {
                        LOG.warning(String.format("cannot delete existing file : %s",
                                out.getAbsolutePath()));
                        continue;
                    }
                    // decrypt AES session key
                    final byte[] decKey = tc.decryptKey(fromHex(fileKey),
                            ctx.keyPair.getPrivate());
                    final SecretKeySpec rkey = new SecretKeySpec(decKey, "AES");
                    // check content type
                    switch (type) {
                        // if its folder
                        case "folder": {
                            // note on extension
                            final File outz = new File(f, name + ".tmpzip");
                            // decrypt data
                            try (FileOutputStream fout = new FileOutputStream(outz)) {
                                tc.decryptData(rkey, zin, fout);
                            }
                            // if successful - unpack it
                            if (outz.length() > 0) {
                                unpackFolder(outz);
                            }
                            // now remove temp file (archive itself)
                            if (!outz.delete()) {
                                LOG.warning(String.format("cannot delete temp file: %s",
                                        outz.getAbsolutePath()));
                            }
                            break;
                        }
                        // if content is file
                        case "file": {
                            // just decrypt to target file
                            try (FileOutputStream fout = new FileOutputStream(out)) {
                                tc.decryptData(rkey, zin, fout);
                            }
                            break;
                        }
                    }
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(String.format("file downloaded: %s ", out.getAbsolutePath()));
                    }
                }
            }
        }
        http.disconnect();
    }
    /**
     * Regsters portal on remote portal
     * @throws IOException
     *      on network errors
     */
    public void register() throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("registering on relay");
        }
        final String part = decodeUrl(ctx.relayUrl, "register");
        // note:  existing path will be overwritten
        final URL u = new URL(ctx.relayUrl, ctx.relayUrl.getPath() + "/" + part);
        // try name from environment
        String portalName = System.getProperty("portalName", null);
        // try hostname
        if (portalName == null || portalName.isEmpty()) {
            portalName = InetAddress.getLocalHost().getHostName();
        }
        // send unnamed
        if (portalName == null || portalName.isEmpty()) {
            portalName = "Unnamed portal";
        }
        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        // build request to relay (yep, it's based on java.util.Properties)
        final Properties props = new Properties();
        props.setProperty("name", portalName);
        props.setProperty("publicKey",
                toHex(ctx.keyPair.getPublic().getEncoded(), 0, 0));
        // respond without encryption - to transfer relay's public key
        try (OutputStream os = http.getOutputStream()) {
            props.store(os, "");
            os.flush();
        }
        // now parse response, this function actually triggers request submission
        int code = http.getResponseCode();
        // check HTTP status
        if (code != HttpURLConnection.HTTP_OK) {
            LOG.warning(String.format("incorrect relay response: %d", code));
            http.disconnect();
            return;
        }
        // parse response from relay (also based on java.util.Properties)
        final Properties resp = new Properties();
        try (InputStream in = http.getInputStream()) {
            resp.load(in);
        }
        // reflect with context
        ctx.sessionId = resp.getProperty("id", null);
        ctx.relayPublicKey = resp.getProperty("publicKey", null);
        http.disconnect();
    }

    /**
     * Unpacks received zip file back to folder with files
     *
     * @param zipfolder
     *          zip archive with folder contents
     */
    private void unpackFolder(final File zipfolder) {
        try (final ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(zipfolder.toPath()))) {
            for (ZipEntry ze; (ze = zipIn.getNextEntry()) != null; ) {
                final Path resolvedPath = zipfolder
                        .getParentFile().toPath().resolve(ze.getName());
                if (ze.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(zipIn, resolvedPath);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Pack folder into single ZIP file
     * @param folder
     *          source folder
     * @return
     *          archived folder
     */
    private File packFolder(File folder) {
        final File out = new File(folder.getParentFile(), folder.getName() + ".tmpzip");
        try (FileOutputStream fout = new FileOutputStream(out);
             final ZipOutputStream zos = new ZipOutputStream(fout)) {
            final Path pp = folder.toPath();
            int c = pp.toString().length();
            try (Stream<Path> entries = Files.walk(pp)
                    .filter(path -> !Files.isDirectory(path))) {
                // folders will be added automatically
                entries.forEach(path -> {
                    final ZipEntry zipEntry = new ZipEntry(
                            (folder.getName()
                                    + '/'
                                    +pp.relativize(path))
                                    // ZIP requires / slash not \
                                    .replaceAll("\\\\","/"));
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }
    /**
     * Teleporta Client's execution context
     */
    public static class ClientRuntimeContext {
        final File storageDir; // selected storage dir
        final URL relayUrl; // current relay url
        final boolean allowClipboard; // is clipboard allowed?
        private String sessionId, // a session id, generated by relay and used as authentication
                relayPublicKey;  // relay's public key
        private final Map<String, TeleportaCommons.RegisteredPortal> portals = new LinkedHashMap<>();
        private final Map<String, String> portalNames = new LinkedHashMap<>();
        KeyPair keyPair;
        ClientRuntimeContext(URL relayUrl, File storageDir, boolean allowClipboard) {
            this.storageDir = storageDir;
            this.relayUrl = relayUrl;
            this.allowClipboard = allowClipboard;
        }
    }
}