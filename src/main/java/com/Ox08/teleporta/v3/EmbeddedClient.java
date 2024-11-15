package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.messages.TeleportaSysMessage;
import com.Ox08.teleporta.v3.services.TeleClipboard;
import com.Ox08.teleporta.v3.services.TeleFilesWatch;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.Ox08.teleporta.v3.TeleportaCommons.*;
import static com.Ox08.teleporta.v3.TeleportaCommons.toHex;
import static com.Ox08.teleporta.v3.TeleportaRelay.MAX_FILES_TO_LIST;
/**
 * This is embedded client, used when Relay also act as portal
 * @since 3.1.5
 * @author 0x08
 */
public class EmbeddedClient extends AbstractClient {
    // shared executor, used for parallel files download
    private final ScheduledExecutorService ses = Executors.newScheduledThreadPool(11);
    private TeleClipboard clip;
    final TeleFilesWatch watch;
    final EmbeddedClientRuntimeContext ctx;

    public EmbeddedClient(EmbeddedClientRuntimeContext ctx) {
        // if clipboard monitoring is enabled - start it
        if (ctx.allowClipboard) {
            clip = new TeleClipboard(data -> {
                try {
                    sendClipboard(data);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            });
        }
        // if we allow outgoing files - enable Folder Watch service
        if (ctx.allowOutgoing) {
            this.watch = new TeleFilesWatch(ctx.useLockFile);
        } else {
            this.watch = null;
        }
        this.ctx = ctx;

    }
    public void init()  {
        // create teleporta client's home folder
        final File teleportaHome = checkCreateHomeFolder("teleporta"),
                inputDir = new File(teleportaHome, "from"), // for incoming files
                outputDir = new File(teleportaHome, "to"); // for outgoing files
        checkCreateFolder(inputDir);
        this.ctx.storageDir = teleportaHome;
        // check if we allow outgoing files on this portal
        if (ctx.allowOutgoing) {
            if (outputDir.exists() && outputDir.isDirectory()) {
                if (ctx.clearOutgoing) {
                    deleteRecursive(outputDir, false);
                }
            } else {
                checkCreateFolder(outputDir);
            }
        }
        // do we need to create desktop link?
        final boolean createDesktopLink =
                Boolean.parseBoolean(System.getProperty("createDesktopLink", "true"));
        // no need to create desktop link if we're on headless system
        if (createDesktopLink) {
            createDesktopLink(teleportaHome);
        }

        // register on relay
        if (!register()) {
            // should not happen
            System.exit(1);
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.portalRegistered", ctx.sessionId));
        }
        // load portals list, don't touch watches initially, because output folder should be empty

        // register watchers for each portal
        if (ctx.allowOutgoing && !ctx.relayCtx.portalNames.isEmpty()) {
            for (String n : ctx.relayCtx.portalNames.keySet()) {
                final File f = new File(outputDir, n);
                checkCreateFolder(f);
                watch.register(f.toPath());
            }
        }
        // don't register any watchers, if we're not allow to send anything
        if (ctx.allowOutgoing) {
            // register handler for new file events
            watch.registerHandler((files, receiver_name) -> {
                // pause transfer attempts if there is network error
                // check if portal exists on client side first
                if (!ctx.relayCtx.portalNames.containsKey(receiver_name)) {
                    // unknown portal
                    LOG.warning(TeleportaError.messageFor(0x6108, receiver_name));
                    return;
                }
                // get remote portal's id
                final String id = ctx.relayCtx.portalNames.get(receiver_name);
                try {
                    for (File f : files) {
                        ses.submit(() -> {
                            try {
                                sendFile(f, id);
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
            watch.start();
        }
        // if work with clipboard is enabled - start it
        if (clip != null) {
            clip.start();
        }
        // schedule poll for incoming files
        ses.scheduleAtFixedRate(() -> {
            try {
                final String[] files = getPending();
                if (files != null) {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(TeleportaSysMessage.of("teleporta.system.message.foundPendingFiles", files.length));
                    }
                    // there could be only few files always, no need for dir streaming
                    for (String file : files) {
                        ses.submit(() -> {
                            try {
                                downloadFile(file);
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, e.getMessage(), e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, e.getMessage(), e);
                } else {
                    LOG.warning(e.getMessage());
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    /**
     * Pending files are read directly from relay's folder
     * @return
     * @throws IOException
     */
    public String[] getPending() throws IOException {
        // on relay's side
        final File toFolder = new File(ctx.relayCtx.storageDir, ctx.sessionId);
        final TeleportaRelay.RuntimePortal p = ctx.relayCtx.portals.get(ctx.sessionId);
        // mark 'last seen online'
        p.lastSeen = System.currentTimeMillis();
        // put mark if client must reload portals list
        if (p.needReloadPortals) {
            reloadPortals(true);
        }
        if (p.needLoadClipboard) {
            downloadClipboard();
        }
        // if there were no files for that portal (could be a new one) - respond current
        if (!toFolder.exists() || !toFolder.isDirectory()) {
            return null;
        }
        final List<String> files = new ArrayList<>();
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(toFolder.toPath())) {
            int fileCounter = 1;
            for (Path e : dirStream) {
                if (fileCounter > MAX_FILES_TO_LIST) {
                    break;
                }
                if (!e.toString().endsWith(".dat")) {
                    continue;
                }
                String name = e.getFileName().toString();
                name = name.substring(0, name.length() - 4);
                files.add(name);
                fileCounter++;
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
        // no .dat files, but still settings
        if (files.isEmpty()) {
            return null;
        }
        return files.toArray(new String[0]);
    }

    public void removeExpired(Set<String> expiredPortals) {
        if (!ctx.allowOutgoing) {
            return;
        }
        final File outputDir = new File(ctx.storageDir, "to");
        for (String e:expiredPortals) {
            final Path p = new File(outputDir, e).toPath();
            if (watch.isWatching(p)) {
                watch.unregister(p);
            }
        }
    }
    /**
     * Reload is used to update watchers only
     * @param updateWatcher
     */
    public void reloadPortals(boolean updateWatcher) {
        if (!ctx.allowOutgoing || !updateWatcher) {
            return;
        }
        final File outputDir = new File(ctx.storageDir, "to");
        for (String p : ctx.relayCtx.portalNames.keySet()) {
            final File f = new File(outputDir, p);
            Path pp = f.toPath();
            if (!watch.isWatching(pp)) {
                checkCreateFolder(f);
                watch.register(pp);
            }
        }
    }
    public void sendClipboard(String data) throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.sendingClipboard", data.length()));
        }
        final File cbout = new File(ctx.relayCtx.storageDir, "cb.dat");
        // if it's not exist or not readable - just respond bad request
        if ((!cbout.exists() || !cbout.isFile() || !cbout.canRead()) && !cbout.createNewFile()) {
            // clipboard file not found
            LOG.warning(TeleportaError.messageFor(0x7222,
                    cbout.getAbsolutePath()));
            return;
        }
        final SecretKey key;
        try (OutputStream out = new FileOutputStream(cbout);
             ByteArrayInputStream in = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))) {
            key = tc.generateFileKey();
            final byte[] foreignPk = ctx.relayCtx.relayPair.getPublic().getEncoded();
            final PublicKey pk = tc.restorePublicKey(foreignPk);
            final byte[] enc = tc.encryptKey(key.getEncoded(), pk);
            out.write(enc);
            out.flush();
            tc.encryptData(key, in, out);
            out.flush();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Error encrypting clipboard data
            throw TeleportaError.withError(0x7214, e);
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.clipboardSent", data.length()));
        }
    }
    public void sendFile(File file, String receiverId) throws IOException {
        final TeleportaCommons.RegisteredPortal p = ctx.relayCtx.portals.get(receiverId);
        // build metadata
        final Properties props = new Properties();
        props.setProperty("name", file.getName());
        props.setProperty("from", ctx.sessionId);
        props.setProperty("type", file.isDirectory() ? "folder" : "file");
        final SecretKey key;
        try {
            key = tc.generateFileKey(); // generate session key (AES)
            final byte[] foreignPk = fromHex(p.publicKey);
            final PublicKey pk = tc.restorePublicKey(foreignPk);
            final byte[] enc = tc.encryptKey(key.getEncoded(), pk);
            props.setProperty("fileKey", toHex(enc, 0, 0));
        } catch (Exception e) {
            // Error creating session key
            throw TeleportaError.withError(0x7213, e);
        }
        // generate storage folder
        final File toFolder = new File(ctx.relayCtx.storageDir, receiverId);
        // try to create it if it's not exist
        checkCreateFolder(toFolder);
        // create temp file on relay side
        final File out = new File(toFolder, generateUniqueID() + ".dat");
        // note: if file is directory - we create temp archive and then
        // encrypt & send it instead of each file in folder
        final File packedF = file.isDirectory() ? packFolder(file) : null;
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out));
             //read packed folder instead of file
             FileInputStream in = new FileInputStream(packedF != null ? packedF : file)) {
            zout.putNextEntry(new ZipEntry("meta.properties"));
            props.store(zout, "");
            zout.putNextEntry(new ZipEntry("file.content"));
            tc.encryptData(key, in, zout);
            zout.closeEntry();
            zout.flush();
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaSysMessage.of("teleporta.system.message.fileSent", file.getAbsolutePath()));
            }
        } finally {
            if (file.isFile() && !file.delete()) {
                LOG.warning(TeleportaError.messageFor(0x6106,
                        file.getAbsolutePath()));
            } else if (file.isDirectory()) {
                deleteRecursive(file, true);
            }
            if (packedF != null && !packedF.delete()) {
                LOG.warning(TeleportaError.messageFor(0x6107,
                        packedF.getAbsolutePath()));
            }
        }
    }
    /**
     * This actually copies file with decryption from relay's folder to portal
     * @param fileId
     * @throws IOException
     */
    public void downloadFile(String fileId) throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.downloadingFile", fileId));
        }
        final File toFolder = new File(ctx.relayCtx.storageDir, ctx.sessionId),
                rFile = new File(toFolder, fileId + ".dat");
        if (!rFile.exists() || !rFile.isFile() || !rFile.canRead()) {
            // stored file not found
            LOG.warning(TeleportaError.messageFor(0x6114,
                    rFile.getAbsolutePath()));
            return;
        }
        final Properties props = new Properties();
        // we do unpack & decrypt on the fly, without any temp files
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(rFile))) {
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
                    if (!ctx.relayCtx.portals.containsKey(from)) {
                        // Portal not found
                        LOG.warning(TeleportaError.messageFor(0x6108, from));
                        continue;
                    }
                    // get sender's portal
                    final TeleportaCommons.RegisteredPortal p = ctx.relayCtx.portals.get(from);
                    // build target folder, based on sender's portal name
                    final File f = Paths.get(ctx.storageDir.getAbsolutePath(),
                            "from", p.name).toFile();
                    // create folder tree
                    TeleportaCommons.checkCreateFolder(f);
                    // create target file
                    final File out = new File(f, name);
                    // if it's already exist - delete
                    if (out.exists()) {
                        if (out.isFile() && !out.delete()) {
                            //cannot delete existing file
                            LOG.warning(TeleportaError.messageFor(0x6106,
                                    out.getAbsolutePath()));
                        }
                        if (out.isDirectory()) {
                            deleteRecursive(out, true);
                        }
                        continue;
                    }
                    // decrypt AES session key
                    final byte[] decKey = tc.decryptKey(fromHex(fileKey),
                            ctx.relayCtx.relayPair.getPrivate());
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
                                // Cannot delete temp file
                                LOG.warning(TeleportaError.messageFor(0x6107,
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
                        LOG.fine(TeleportaSysMessage.of("teleporta.system.message.fileDownloaded",
                                out.getAbsolutePath()));
                    }
                }
            }
        } finally {
            if (rFile.delete()) {
                // cannot delete file
                LOG.warning(TeleportaError.messageFor(0x6106,
                        rFile.getAbsolutePath()));
            }
        }
    }
    public void downloadClipboard() throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.downloadingClipboard"));
        }
        final TeleportaRelay.RuntimePortal p = ctx.relayCtx.portals.get(ctx.sessionId);
        final File rFile = new File(ctx.relayCtx.storageDir, "cb.dat");
        if (!rFile.exists() || !rFile.isFile() || !rFile.canRead()) {
            p.needLoadClipboard = false;
            LOG.warning(TeleportaError.messageFor(0x7222,
                    rFile.getAbsolutePath()));
            return;
        }
        try (BufferedInputStream bin = new BufferedInputStream(new FileInputStream(rFile), 4096);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final SecretKeySpec rkey = readSessionKey(bin, false, ctx.relayCtx.relayPair.getPrivate());
            if (rkey == null) {
                return;
            }
            tc.decryptData(rkey, bin, bout);
            clip.setClipboard(bout.toString());
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaSysMessage.of("teleporta.system.message.clipboardUpdated", bout.size()));
            }
        }
    }
    public boolean register() {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("registering on relay");
        }
        String id = generateUniqueID() + "";
        // try name from environment
        String portalName = TeleportaClient.buildPortalName();
        ctx.relayCtx.portals.put(id, new TeleportaRelay.RuntimePortal(portalName,
                toHex(ctx.relayCtx.relayPair.getPublic().getEncoded(), 0, 0)));
        ctx.relayCtx.portalNames.put(portalName, id);
        ctx.sessionId = id;
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("self registered as '%s' on relay :", portalName));
        }
        return true;
    }

    public static class EmbeddedClientRuntimeContext {
        final boolean allowClipboard, // is clipboard allowed?
                allowOutgoing,  // if true - we allow outgoing files from this portal
                clearOutgoing,
                useLockFile;
        private String sessionId; // a session id, generated by relay and used as authentication
        final TeleportaRelay.RelayRuntimeContext relayCtx;
        File storageDir; // selected storage dir
        EmbeddedClientRuntimeContext(
                                     boolean allowClipboard,
                                     boolean allowOutgoing,
                                     boolean useLockFile,
                                     boolean clearOutgoing,
                                     TeleportaRelay.RelayRuntimeContext relayCtx) {

            this.relayCtx = relayCtx;
            this.allowClipboard = allowClipboard;
            this.allowOutgoing = allowOutgoing;
            this.clearOutgoing = clearOutgoing;
            this.useLockFile = useLockFile;
        }
    }
}
