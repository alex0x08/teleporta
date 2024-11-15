package com.Ox08.teleporta.v3;

import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.messages.TeleportaSysMessage;
import com.Ox08.teleporta.v3.services.TeleClipboard;
import com.Ox08.teleporta.v3.services.TeleFilesWatch;
import com.Ox08.teleporta.v3.services.TeleLnk;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
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
import static com.Ox08.teleporta.v3.services.TeleFilesWatch.isAcceptable;

/**
 * Teleporta Client
 *
 * @author 0x08
 * @since 1.0
 */
public class TeleportaClient extends AbstractClient{

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
        this.ctx.keyPair = tc.generateKeys();
    }

    public static void main(String[] args) throws Exception {
        setDebugLogging();
        //  System.setProperty("relayKey","/opt/work/tmp/tele.pub");
        System.setProperty("useLockFile", "true");
        System.setProperty("dumbWatcher", "true");
        System.setProperty("allowOutgoing","false");
        init("http://127.0.0.1:8989/testaaaatest22222222aaaaaaaaaaaaaaaaaaaaaa", true,false);
    }
    /**
     * Main function, that starts Teleporta client
     * @param relayUrl
     *              full URL for remote Teleporta relay
     * @param allowClipboard
     *          if true - allow clipboard mode
     * @param clearOutgoing
     *          if true - wipe outgoing folder on start
     * @throws Exception
     */
    public static void init(String relayUrl, boolean allowClipboard,boolean clearOutgoing) throws Exception {
        // remove all spaces
        relayUrl = relayUrl
                .replaceAll("\\s+","");
        // remove dot, if found as last element
        if (relayUrl.endsWith(".")) {
            relayUrl = relayUrl.substring(0,relayUrl.length()-1);
        }
        // create teleporta client's home folder
        final File teleportaHome = checkCreateHomeFolder("teleporta"),
                inputDir = new File(teleportaHome, "from"), // for incoming files
                outputDir = new File(teleportaHome, "to"); // for outgoing files
        checkCreateFolder(inputDir);
        // check if we allow outgoing files on this portal
        final boolean allowOutgoing =
                Boolean.parseBoolean(System.getProperty("allowOutgoing", "true")),
                // check for 'lock' mode
                useLockFile = Boolean.parseBoolean(System.getProperty("useLockFile", "false"));

        if (allowOutgoing) {
            if (outputDir.exists() && outputDir.isDirectory()) {
                if (clearOutgoing) {
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
        if (createDesktopLink ) {
           createDesktopLink(teleportaHome);
        }
        // build teleporta client context
        final ClientRuntimeContext ctx = new ClientRuntimeContext(new URL(relayUrl),
                teleportaHome, allowClipboard,allowOutgoing,useLockFile);
        // build client
        final TeleportaClient c = new TeleportaClient(ctx);
        // register on relay
        if (!c.register()) {
            TeleportaError.printErr(0x6001, relayUrl);
            System.exit(1);
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.portalRegistered", ctx.sessionId));
        }
        // load portals list, don't touch watches initially, because output folder should be empty
        c.reloadPortals(false);
        // register watchers for each portal
        if (ctx.allowOutgoing && !ctx.portalNames.isEmpty()) {

            if (!clearOutgoing) {
                c.sendAllNotDelivered(outputDir,ctx.useLockFile);
            }

            for (String n : ctx.portalNames.keySet()) {
                    final File f = new File(outputDir, n);
                    checkCreateFolder(f);
                    c.watch.register(f.toPath());
                }
        }
        // don't register any watchers, if we're not allow to send anything
        if (ctx.allowOutgoing) {
            // register handler for new file events
            c.watch.registerHandler((files, receiver_name) -> {
                // pause transfer attempts if there is network error
                if(c.networkError) {
                    return;
                }
                // check if portal exists on client side first
                if (!ctx.portalNames.containsKey(receiver_name)) {
                    // unknown portal
                    LOG.warning(TeleportaError.messageFor(0x6108, receiver_name));
                    return;
                }
                // get remote portal's id
                final String id = ctx.portalNames.get(receiver_name);
                try {
                    for (File f : files) {
                        if (c.networkError) {
                            break;
                        }
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
        }
        // if work with clipboard is enabled - start it
        if (c.clip != null) {
            c.clip.start();
        }
        c.pollRunning = true;
        // schedule poll for incoming files
        ses.scheduleAtFixedRate(() -> {
            if (!c.pollRunning) {
                return;
            }
            try {
                final String[] files = c.getPending();
                if (c.networkError) {
                    c.networkError = false; // first successful request turns this switch off
                }
                if (files != null) {
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(TeleportaSysMessage.of("teleporta.system.message.foundPendingFiles", files.length));
                    }
                    // there could be only few files always, no need for dir streaming
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
     *
     * @return array with file ids
     * @throws IOException on i/o errors
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
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaError.messageFor(0x7002, code));
            }
            return null;
        }
        final Properties props = new Properties();
        try (BufferedInputStream in = new BufferedInputStream(http.getInputStream(), 512);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            // note: this is correct case, because we don't respond at all,
            //  if there are no pending files or events
            final SecretKeySpec rkey = readSessionKey(in, true,ctx.keyPair.getPrivate());
            if (rkey == null) {
                http.disconnect();
                return null;
            }
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
     *
     * @param updateWatcher if true - filesystem watchers will be updated
     * @throws IOException on i/o errors
     */
    public void reloadPortals(boolean updateWatcher) throws IOException {
        final Properties portals = getPortals();
        if (portals.isEmpty()) {
            return;
        }
        int total = Integer.parseInt(portals.getProperty("total"));
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.foundPortals", total));
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

            if (ctx.allowOutgoing) {
                if (updateWatcher && !prevPortals.contains(name)) {
                    final File f = new File(outputDir, name);
                    checkCreateFolder(f);
                    watch.register(f.toPath());
                }
            }
        }
        if (ctx.allowOutgoing && updateWatcher && !ctx.portalNames.isEmpty()) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaSysMessage.of("teleporta.system.message.updatingFolderWatchers",
                        ctx.portalNames.size()));
            }
            for (String n : prevPortals) {
                if (!ctx.portalNames.containsKey(n)) {
                    final File f = new File(outputDir, n);
                    watch.unregister(f.toPath());
                    deleteRecursive(f, true);
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(TeleportaSysMessage.of("teleporta.system.message.removedExpiredPortal", n));
                    }
                }
            }
        }
    }

    /**
     * Load portals list
     *
     * @return properties, filled with portals list
     * @throws IOException on i/o and network errors
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
            // incorrect relay response
            LOG.warning(TeleportaError.messageFor(0x7002, code));
            http.disconnect();
            return out;
        }
        try (BufferedInputStream in = new BufferedInputStream(http.getInputStream(), 512);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final SecretKeySpec rkey = readSessionKey(in, false,ctx.keyPair.getPrivate());
            if (rkey == null) {
                http.disconnect();
                return out;
            }
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
     *
     * @param data data from clipboard
     * @throws IOException on i/o errors
     */
    public void sendClipboard(String data) throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.sendingClipboard", data.length()));
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
            // Error encrypting clipboard data
            throw TeleportaError.withError(0x7214,e);

        }
        // MUST be called, otherwise request will not be executed!
        int code = http.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            // incorrect relay response
            LOG.warning(TeleportaError.messageFor(0x7002, code));
            http.disconnect();
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.clipboardSent", data.length()));
        }
        http.disconnect();
    }

    /**
     * Send file to relay
     *
     * @param file       source file
     * @param receiverId remote portal's id
     * @throws IOException on i/o errors
     */
    public void sendFile(File file, String receiverId) throws IOException {
        if (networkError) {
            return;
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.sendingFile", file.getAbsolutePath()));
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
            // Error creating session key
            throw TeleportaError.withError(0x7213,e);
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
                LOG.warning(TeleportaError.messageFor(0x7002, code));
                http.disconnect();
                return;
            }
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
        http.disconnect();
    }

    /**
     * Download clipboard update
     *
     * @throws IOException on i/o errors
     */
    public void downloadClipboard() throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.downloadingClipboard"));
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
            LOG.warning(TeleportaError.messageFor(0x7002, code));
            http.disconnect();
            return;
        }
        try (BufferedInputStream bin = new BufferedInputStream(http.getInputStream(), 4096);
             ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final SecretKeySpec rkey = readSessionKey(bin, false,ctx.keyPair.getPrivate());
            if (rkey == null) {
                http.disconnect();
                return;
            }
            tc.decryptData(rkey, bin, bout);
            clip.setClipboard(bout.toString());
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaSysMessage.of("teleporta.system.message.clipboardUpdated", bout.size()));
            }
        }
        http.disconnect();
    }

    /**
     * Download file from relay
     *
     * @param fileId unique file id (generated on relay)
     * @throws IOException on I/O errors
     */
    public void downloadFile(String fileId) throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.downloadingFile",fileId));
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
            // Unexpected relay response
            LOG.warning(TeleportaError.messageFor(0x7002, code));
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
                        // Portal not found
                        LOG.warning(TeleportaError.messageFor(0x6108, from));
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
        }
        http.disconnect();
    }

    /**
     * Regsters portal on remote portal
     *
     * @throws IOException on network errors
     */
    public boolean register() throws IOException {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("registering on relay");
        }
        final String part = decodeUrl(ctx.relayUrl, "register");
        // note:  existing path will be overwritten
        final URL u = new URL(ctx.relayUrl, ctx.relayUrl.getPath() + "/" + part);
        // try name from environment
        String portalName = buildPortalName();

        final URLConnection con = u.openConnection();
        final HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");
        http.setDoOutput(true);

        // build request to relay (yep, it's based on java.util.Properties)
        final Properties props = new Properties();
        props.setProperty("name", portalName);
        props.setProperty("publicKey",
                toHex(ctx.keyPair.getPublic().getEncoded(), 0, 0));

        boolean privateRelay = false;
        final String relayKey;

        /*
            Check for relay public key.
            If its exists - we try to use it during registration process.
         */
        final String relayPublicKeyFile =
                System.getProperty("relayKey", null);
        if (relayPublicKeyFile != null) {
            final File k = new File(relayPublicKeyFile);
            if (!k.isFile() || !k.canRead()) {
                //Provided relay key file is not readable:
                TeleportaError.printErr(0x606,relayPublicKeyFile);
                return false;
            }
            // stupid check for completely incorrect key file
            if (k.length() < 5 || k.length() > 4096) {
                // Provided relay key file corrupt or empty
                TeleportaError.printErr(0x6005,relayPublicKeyFile);
                return false;
            }
            // try parse it
            relayKey = parseRelayPublicKey(Files.readAllBytes(k.toPath()));

            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaSysMessage.of("teleporta.system.message.parsedRelayKey",relayKey));
            }
            try {
                // decode public key
                final PublicKey relayPublicKey = tc.restorePublicKey(fromHex(relayKey));
                // generate 'hello' message
                final byte[] hello = new byte[16];
                new SecureRandom().nextBytes(hello);
                // encrypt it with relay's public key
                byte[] enc = tc.encryptKey(hello, relayPublicKey);
                // send encrypted hello message during register - relay will try to decrypt it
                // if succeeded - you have correct relay key
                props.setProperty("hello", toHex(enc, 0, 0));
                privateRelay = true;
            } catch (Exception e) {
                throw TeleportaError.withError(0x6004, e);
            }
        } else {
            relayKey = null;
        }
        // respond without encryption - to transfer relay's public key
        try (OutputStream os = http.getOutputStream()) {
            props.store(os, "");
            os.flush();
        }
        // now parse response, this function actually triggers request submission
        int code = http.getResponseCode();
        // check HTTP status
        if (code != HttpURLConnection.HTTP_OK) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaError.messageFor(0x7002,code));
            }
            http.disconnect();
            return false;
        }
        // parse response from relay (also based on java.util.Properties)
        final Properties resp = new Properties();
        try (InputStream in = http.getInputStream()) {
            resp.load(in);
        }
        // load and display 'Message of the day'
        final String motd = resp.getProperty("motd",null);
        if (motd!=null) {
           System.out.println(motd);
        }
        // reflect with context
        ctx.sessionId = resp.getProperty("id", null);
        // if relay is private - just copy already loaded key to context,
        // because private relay will not publish own public key
        if (privateRelay) {
            ctx.relayPublicKey = relayKey;
        } else {
            // for non-private relays - copy key from relay response
            ctx.relayPublicKey = resp.getProperty("publicKey", null);
            if (ctx.relayPublicKey == null || ctx.relayPublicKey.isEmpty()) {
                // relay key is empty message
                TeleportaError.printErr(0x7212,ctx.relayUrl);
                return false;
            }
        }
        // could be a bug there
        if (ctx.sessionId == null || ctx.sessionId.isEmpty()) {
            // got empty session id from relay
            TeleportaError.printErr(0x7211, ctx.relayUrl);
            return false;
        }
        http.disconnect();
        LOG.info(TeleportaSysMessage.of("teleporta.system.message.connectedToRelay"));
        return true;
    }

    public void sendAllNotDelivered(File outputDir, boolean useLockFile) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(TeleportaSysMessage.of("teleporta.system.message.sendNonDeliveredFiles"));
        }
        for (String n : ctx.portalNames.keySet()) {
            // get remote portal's id
            final String id = ctx.portalNames.get(n);
            final File f = new File(outputDir, n);
            if (!f.exists() || !f.isDirectory() || !f.canRead()) {
                continue;
            }
            if (useLockFile) {
                final File lock = new File(f,"lock");
                if (lock.exists()) {
                    continue;
                }
            }
            try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(f.toPath())) {
                for (Path e : dirStream) {
                    final File ff = e.toFile();
                    // ignore packed folders, caught in process
                    if (ff.getName().endsWith(".tmpzip")) {
                        continue;
                    }
                    if (!isAcceptable(ff)) {
                        // ignore non-existent or non-readable
                        // ( possibly deleted before trigger happens )
                        continue;
                    }
                    try {
                        sendFile(ff, id);
                    } catch (Exception ee) {
                        LOG.warning(TeleportaError.messageFor(0x7006,ee));

                    }
                }
            } catch (IOException e) {
                LOG.warning(TeleportaError.messageFor(0x7006,e));
            }
        }
    }




    /**
     * Teleporta Client's execution context
     */
    public static class ClientRuntimeContext {
        final File storageDir; // selected storage dir
        final URL relayUrl; // current relay url
        final boolean allowClipboard, // is clipboard allowed?
                      allowOutgoing,  // if true - we allow outgoing files from this portal
                      useLockFile;
        private String sessionId, // a session id, generated by relay and used as authentication
                relayPublicKey;  // relay's public key
        private final Map<String, TeleportaCommons.RegisteredPortal> portals = new LinkedHashMap<>();
        private final Map<String, String> portalNames = new LinkedHashMap<>();
        KeyPair keyPair; // portal public&private keys

        ClientRuntimeContext(URL relayUrl, File storageDir,
                             boolean allowClipboard,boolean allowOutgoing, boolean useLockFile) {
            this.storageDir = storageDir;
            this.relayUrl = relayUrl;
            this.allowClipboard = allowClipboard;
            this.allowOutgoing = allowOutgoing;
            this.useLockFile = useLockFile;
        }
    }

}
