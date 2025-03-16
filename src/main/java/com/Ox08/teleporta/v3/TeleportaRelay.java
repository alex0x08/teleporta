package com.Ox08.teleporta.v3;

import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.messages.TeleportaMessage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.Ox08.teleporta.v3.TeleCrypt.SESSION_KEY_LEN;
import static com.Ox08.teleporta.v3.TeleportaCommons.*;
import static com.Ox08.teleporta.v3.services.TeleFilesWatch.FILES_BULK_LIMIT;
import static com.Ox08.teleporta.v3.services.TeleFilesWatch.isAcceptable;
/**
 * Teleporta Relay
 *
 * @author 0x08
 * @since 1.0
 */
public class TeleportaRelay {
    static final String EXT_UPLOAD = ".upload", // file being uploaded
                        EXT_FILE =".dat"; // file is stored on relay
    private final static Logger LOG = Logger.getLogger("TC");
    // single thread executor
    private final static ScheduledExecutorService ses = Executors.newScheduledThreadPool(1);

    /**
     * Main function is only used for tests
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        setLogging(true);
        System.setProperty("javax.net.debug", "all");
        System.setProperty("seed", "testaaaatest22222222aaaaaaaaaaaaaaaaaaaaaa");
        System.setProperty("appPort", "8989");
        // System.setProperty("privateRelay","true");
        init(false,false,false);
    }

    /**
     * Initializes Teleporta in relay mode
     *
     * @param allowClipboard
     *          if true - allow clipboard transfer
     * @param clearOutgoing
     *          if true - will remove pending files on start
     * @param relayHasPortal
     *          if true - start embedded portal on relay side
     * @throws Exception on I/O errors
     * 
     */
    public static void init(boolean allowClipboard,
            boolean clearOutgoing,
            boolean relayHasPortal) throws Exception {
        // read port
        int port = Integer.parseInt(System.getProperty("appPort", "0"));
        // get relay home folder
        final File teleportaHome = checkCreateHomeFolder("teleporta-relay");
        if (clearOutgoing) {
            deleteRecursive(teleportaHome, false,null);
        }
        // We need to detect own jar location and pass full path 
        // as global variable to 'RespondSelfHandler'
        final CodeSource codeSource = TeleportaRelay.class
                .getProtectionDomain().getCodeSource();
        final File jarFile;
        try {
            jarFile = new File(codeSource.getLocation().toURI());
        } catch (URISyntaxException ex) {
            TeleportaError.printErr(0x7216, ex);
            System.exit(1);
            return;
        }
        System.setProperty("TELEPORTA_APP_JAR", jarFile.getAbsolutePath());

        // if port is not set - try to get first free port
        if (port==0) {
            port = findFreePort();
            // if free port not found - try default 8989
            if (port == -1)
                port = 8989;

        }
        // create server
        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 50);
        server.setExecutor(Executors.newFixedThreadPool(255));
        final TeleCrypt tc = new TeleCrypt();
        // generate relay key pair
        final KeyPair rkp = tc.generateKeys();
        final boolean privateRelay =
                Boolean.parseBoolean(System.getProperty("privateRelay", "false"));
        // print relay key if 'private mode' enabled, allows user to copy it
        if (privateRelay) {
            TeleportaMessage.println("teleporta.system.message.relayKey");
            printRelayKey(rkp.getPublic().getEncoded());
        }
        final boolean respondVersion =
                Boolean.parseBoolean(System.getProperty("respondVersion", "true"));
        final RelayLimits limits = new RelayLimits();
        // build runtime context for relay itself
        final RelayRuntimeContext rc = new RelayRuntimeContext(limits,
                teleportaHome,
                rkp, privateRelay,allowClipboard,respondVersion);
        final EmbeddedClient ec;
        // check if 'embedded' portal is enabled 
        if (relayHasPortal) {
            final boolean allowOutgoing =
                    Boolean.parseBoolean(System.getProperty("allowOutgoing", "true")),
                    // check for 'lock' mode
                    useLockFile = Boolean.parseBoolean(System.getProperty("useLockFile", "false"));
            // build another context, but for embedded relay
            // this context will be linked with relay context 
            final EmbeddedClient.EmbeddedClientRuntimeContext ectx
                    = new EmbeddedClient.EmbeddedClientRuntimeContext(
                    allowClipboard,allowOutgoing,useLockFile,clearOutgoing,rc);
            ec = new EmbeddedClient(ectx);
            ec.init();
        } else
            ec = null;

        // background task to remove expired portals
        ses.scheduleAtFixedRate(() -> {
           final Set<String> expired= removeExpired(rc);
           // second step - remove expired for embedded portal, if enabled
           if (!expired.isEmpty() && ec!=null)
               ec.removeExpired(expired);

        }, 120, 60, TimeUnit.SECONDS);
        // Use defined or generate seed
        final String seed = System.getProperty("seed", genSeed());
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage.of("teleporta.system.message.seed", seed));

        /*
         * Register handlers for endpoints
         */
        server.createContext(generateUrl(seed, "file-upload"))
                .setHandler(new FileUploadHandler(rc));
        server.createContext(generateUrl(seed, "poll"))
                .setHandler(new FilePendingDownloadsHandler(rc));
        server.createContext(generateUrl(seed, "file-download"))
                .setHandler(new FileDownloadHandler(rc));
        // check if we allow clipboard exchange on relay
        if (rc.allowClipboardTransfer) {
            TeleportaMessage.println("teleporta.system.message.clipboardEnabled");
            server.createContext(generateUrl(seed, "cb-download"))
                .setHandler(new ClipboardDownloadHandler(rc));
            server.createContext(generateUrl(seed, "cb-upload"))
                .setHandler(new ClipboardUploadHandler(rc));
        }
        server.createContext(generateUrl(seed, "register"))
                .setHandler(new RegisterHandler(rc));
        server.createContext(generateUrl(seed, "get-portals"))
                .setHandler(new GetPortalsHandler(rc));
        // check for 'self download' feature
        final boolean selfDownload = Boolean
                .parseBoolean(System.getProperty("selfDownload", "true"));
        // if enabled - attach self download handler
        if (selfDownload) {
            LOG.fine(TeleportaMessage
                    .of("teleporta.system.message.selfDownloadsAllowed"));
            server.createContext("/" + seed)
                    .setHandler(new RespondSelfHandler(rc));
        }
        final String sb = TeleportaMessage.of(rc.privateRelay ?
                "teleporta.system.message.teleportaRelayPrivate" :
                "teleporta.system.message.teleportaRelayPublic") +
                " " +
                TeleportaMessage.of("teleporta.system.message.teleportaRelayStarted",
                        String.format("http://%s:%d/%s.",
                                InetAddress.getLocalHost().getHostName(),
                                port, seed));

        LOG.info(sb);
        // starts Teleporta in relay mode
        server.start();
    }

    /**
     * This handler allows to download Teleporta distribution, generated from runtime.
     */
    static class RespondSelfHandler extends AbstractHandler {
        RespondSelfHandler(RelayRuntimeContext ctx) { super(ctx);}
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            setVersionHeader(httpExchange);
            final String dl = params.isEmpty() ? null : params.get("dl");
            // if there is no 'dl' parameter - respond static html file
            if (dl == null) {
                httpExchange.sendResponseHeaders(200, INDEX.length);
                // respond static page with javascript to detect self domain
                httpExchange.getResponseHeaders().add("Content-Type", "text/html");
                try (OutputStream out = httpExchange.getResponseBody()) {
                    out.write(INDEX);
                    out.flush();
                }
                return;
            }
            // get self domain name
            final String self = params.get("self");
            // get self jar location
            final String jarF = System.getProperty("TELEPORTA_APP_JAR");
            if (jarF == null || jarF.isEmpty()) {
                // self jar not found, TELEPORTA_APP_JAR is null!
                LOG.warning(TeleportaError.messageFor(0x7217));
                respondAndClose(400, httpExchange);
                return;
            }
            final File jarFile = new File(jarF);
            if (!jarFile.exists() || !jarFile.isFile() || !jarFile.canRead()) {
                // self file not found or not readable
                LOG.warning(TeleportaError.messageFor(0x7218, jarFile));
                respondAndClose(400, httpExchange);
                return;
            }
            final Headers headers = httpExchange.getResponseHeaders();
            headers.set("Content-Type", "application/octet-stream");
            // build .zip distribution and respond as stream
            headers.set("Content-Disposition", "attachment;filename=teleporta.zip");
            httpExchange.sendResponseHeaders(200, 0);
            final byte[] buffer = new byte[4096];
            try (ZipOutputStream zout = new ZipOutputStream(httpExchange.getResponseBody());
                 FileInputStream in = new FileInputStream(jarFile)) {
                zout.putNextEntry(new ZipEntry("teleporta/" + jarFile.getName()));
                // write app data
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
                    zout.write(buffer, 0, n);
                zout.closeEntry();
                // if we're able to detect self domain - add connection string
                if (self != null) {
                    zout.putNextEntry(new ZipEntry("teleporta/teleporta.properties"));
                    final Properties p = new Properties();
                    p.setProperty("relayUrl", self);
                    p.store(zout, "Initial Teleporta Server settings");
                    zout.closeEntry();
                    zout.flush();
                }
            }
        }

        /**
         * This is static HTML with simple JS, to detect relay's external URL.
         * In most cases it will be behind some proxy like Nginx, not directly connected, so
         * Teleporta would not know full external URL.
         *
         */
        private static final byte[] INDEX = ("<!DOCTYPE html>\n" +
                "<html>\n" +
                "    <head>\n" +
                "        <meta charset=\"UTF-8\">\n" +
                "        <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    </head>\n" +
                "    <body>\n" +
                "        <script type=\"text/javascript\">\n" +
                "            window.location.href =  window.location.href + '?dl=1&self=' + window.location.href ;\n" +
                "        </script>    \n" +
                "    </body>\n" +
                "</html>\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A handler to respond registered portals
     */
    static class GetPortalsHandler extends AbstractHandler {
        GetPortalsHandler(RelayRuntimeContext rc) {
           super(rc);
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            setVersionHeader(httpExchange);
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }

            if (!params.containsKey("to")) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to = PK.fromExternal(params.get("to"));
            // malformed ID
            if (to==null) {
                respondAndClose(400, httpExchange);
                return;
            }
            // 'to' param is used as session
            if (!rc.portals.containsKey(to)) {
                LOG.warning(TeleportaError.messageFor(0x6108, to));
                return;
            }
            final RuntimePortal p = rc.portals.get(to);
            // reset 'reload' mark
            p.needReloadPortals = false;
            // if there is no portals registered - just respond 200 ok without body
            if (rc.portals.isEmpty()) {
                respondAndClose(200, httpExchange);
                return;
            }
            /*
             * Instead of JSON/XML we use... java.util.Properties!
             * All hail to the innovations!
             */
            final Properties props = new Properties();
            int count = 0;
            for (Map.Entry<String, RuntimePortal> pp : rc.portals.entrySet()) {
                count++;
                props.put(String.format("portal.%d.id", count), PK.toExternal(pp.getKey()));
                props.put(String.format("portal.%d.name", count), pp.getValue().name);
                props.put(String.format("portal.%d.publicKey", count), pp.getValue().publicKey);
            }
            // the 'total' property is used as counter, to iterate over properties,
            // linked to same portal
            props.put("total", String.valueOf(count));
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaMessage.of("teleporta.system.message.respondPortals", count));
            }
            respondEncryptedProperties(p.publicKey, props, httpExchange, true);
        }
    }
    /**
     * A handler to register/refresh portal
     */
    static class RegisterHandler extends AbstractHandler {
        // if true - we allow to replace registered portals
        private final boolean allowPortalNamesUpdate; 
        private final String motd;
        RegisterHandler(RelayRuntimeContext rc) {
            super(rc);
            allowPortalNamesUpdate = Boolean.parseBoolean(
                    System.getProperty("allowPortalNameUpdate", "false"));
            // build relay's MOTD (welcome message)
            String m =System.getProperty("motd",null);
            if (m == null || m.isEmpty()) {
                m = TeleportaMessage.of("teleporta.system.message.defaultMotd",
                        SystemInfo.SI.getBuildVersion());
            } else if (m.length()>512) {
                m = m.substring(0,512);
            }
            motd = m;
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            setVersionHeader(httpExchange);
            if (checkIfNonPostRequest(httpExchange))
                return;
            // check for limit of registered portals - to avoid DDOS
            if (rc.portals.size() > rc.limits.maxPortals) {
                LOG.log(Level.WARNING, TeleportaError.messageFor(0x7219,rc.limits.maxPortals));
                respondAndClose(400, httpExchange);
                return;
            }
            // parse input, also a .properties file
            final Properties data = new Properties();
            try (InputStream in = httpExchange.getRequestBody()) {
                data.load(in);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
                respondAndClose(500, httpExchange);
                return;
            }
            // portal name (must be unique)
            final String name = data.getProperty("name", null),
                    // portal's public key
                    publicKey = data.getProperty("publicKey", null),
                    currentId = data.getProperty("currentId",null);
            /*
             * In 'private' mode, client must send special encrypted message,
             *  created with his copy of relay's public key.
             *  This works as proof that client has correct key 
             *  and his requests could be processed.
             */
            if (rc.privateRelay) {
                // get hello message
                final String helloMsg = data.getProperty("hello", null);
                // if there is no 'hello' message or its empty - just respond 403 and stop
                if (helloMsg == null || helloMsg.isEmpty()) {
                    // hello message required
                    LOG.warning(TeleportaError.messageFor(0x7220));
                    respondAndClose(403, httpExchange);
                    return;
                }
                try {
                    tc.decryptKey(fromHex(helloMsg), rc.relayPair.getPrivate());
                } catch (Exception ignore) {
                    // incorrect relay key provided
                    LOG.warning(TeleportaError.messageFor(0x7221));
                    respondAndClose(403, httpExchange);
                    return;
                }
            }
            // re-use old session id (if provided) or generate unique ID
            String id = currentId==null
                    // disallow duplicates
                    || rc.portals.containsKey(currentId) ?  PK.generate() :
                    // mean that relay has been restarted and lost all sessions
                    currentId;
            // check for portal name
            if (name == null || name.isEmpty()) {
                // portal name is empty
                LOG.log(Level.WARNING, TeleportaError.messageFor(0x6111));
                respondAndClose(500, httpExchange);
                return;
            }
            // check public key
            if (publicKey == null || publicKey.isEmpty()) {
                // public key is empty
                LOG.log(Level.WARNING, TeleportaError.messageFor(0x6112));
                respondAndClose(500, httpExchange);
                return;
            }
            // check if portal's name already present on relay
            if (rc.portalNames.containsKey(name)) {
                final String existingId = rc.portalNames.get(name);
                final RuntimePortal p = rc.portals.get(existingId);
                // allow session replacement for same public key
                if (p.publicKey.equals(publicKey)) {
                    id = existingId;
                    // mostly for testing
                } else if (allowPortalNamesUpdate) {
                    id = existingId;
                    p.publicKey = publicKey; // update public key
                    // notify all other portals to reload list from relay
                    for (RuntimePortal pp : rc.portals.values()) {
                        if (pp.name.equals(p.name)) {
                            continue;
                        }
                        pp.needReloadPortals = true;
                    }
                } else {
                    // duplicate portal name
                    LOG.log(Level.WARNING, TeleportaError.messageFor(0x6113));
                    respondAndClose(403, httpExchange);
                    return;
                }
            } else {
                // if there is no portal with this name - proceed with registration
                // inform all other portals to reload portals list
                for (RuntimePortal p : rc.portals.values()) {
                    p.needReloadPortals = true;
                }
                // register new portal on relay
                rc.portals.put(id, new RuntimePortal(name, publicKey));
                rc.portalNames.put(name, id);
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(TeleportaMessage
                            .of("teleporta.system.message.portalRegistered", id));
                }
            }
            // respond back generated ID
            final Properties resp = new Properties();
            resp.setProperty("id", PK.toExternal(id));
            /*
             * If we operate in normal mode then it's ok to respond own public key
             */
            if (!rc.privateRelay) {
                resp.setProperty("publicKey",
                        toHex(rc.relayPair.getPublic().getEncoded(), 0, 0));
            }
            if (motd!=null) {
                resp.setProperty("motd",motd);
            }
            respondProperties(resp, httpExchange, true);
        }
    }

    /**
     * A handler to respond list of files, awaiting download
     */
    static class FilePendingDownloadsHandler extends AbstractHandler {
        FilePendingDownloadsHandler(RelayRuntimeContext rc) {
            super(rc);
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            setVersionHeader(httpExchange);
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (!params.containsKey("to")) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to = PK.fromExternal(params.get("to"));
            // bad or malformed portal ID
            if (to==null) {
                respondAndClose(400, httpExchange);
                return;
            }
            // no active portal with this ID
            if (!rc.portals.containsKey(to)) {
                LOG.warning(TeleportaError.messageFor(0x6108, to));
                respondAndClose(403, httpExchange);
                return;
            }

            final RuntimePortal p = rc.portals.get(to);
            // mark 'last seen online'
            p.lastSeen = System.currentTimeMillis();
            final Properties props = new Properties();
            // put mark if client must reload portals list
            if (p.needReloadPortals) {
                props.setProperty("reloadPortals", "true");
            }
            if (p.needLoadClipboard) {
                props.setProperty("updateClipboard", "true");
            }
            final File toFolder = new File(rc.storageDir, PK.toExternal(to));
            // if there were no files for that portal (could be a new one) - respond current
            if (!toFolder.exists() || !toFolder.isDirectory()) {
                if (props.isEmpty()) {
                    respondAndClose(200, httpExchange);
                } else {
                    respondEncryptedProperties(p.publicKey, props, httpExchange, true);
                }
                return;
            }
            final StringBuilder sb = new StringBuilder();
            try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(toFolder.toPath())) {
                int fileCounter = 1;
                for (Path e : dirStream) {
                    if (fileCounter > rc.limits.maxPendingFilesAtOnce) {
                        break;
                    }
                    if (!e.toString().endsWith(EXT_FILE)) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(",");
                    }
                    String name = e.getFileName().toString();
                    // remove prefix and extension from file name
                    name = name.substring("f_".length(), name.length() - EXT_FILE.length());
                    sb.append(name);
                    fileCounter++;
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
            // no .dat files, but still settings
            if (sb.length() == 0) {
                if (props.isEmpty()) {
                    respondAndClose(200, httpExchange);
                } else {
                    respondEncryptedProperties(p.publicKey, props, httpExchange, true);
                }
                return;
            }
            props.setProperty("files", sb.toString());
            respondEncryptedProperties(p.publicKey, props, httpExchange, true);
        }
    }
    /**
     * A handler to upload new file to relay
     */
    static class FileUploadHandler extends AbstractHandler {
        FileUploadHandler(RelayRuntimeContext rc) {
            super(rc);
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            setVersionHeader(httpExchange);
            // allow only POST requests here
            if (checkIfNonPostRequest(httpExchange))
                return;
            // get query params
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            // if no params - just respond 'bad request' and close
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (!params.containsKey("from") || !params.containsKey("to")) {
                respondAndClose(400, httpExchange);
                return;
            }
            // extract query params
            final String from = PK.fromExternal(params.get("from")), // source portal
                    to = PK.fromExternal(params.get("to")); // target portal
            // broken or malformed portal IDs
            if (from==null || to==null) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (!rc.portals.containsKey(from)) {
                LOG.warning(TeleportaError.messageFor(0x6108, from));
                respondAndClose(403, httpExchange);
                return;
            }
            if (!rc.portals.containsKey(to)) {
                LOG.warning(TeleportaError.messageFor(0x6108, to));
                respondAndClose(403, httpExchange);
                return;
            }
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaMessage.of("teleporta.system.message.fromTo", from, to));
            }
            final RuntimePortal p = rc.portals.get(to);
            // generate storage folder
            // external form is used as folder name
            final File toFolder = new File(rc.storageDir, PK.toExternal(to));
            // try to create it if it's not exist
            checkCreateFolder(toFolder);
            // create temp file on relay side
            final File out = new File(toFolder,
                    String.format("f_%d%s", generateUniqueID(), EXT_UPLOAD));
            final byte[] buffer = new byte[4096];
            // transfer file
            try (InputStream in = httpExchange.getRequestBody();
                 FileOutputStream fout = new FileOutputStream(out)) {
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                    // we need to update lastSeen during uploading, because it could be slow
                    p.lastSeen = System.currentTimeMillis();
                    fout.write(buffer, 0, n);
                    fout.flush();
                }

                // build target file, but with .DAT extension
                final File dat_out = new File(out.getParentFile(),
                        out.getName().substring(0, out.getName().length() - EXT_UPLOAD.length())+EXT_FILE);
                if (dat_out.exists() && !dat_out.delete()) {
                    LOG.warning(TeleportaError.messageFor(0x6107,
                            dat_out.getAbsolutePath()));
                }
                // now move from .upload to .dat
                // note: this required to forbid cases when non-completed uploads will be fetched from client side
                if (!out.renameTo(dat_out)) {
                    LOG.warning(TeleportaError.messageFor(0x6115,
                            dat_out.getAbsolutePath()));
                }
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(TeleportaMessage.of("teleporta.system.message.fileUploaded",
                            dat_out.getAbsolutePath()));
                }
                //  respond 200 OK with no data
                httpExchange.sendResponseHeaders(200, 0);
                httpExchange.close();
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
                // delete uncompleted upload on error
                try {
                    if (!out.delete()) {
                        LOG.warning(TeleportaError.messageFor(0x6107,
                                out.getAbsolutePath()));
                    }
                } catch (Exception ignored) {
                }
                //  respond 500 with no data
                respondAndClose(500, httpExchange);
            }
        }
    }

    /**
     * Handler to upload new clipboard content
     */
    static class ClipboardUploadHandler extends AbstractHandler {
        ClipboardUploadHandler(RelayRuntimeContext rc) {
            super(rc);
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            setVersionHeader(httpExchange);
            if (checkIfNonPostRequest(httpExchange))
                return;
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (!params.containsKey("from")) {
                respondAndClose(400, httpExchange);
                return;
            }

            final String from =  PK.fromExternal(params.get("from"));
            if (from==null) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (!rc.portals.containsKey(from)) {
                LOG.warning(TeleportaError.messageFor(0x6108, from));
                respondAndClose(403, httpExchange);
                return;
            }
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(TeleportaMessage.of("teleporta.system.message.from", from));
            }
            // build clipboard file
            final File out = new File(rc.storageDir, String.format("cb_%d_f%s", System.currentTimeMillis(), EXT_FILE));
            // if not created - just respond bad request
            if (!out.createNewFile()) {
                // clipboard file not found
                LOG.warning(TeleportaError.messageFor(0x7222,
                        out.getAbsolutePath()));
                respondAndClose(400, httpExchange);
                return;
            }
            final byte[] buffer = new byte[4096];
            try (InputStream in = httpExchange.getRequestBody();
                 FileOutputStream fout = new FileOutputStream(out)) {
                //read data and store into file on relay
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
                    fout.write(buffer, 0, n);

                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(TeleportaMessage.of("teleporta.system.message.clipboardUploaded",
                            out.getAbsolutePath()));
                }
                // try to delete previous clipboard data
                if (rc.currentCbFile!=null && !rc.currentCbFile.delete()) {
                        // cannot delete file
                        LOG.warning(TeleportaError.messageFor(0x6106,
                                out.getAbsolutePath()));
                }
                // attach current clipboard file to context
                rc.currentCbFile = out;
                // notify all other about clipboard update
                for (String k : rc.portals.keySet()) {
                    if (k.equals(from)) {
                        continue;
                    }
                    final RuntimePortal p = rc.portals.get(k);
                    p.needLoadClipboard = true;
                }
                //  respond 200 OK with no data
                httpExchange.sendResponseHeaders(200, 0);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
                    if (!out.delete())
                        // cannot delete file
                        LOG.warning(TeleportaError.messageFor(0x6106,
                                out.getAbsolutePath()));

                //  respond 500 with no data
                respondAndClose(500, httpExchange);
            }
        }
    }

    /**
     * Handler to download updated clipboard contents
     */
    static class ClipboardDownloadHandler extends AbstractHandler {
        ClipboardDownloadHandler(RelayRuntimeContext rc) {
           super(rc);
        }

        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            // set header with Teleporta version
            setVersionHeader(httpExchange);

            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (!params.containsKey("to")) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to =  PK.fromExternal(params.get("to")); // target portal's id
            // malformed ID
            if (to==null) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (LOG.isLoggable(Level.FINE))
                LOG.fine(TeleportaMessage.of("teleporta.system.message.to", to));

            if (!rc.portals.containsKey(to)) {
                // portal not found error
                LOG.warning(TeleportaError.messageFor(0x6108, to));
                respondAndClose(403, httpExchange);
                return;
            }

            // get target portal
            final RuntimePortal p = rc.portals.get(to);
            // check for clipboard file
            final File rFile = rc.currentCbFile;
            if (!isAcceptable(rFile,true)) {
                p.needLoadClipboard = false;
                LOG.warning(TeleportaError.messageFor(0x7222,""));
                respondAndClose(400, httpExchange);
                return;
            }
            httpExchange.sendResponseHeaders(200, 0);
            try (OutputStream out = httpExchange.getResponseBody();
                 FileInputStream fin = new FileInputStream(rFile)) {
                AbstractClient.checkPacketHeader(fin,false);
                // try to extract session key first (AES)
                final byte[] key = new byte[SESSION_KEY_LEN];
                if (fin.read(key) != key.length) {
                    LOG.warning(TeleportaError.messageFor(0x6007));
                    respondAndClose(400, httpExchange);
                    return;
                }
                // decrypt it using relay's private key
                byte[] dec = tc.decryptKey(key, rc.relayPair.getPrivate());
                final SecretKeySpec rkey = new SecretKeySpec(dec, "AES");
                // now generate another session key
                final SecretKey key2 = tc.generateFileKey();
                // get target portal's public key
                final PublicKey pk = tc.restorePublicKey(
                        fromHex(rc.portals.get(to).publicKey));
                // and now encrypt data with a new key
                final byte[] enc2 = tc.encryptKey(key2.getEncoded(), pk);
                out.write(AbstractClient.TELEPORTA_PACKET_HEADER);
                out.write(enc2);
                out.flush();
                // do re-encryption for data flow
                tc.rencryptData(rkey, key2, fin, out);
                // reset mark for clipboard update
                p.needLoadClipboard = false;
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(TeleportaMessage.of("teleporta.system.message.fileDownloaded",
                            rFile.getAbsolutePath()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            } finally {
                // file removal is fast, no need to detach in dedicated thread
                if (!rFile.delete())
                    LOG.warning(TeleportaError.messageFor(0x6106, rFile.getAbsolutePath()));

            }
        }
    }
    /**
     * Handle files downloads
     */
    static class FileDownloadHandler extends AbstractHandler {
        FileDownloadHandler(RelayRuntimeContext rc) {
           super(rc);
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            setVersionHeader(httpExchange);
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (!params.containsKey("to") || !params.containsKey("file")) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to = PK.fromExternal(params.get("to")), // client portal
                    fileId = PK.fromExternal(params.get("file")); // file id

            // malformed IDs
            if (to==null || fileId==null) {
                respondAndClose(400, httpExchange);
                return;
            }
            if (LOG.isLoggable(Level.FINE))
                LOG.fine(TeleportaMessage.of("teleporta.system.message.toFile", to, fileId));

            final RuntimePortal p = rc.portals.get(to);
            // external form is used for files/folders stored on disk
            final File toFolder = new File(rc.storageDir, PK.toExternal(to)),
                    rFile = new File(toFolder, String.format("f_%s%s", PK.toExternal(fileId), EXT_FILE));
            if (!isAcceptable(rFile,true)) {
                // stored file not found
                LOG.warning(TeleportaError.messageFor(0x6114,
                        rFile.getAbsolutePath()));
                respondAndClose(400, httpExchange);
                return;
            }
            httpExchange.sendResponseHeaders(200, rFile.length());
            final byte[] buffer = new byte[4096];
            try (OutputStream out = httpExchange.getResponseBody();
                 FileInputStream fin = new FileInputStream(rFile)) {
                // respond file data
                for (int n = fin.read(buffer); n >= 0; n = fin.read(buffer)) {
                    // mark 'last seen online'
                    p.lastSeen = System.currentTimeMillis();
                    out.write(buffer, 0, n);
                    out.flush();
                }
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(TeleportaMessage.of("teleporta.system.message.fileDownloaded",
                            rFile.getAbsolutePath()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            } finally {
                // file removal is fast, no need to detach in dedicated thread
                if (!rFile.delete())
                    LOG.warning(TeleportaError.messageFor(0x6106,
                            rFile.getAbsolutePath()));

                httpExchange.close();
            }
        }
    }

    /**
     * Abstract shared handler, contains some useful stuff.
     */
    abstract static class AbstractHandler implements HttpHandler {
        protected final TeleCrypt tc = new TeleCrypt();
        protected final RelayRuntimeContext rc;
        AbstractHandler(RelayRuntimeContext rc) {
            this.rc = rc;
        }
        /**
         * Extract query params from URL
         *
         * @param u url
         * @return key-value pairs with provided params
         */
        Map<String, String> getQueryParams(URI u) {
            if (u == null || u.getQuery() == null)
                return Collections.emptyMap();

            final String query = u.getQuery().toLowerCase().trim();
            if (query.isEmpty())
                return Collections.emptyMap();

            final Map<String, String> qp = new LinkedHashMap<>();
            final String[] pairs = query.split("&");
            for (String pair : pairs) {
                try {
                    final int idx = pair.indexOf("=");
                    final String key = idx > 0 ?
                            URLDecoder.decode(pair.substring(0, idx),
                                    String.valueOf(StandardCharsets.UTF_8)) : pair;
                    final String value = idx > 0 && pair.length() > idx + 1 ?
                            URLDecoder.decode(pair.substring(idx + 1),
                                    String.valueOf(StandardCharsets.UTF_8)) : null;
                    qp.put(key, value);
                } catch (UnsupportedEncodingException e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                    return Collections.emptyMap();
                }
            }
            return qp;
        }
        /**
         * Checks that current request is not POST
         * We allow only POST for some endpoints.
         *
         * @param exchange current http context
         * @return true if this request is not POST
         * false - otherwise
         */
        protected boolean checkIfNonPostRequest(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()))
                return false;
            respondAndClose(400, exchange);
            return true;
        }
        /**
         * Set Teleporta version header on relay side
         *
         * @param exchange
         */
        protected void setVersionHeader(HttpExchange exchange) {
            exchange.getResponseHeaders().set("Server", "Teleporta Relay/" +
                    (rc.respondVersion ? SystemInfo.SI.getBuildVersion() : "Unknown"));
        }
        /**
         * Respond unencrypted properties to http stream
         *
         * @param props    filled properties object
         * @param exchange HttpExchange instance
         * @param close    if true - HttpExchange.close() method will be called
         */
        protected void respondProperties(Properties props, HttpExchange exchange, boolean close) {
            try (OutputStream os = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, 0);
                props.store(os, "");
                os.flush();
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            } finally {
                if (close) {
                    exchange.close();
                }
            }
        }
        /**
         * Respond Properties file with encryption
         *
         * @param portalPk portal (client) public key
         * @param props    properties object
         * @param exchange current http exchange context
         * @param close    if true - closes output stream
         */
        protected void respondEncryptedProperties(String portalPk,
                                                  Properties props,
                                                  HttpExchange exchange, boolean close) {
            try (OutputStream os = exchange.getResponseBody();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                exchange.sendResponseHeaders(200, 0);
                os.write(AbstractClient.TELEPORTA_PACKET_HEADER);
                // generate new session key (AES)
                final SecretKey sk = tc.generateFileKey();
                // restore public key
                final PublicKey pk = tc.restorePublicKey(fromHex(portalPk));
                // encrypt session key with public key
                final byte[] enc = tc.encryptKey(sk.getEncoded(), pk);
                // write encrypted key
                os.write(enc);
                // store properties data into byte array
                props.store(baos, "");
                // now encrypt stored data and push it to client
                try (ByteArrayInputStream in = new ByteArrayInputStream(baos.toByteArray())) {
                    tc.encryptData(sk, in, os);
                }
                os.flush();
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            } finally {
                if (close) {
                    exchange.close();
                }
            }
        }
        /**
         * Respond 400 Bad Request
         * This is used as universal 'bad request' answer.
         *
         * @param exchange current http exchange context
         */
        protected void respondAndClose(int httpError,
                                       HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(httpError, 0);
            exchange.close();
        }
    }
    /**
     * DTO to store portal details
     */
    static class RuntimePortal extends RegisteredPortal {
        boolean needReloadPortals, // if true - this portal must reload portals list from relay
                needLoadClipboard;  // if true - this portal must load clipboard file
        long lastSeen; // last seen this portal online
        RuntimePortal(String name, String publicKey) {
            super(name, publicKey);
        }
    }
    /***
     * Stores limits for Teleporta Relay
     */
    static class RelayLimits {
        final int maxPortals, // max registered portals
                maxPendingFilesAtOnce, // maximum pending files per package
                nonDeliveredExpire, // expiration time for non-delivered files
                portalExpireTimeout; // portal expiration time
        RelayLimits() {
            maxPortals = Integer.parseInt(System.getProperty("limits.maxPortals","500"));
            maxPendingFilesAtOnce = Integer.parseInt(System.getProperty("limits.maxPending","10"));
            nonDeliveredExpire = 60 * 60 * 1000 *
                    Integer.parseInt(System.getProperty("limits.nonDeliveredExpire","5"));
            portalExpireTimeout = 1000 * Integer.parseInt(System.getProperty("limits.portalTimeout","60"));
        }
    }
    /**
     * Runtime context, stores configuration and runtime data
     */
    static class RelayRuntimeContext {
        final File storageDir; // root storage folder, used on relay side
        final Map<String, RuntimePortal> portals = new LinkedHashMap<>(); // all registered portals
        final Map<String, String> portalNames = new LinkedHashMap<>(); // all registered portals names
        final KeyPair relayPair; // relay keys
        final boolean privateRelay, // if true - we operate in 'private relay' mode
                allowClipboardTransfer, // if true - we allow clipboard transfers
                respondVersion;
        final RelayLimits limits;
        File currentCbFile; // current clipboard data
        RelayRuntimeContext(RelayLimits limits,File storageDir,
                            KeyPair kp,
                            boolean privateRelay,boolean allowClipboardTransfer,boolean respondVersion) {
            this.storageDir = storageDir;
            this.relayPair = kp;
            this.privateRelay = privateRelay;
            this.allowClipboardTransfer = allowClipboardTransfer;
            this.respondVersion = respondVersion;
            this.limits = limits;
        }
    }
    /**
     * Checks and removes expired portals and undelivered files
     * @param rc
     * @return
     */
    private static Set<String> removeExpired(RelayRuntimeContext rc) {
        final Set<String> expired = new HashSet<>();
        for (String k : rc.portals.keySet()) {
            final RuntimePortal p = rc.portals.get(k);
            // check for other portals expiration
            if (System.currentTimeMillis() - p.lastSeen > rc.limits.portalExpireTimeout)
                expired.add(k);

        }
        // remove local folders for expired portals
        if (!expired.isEmpty()) {
            for (String k : expired) {
                final RegisteredPortal p = rc.portals.remove(k);
                rc.portalNames.remove(p.name);
                // remove non-delivered files for expired portals
                deleteRecursive(new File(rc.storageDir, p.name), true,EXT_FILE);
                if (LOG.isLoggable(Level.FINE))
                    LOG.fine(TeleportaMessage.of("teleporta.system.message.removedExpiredPortal", p.name));

            }
            // notify all other about removal
            for (String k : rc.portals.keySet()) {
                final RuntimePortal p = rc.portals.get(k);
                p.needReloadPortals = true;
            }
        }
        // second stage: check expiration on each file in relay store,
        //  which not yet delivered
        for (String k : rc.portalNames.keySet()) {
            final File relayParent = new File(rc.storageDir, k);
            if (!relayParent.exists() || !relayParent.isDirectory())
                continue;

            // note: we limit number of processed files on each iteration
            // because there could be too many of them
            try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(relayParent.toPath())) {
                int fileCounter = 1;
                for (Path e : dirStream) {
                    if (fileCounter > FILES_BULK_LIMIT)
                        break;

                    // ignore files not related to Teleporta
                    if (!e.toString().toLowerCase().endsWith(EXT_FILE))
                        continue;

                    final File f = e.toFile();
                    // check for file expiration
                    if (System.currentTimeMillis() - f.lastModified() > rc.limits.nonDeliveredExpire) {
                        if (!f.delete()) {
                            LOG.warning(TeleportaError.messageFor(0x6106,
                                    f.getAbsolutePath()));
                            continue;
                        } else {
                            if (LOG.isLoggable(Level.FINE))
                                LOG.fine(TeleportaMessage.of("teleporta.system.message.removedExpiredNonDeliveredFile",
                                        f.getAbsolutePath()));

                        }
                    }
                    fileCounter++;
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }
        }
        return expired;
    }

    static String generateUrl(String seed, String part) {
        final String url = buildServerUrl(seed, part);
        if (LOG.isLoggable(Level.FINE))
            LOG.fine(TeleportaMessage
                    .of("teleporta.system.message.urlDerivedPart", url, part));

        return String.format("/%s/%s", seed, url);
    }
    /**
     * Prints Teleporta relay's public key
     * @param data
     */
    static void printRelayKey(byte[] data) {
        System.out.printf("%s|%n", ("|TELEPORTA" + toHex(data, 0, 0))
                .replaceAll(".{80}(?=.)", "$0\n"));
    }
}
