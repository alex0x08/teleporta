package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.messages.TeleportaMessage;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static com.Ox08.teleporta.v3.TeleportaCommons.*;
import static com.Ox08.teleporta.v3.TeleportaRelay.EXT_FILE;

/**
 * This is embedded client, used when Relay also act as portal
 * Note: there is no networking.
 * @since 3.1.5
 * @author 0x08
 */
public class EmbeddedClient extends AbstractClient {
    // shared executor, used for parallel files download
    private final ScheduledExecutorService ses = Executors.newScheduledThreadPool(11);
    private TeleClipboard clip;
    final TeleFilesWatch watch;
    final EmbeddedClientRuntimeContext ctx;
    private boolean wasInitialized;

    EmbeddedClient(EmbeddedClientRuntimeContext ctx) {
        this.ctx = ctx;
        // if clipboard monitoring is enabled - start it
        if (ctx.allowClipboard) {
            clip = new TeleClipboard(data -> {
                try {
                    sendClipboard(data);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            });
        }
        // if we allow outgoing files - enable Folder Watch service
        this.watch = ctx.allowOutgoing ? new TeleFilesWatch(ctx.useLockFile) : null;
    }
    void init()  {
        if (wasInitialized)
            throw TeleportaError.withError(0x7270);

        wasInitialized = true;
        // create teleporta client's home folder
        final File teleportaHome = checkCreateHomeFolder("teleporta"),
                inputDir = new File(teleportaHome, TeleportaMessage.of("teleporta.folder.from")), // for incoming files
                outputDir = new File(teleportaHome, TeleportaMessage.of("teleporta.folder.to")); // for outgoing files
        checkCreateFolder(inputDir);
        this.ctx.storageDir = teleportaHome;
        // check if we allow outgoing files on this portal
        if (ctx.allowOutgoing)
            if (outputDir.exists() && outputDir.isDirectory()) {
                if (ctx.clearOutgoing)
                    deleteRecursive(outputDir, false,null);

            } else {
                checkCreateFolder(outputDir);
            }

        // do we need to create desktop link?
        final boolean createDesktopLink =
                Boolean.parseBoolean(System.getProperty("createDesktopLink", "true"));
        // no need to create desktop link if we're on headless system
        if (createDesktopLink)
            createDesktopLink(teleportaHome);

        // register on relay
        register();
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage
                    .of("teleporta.system.message.portalRegistered", ctx.sessionId));

        // register watchers for each portal
        if (ctx.allowOutgoing && !ctx.relayCtx.portalNames.isEmpty())
            for (String n : ctx.relayCtx.portalNames.keySet()) {
                final File f = new File(outputDir, n);
                checkCreateFolder(f);
                watch.register(f.toPath());
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
                    for (File f : files)
                        ses.submit(() -> {
                            try {
                                sendFile(f, id);
                            } catch (IOException e) {
                                LOG.log(Level.WARNING, e.getMessage(), e);
                            }
                        });
                } catch (Exception e) { // MUST catch *all* exceptions there
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            });
            // start filesystem watcher
            watch.start();
        }
        // if work with clipboard is enabled - start it
        if (clip != null)
            clip.start();

        // schedule poll for incoming files
        ses.scheduleAtFixedRate(() -> {
            try {
                final String[] files = getPending();
                if (files != null) {
                    if (LOG.isLoggable(Level.FINE))
                        LOG.fine(TeleportaMessage
                                .of("teleporta.system.message.foundPendingFiles",
                                files.length));

                    // there could be only few files always, no need for dir streaming
                    for (String file : files)
                        ses.submit(() -> {
                            try {
                                downloadFile(file);
                            } catch (IOException e) {
                                LOG.log(Level.WARNING, e.getMessage(), e);
                            }
                        });

                }
            } catch (IOException e) {
                if (LOG.isLoggable(Level.FINE))
                    LOG.log(Level.FINE, e.getMessage(), e);
                else
                    LOG.warning(e.getMessage());

            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    /**
     * Pending files are read directly from relay's folder
     * @return
     *      array with pending files ids
     * @throws IOException
     *          on i/o errors
     */
    public String[] getPending() throws IOException {
        // on relay's side
        final File toFolder = new File(ctx.relayCtx.storageDir, PK.toExternal(ctx.sessionId));
        final TeleportaRelay.RuntimePortal p = ctx.relayCtx.portals.get(ctx.sessionId);
        // mark 'last seen online'
        p.lastSeen = System.currentTimeMillis();

        // update folder watchers, if we allow outgoing processing
        if (ctx.allowOutgoing && p.needReloadPortals)
            reloadPortals();

        // set updated clipboard data, if required
        if (p.needLoadClipboard)
            downloadClipboard();

        // if there were no files for that portal (could be a new one) - respond current
        if (!toFolder.exists() || !toFolder.isDirectory())
            return null;

        final List<String> files = new ArrayList<>();
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(toFolder.toPath())) {
            int fileCounter = 1;
            for (Path e : dirStream) {
                if (fileCounter > ctx.relayCtx.limits.maxPendingFilesAtOnce)
                    break;

                // process only fully uploaded files
                if (!e.toString().endsWith(EXT_FILE))
                    continue;

                String name = e.getFileName().toString();
                name = name.substring(0, name.length() - 4);
                files.add(name);
                fileCounter++;
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
        // no .dat files, but still settings
        if (files.isEmpty())
            return null;
        return files.toArray(new String[0]);
    }
    /**
     * Remove expired portals
     * @param expiredPortals
     *          set with portals names
     */
    public void removeExpired(Set<String> expiredPortals) {
        if (!ctx.allowOutgoing)
            return;

        final File outputDir = new File(ctx.storageDir,
                TeleportaMessage.of("teleporta.folder.to"));
        for (String e:expiredPortals) {
            final Path p = new File(outputDir, e).toPath();
            if (watch.isWatching(p))
                watch.unregister(p);
        }
    }
    /**
     * In embedded client, this function is used to update watchers only
     */
    public void reloadPortals() {
        final File outputDir = new File(ctx.storageDir,
                TeleportaMessage.of("teleporta.folder.to"));
        for (String p : ctx.relayCtx.portalNames.keySet()) {
            final File f = new File(outputDir, p);
            final Path pp = f.toPath();
            if (!watch.isWatching(pp)) {
                checkCreateFolder(f);
                watch.register(pp);
            }
        }
    }
    /**
     * Publish clipboard update to relay
     * @param data
     *          updated data
     * @throws IOException
     *              on I/O errors
     */
    public void sendClipboard(String data) throws IOException {
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage.of("teleporta.system.message.sendingClipboard",
                    data.length()));

        if (ctx.relayCtx.currentCbFile!=null && !ctx.relayCtx.currentCbFile.delete())
                // cannot delete file
                LOG.warning(TeleportaError.messageFor(0x6106,
                        ctx.relayCtx.currentCbFile.getAbsolutePath()));

        final File cbout = new File(ctx.relayCtx.storageDir,
                String.format("cb_%d_f%s", System.currentTimeMillis(), EXT_FILE));
        // if it's cannot been created - just respond bad request
        if (!cbout.createNewFile()) {
            // clipboard file not found
            LOG.warning(TeleportaError.messageFor(0x7222,
                    cbout.getAbsolutePath()));
            return;
        }
        final SecretKey key;
        try (OutputStream out = Files.newOutputStream(cbout.toPath());
             ByteArrayInputStream in = new ByteArrayInputStream(data
                     .getBytes(StandardCharsets.UTF_8))) {
            // write magic header
            out.write(TELEPORTA_PACKET_HEADER);
            // generate session key (AES)
            key = tc.generateFileKey();
            final byte[] foreignPk = ctx.relayCtx.relayPair.getPublic().getEncoded();
            final PublicKey pk = tc.restorePublicKey(foreignPk);
            // encrypt session key by using relay's public key
            final byte[] enc = tc.encryptKey(key.getEncoded(), pk);
            // write it to underlying stream
            out.write(enc);
            out.flush();
            // encrypt clipboard data
            tc.encryptData(key, in, out);
            out.flush();
            // set uploaded clipboard content as current
            ctx.relayCtx.currentCbFile = cbout;
            // notify all other about clipboard update
            for (String k : ctx.relayCtx.portals.keySet()) {
                // ignore self
                if (k.equals(ctx.sessionId))
                    continue;

                final TeleportaRelay.RuntimePortal p = ctx.relayCtx.portals.get(k);
                p.needLoadClipboard = true; // put mark to update clipboard
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Error encrypting clipboard data
            throw TeleportaError.withError(0x7214, e);
        }
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage.of("teleporta.system.message.clipboardSent",
                    data.length()));

    }
    /**
     * Send file to remote portal
     * @param file
     *          source file
     * @param receiverId
     *          target portal's id
     * @throws IOException
     *          on I/O errors
     */
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
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Error creating session key
            throw TeleportaError.withError(0x7213, e);
        }
        // generate storage folder
        final File toFolder = new File(ctx.relayCtx.storageDir, PK.toExternal(receiverId));
        // try to create it if it's not exist
        checkCreateFolder(toFolder);
        // create temp file on relay side
        final File out = new File(toFolder, String.format("f_%d%s", generateUniqueID(), EXT_FILE));
        try (OutputStream os = Files.newOutputStream(out.toPath());
                ZipOutputStream zout = new ZipOutputStream(os)) {
            // write magic header
            os.write(TELEPORTED_FILE_HEADER);
            zout.putNextEntry(new ZipEntry(ENTRY_META));
            props.store(zout, "");
            zout.putNextEntry(new ZipEntry(ENTRY_DATA));
            if (file.isDirectory())
                tc.encryptFolder(key,file,zout);
            else
                try (FileInputStream in = new FileInputStream(file)){
                    tc.encryptData(key, in, zout);
                }

            zout.closeEntry();
            zout.flush();
            if (LOG.isLoggable(Level.FINE))
                LOG.fine(TeleportaMessage.of("teleporta.system.message.fileSent",
                        file.getAbsolutePath()));

        } finally {
            if (file.isFile() && !file.delete())
                LOG.warning(TeleportaError.messageFor(0x6106,
                        file.getAbsolutePath()));
            else if (file.isDirectory())
                deleteRecursive(file, true,null);

        }
    }
    /**
     * This actually copies file with stream decryption from relay's folder to portal
     * @param fileId
     *          pending file id
     * @throws IOException
     *          on i/o errors
     */
    public void downloadFile(String fileId) throws IOException {
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage.of("teleporta.system.message.downloadingFile", fileId));

        final File toFolder = new File(ctx.relayCtx.storageDir, ctx.sessionId),
                rFile = new File(toFolder, String.format("f_%s%s", fileId, EXT_FILE));
        if (!rFile.exists() || !rFile.isFile() || !rFile.canRead()) {
            // stored file not found
            LOG.warning(TeleportaError.messageFor(0x6114,
                    rFile.getAbsolutePath()));
            return;
        }
        final Properties props = new Properties();
        // we do unpack & decrypt on the fly, without any temp files
        try (InputStream in = Files.newInputStream(rFile.toPath());
             ZipInputStream zin = new ZipInputStream(in)) {
            checkFileHeader(in);
            for (ZipEntry ze; (ze = zin.getNextEntry()) != null; ) {
                if (ze.isDirectory() || ze.getName().isEmpty())
                    continue;

                // note on order:
                // we read zip in exactly same order as
                // we create it, so there is no case when file content
                // will be read *before* metadata
                if (props.isEmpty() && ENTRY_META.equalsIgnoreCase(ze.getName()))
                    props.load(zin);
                else if (ENTRY_DATA.equalsIgnoreCase(ze.getName())) {
                    final String from = PK.fromExternal(props.getProperty("from")), // sender id
                            name = props.getProperty("name"), // original file name
                            type = props.getProperty("type"), // content type (file or folder)
                            fileKey = props.getProperty("fileKey"); // unique file key
                    // check for source portal
                    if (!ctx.relayCtx.portals.containsKey(from)) {
                        // Portal not found
                        LOG.warning(TeleportaError.messageFor(0x6108, from));
                        continue;
                    }
                    // get sender's portal
                    final TeleportaCommons.RegisteredPortal p = ctx.relayCtx.portals.get(from);
                    // build target folder, based on sender's portal name
                    final File f = Paths.get(ctx.storageDir.getAbsolutePath(),
                            TeleportaMessage.of("teleporta.folder.from"), p.name).toFile();
                    // create folder tree
                    TeleportaCommons.checkCreateFolder(f);
                    // create target file
                    final File out = new File(f, name);
                    // if it's already exist - delete
                    if (out.exists()) {
                        if (out.isFile() && !out.delete())
                            //cannot delete existing file
                            LOG.warning(TeleportaError.messageFor(0x6106,
                                    out.getAbsolutePath()));

                        if (out.isDirectory())
                            deleteRecursive(out, true,null);

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
                            final File outz = new File(f, name );
                            tc.decryptFolder(rkey, zin, outz,rFile.length());
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
                    if (LOG.isLoggable(Level.FINE))
                        LOG.fine(TeleportaMessage.of("teleporta.system.message.fileDownloaded",
                                out.getAbsolutePath()));

                }
            }
        } finally {
            if (rFile.delete())
                // cannot delete file
                LOG.warning(TeleportaError.messageFor(0x6106,
                        rFile.getAbsolutePath()));

        }
    }
    /**
     * This actually loads clipboard update from relay's context, without networking
     * @throws IOException
     *          on i/o errors
     */
    public void downloadClipboard() throws IOException {
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage.of("teleporta.system.message.downloadingClipboard"));

        final TeleportaRelay.RuntimePortal p = ctx.relayCtx.portals.get(ctx.sessionId);
        // put the mark first, to disallow repeats
        p.needLoadClipboard = false;
        // use shared file, from relay's context
        final File rFile = ctx.relayCtx.currentCbFile;
        if (rFile==null || !rFile.exists() || !rFile.isFile() || !rFile.canRead()) {
            LOG.warning(TeleportaError.messageFor(0x7222,
                    rFile!=null ? rFile.getAbsolutePath() : ""));
            return;
        }
        try (BufferedInputStream bin = new BufferedInputStream(
                Files.newInputStream(rFile.toPath()), 4096);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            // check magic header presents
            checkPacketHeader(bin,false);
            final SecretKeySpec rkey = readSessionKey(bin, false,
                    ctx.relayCtx.relayPair.getPrivate());
            if (rkey == null) {
                LOG.warning(TeleportaError.messageFor(0x7274));
                return;
            }
            tc.decryptData(rkey, bin, bout);
            clip.setClipboard(bout.toString());
            if (LOG.isLoggable(Level.FINE))
                LOG.fine(TeleportaMessage
                        .of("teleporta.system.message.clipboardUpdated", bout.size()));

        }
    }
    /**
     * Register embedded client without networking
     */
    public void register() {
        final String id = PK.generate();
        // try name from environment
        String portalName = TeleportaClient.buildPortalName();
        ctx.relayCtx.portals.put(id, new TeleportaRelay.RuntimePortal(portalName,
                toHex(ctx.relayCtx.relayPair.getPublic().getEncoded(), 0, 0)));
        ctx.relayCtx.portalNames.put(portalName, id);
        ctx.sessionId = PK.toExternal(id); // mimic real client
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage
                    .of("teleporta.system.message.portalRegistered", portalName));

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
