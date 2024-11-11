package com.Ox08.teleporta.v3;
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

import static com.Ox08.teleporta.v3.TeleportaCommons.*;
/**
 * Teleporta Relay
 *
 * @author 0x08
 * @since 1.0
 */
public class TeleportaRelay {
    public static final int MAX_PORTALS = 500,
            MAX_FILES_TO_LIST = 10,
            NON_DELIVERED_EXPIRE = 60 * 60 * 1000;
    private final static Logger LOG = Logger.getLogger("TC");
    private final static ScheduledExecutorService ses = Executors.newScheduledThreadPool(1);
    /**
     * Main function is only used for tests
     *
     * @param args
     *
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        setDebugLogging();
        System.setProperty("javax.net.debug", "all");
        System.setProperty("seed", "testaaaatest22222222aaaaaaaaaaaaaaaaaaaaaa");
       // System.setProperty("privateRelay","true");
        init();
    }
    /**
     * Initializes Teleporta in relay mode
     * @throws Exception
     *          on I/O errors
     */
    public static void init() throws Exception {
        final int port = Integer.parseInt(System.getProperty("appPort", "8989"));
        final File teleportaHome = checkCreateHomeFolder("teleporta-relay");
        deleteRecursive(teleportaHome, false);

        // We need to detect own jar location and pass full path as global variable to 'RespondSelfHandler'
        final CodeSource codeSource = TeleportaRelay.class.getProtectionDomain().getCodeSource();
        final File jarFile;
        try {
            jarFile = new File(codeSource.getLocation().toURI());
        } catch (URISyntaxException ex) {
            System.err.printf("Cannot detect self location: %s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
            return;
        }
        System.setProperty("TELEPORTA_APP_JAR", jarFile.getAbsolutePath());

        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 50);
        final TeleCrypt tc = new TeleCrypt();
        final KeyPair rkp = tc.generateKeys();
        final boolean privateRelay =
                Boolean.parseBoolean(System.getProperty("privateRelay", "false"));
        // print relay key if 'private mode' enabled, allows user to copy it
        if (privateRelay) {
            System.out.println("Relay key: ");
            printRelayKey(rkp.getPublic().getEncoded());
        }
        // build runtime context
        final RelayRuntimeContext rc = new RelayRuntimeContext(teleportaHome, rkp ,privateRelay);
        // background task to remove expired portals
        ses.scheduleAtFixedRate(() -> {
            final Set<String> expired = new HashSet<>();
            for (String k : rc.portals.keySet()) {
                final RuntimePortal p = rc.portals.get(k);
                // check for other portals expiration
                if (System.currentTimeMillis() - p.lastSeen > 60_000) {
                    expired.add(k);
                }
            }
            // remove local folders for expired portals in 'out' folder
            if (!expired.isEmpty()) {
                for (String k : expired) {
                    final RegisteredPortal p = rc.portals.remove(k);
                    rc.portalNames.remove(p.name);
                    // remove non-delivered files for expired portals
                    deleteRecursive(new File(rc.storageDir, p.name), true);
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine(String.format("removed expired portal: %s", p.name));
                    }
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
                if (!relayParent.exists() || !relayParent.isDirectory()) {
                    continue;
                }
                // note: we limit number of processed files on each iteration
                // because there could be too many of them
                try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(relayParent.toPath())) {
                    int fileCounter = 1;
                    for (Path e : dirStream) {
                        if (fileCounter > 1000) {
                            break;
                        }
                        // ignore files not related to Teleporta
                        if (!e.toString().toLowerCase().endsWith(".dat")) {
                            continue;
                        }
                        final File f = e.toFile();
                        if (System.currentTimeMillis() - f.lastModified() > NON_DELIVERED_EXPIRE) {
                            if (!f.delete()) {
                                LOG.warning(String.format("Cannot remove expired file: %s",
                                        f.getAbsolutePath()));
                                continue;
                            } else {
                                if (LOG.isLoggable(Level.FINE)) {
                                    LOG.fine(String.format("removed expired %s non-delivered file",
                                            f.getAbsolutePath()));
                                }
                            }
                        }
                        fileCounter++;
                    }
                } catch (IOException e) {
                    LOG.log(Level.WARNING, e.getMessage(), e);
                }
            }
        }, 120, 60, TimeUnit.SECONDS);
        // Use defined or generate seed
        final String seed = System.getProperty("seed", genSeed());
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("seed: %s", seed));
        }
        /*
         * Register handlers for endpoints
         */
        server.createContext(generateUrl(seed, "file-upload"))
                .setHandler(new FileUploadHandler(rc));
        server.createContext(generateUrl(seed, "poll"))
                .setHandler(new FilePendingDownloadsHandler(rc));
        server.createContext(generateUrl(seed, "file-download"))
                .setHandler(new FileDownloadHandler(rc));
        server.createContext(generateUrl(seed, "cb-download"))
                .setHandler(new ClipboardDownloadHandler(rc));
        server.createContext(generateUrl(seed, "cb-upload"))
                .setHandler(new ClipboardUploadHandler(rc));
        server.createContext(generateUrl(seed, "register"))
                .setHandler(new RegisterHandler(rc));
        server.createContext(generateUrl(seed, "get-portals"))
                .setHandler(new GetPortalsHandler(rc));
        final boolean selfDownload = Boolean
                .parseBoolean(System.getProperty("selfDownload", "true"));
        if (selfDownload) {
            LOG.info("Self downloads allowed");
            server.createContext("/" + seed)
                    .setHandler(new RespondSelfHandler());
        }
        LOG.info(String.format("%s Teleporta Relay started: http://%s:%d/%s. Press CTRL-C to stop",
                rc.privateRelay ? "Private" : "Public",
                InetAddress.getLocalHost().getHostName(),
                port, seed));
        // starts Teleporta in relay mode
        server.start();
    }
    static String generateUrl(String seed, String part) {
        final String url = buildServerUrl(seed, part);
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(String.format("derived part: '%s' for '%s'", url, part));
        }
        return String.format("/%s/%s", seed, url);
    }
    /**
     * Runtime context, stores configuration and runtime data
     */
    static class RelayRuntimeContext {
        final File storageDir; // root storage folder, used on relay side
        final Map<String, RuntimePortal> portals = new LinkedHashMap<>(); // all registered portals
        final Map<String, String> portalNames = new LinkedHashMap<>(); // all registered portals names
        final KeyPair relayPair; // relay keys
        final boolean privateRelay;
        RelayRuntimeContext(File storageDir, KeyPair kp,boolean privateRelay) {
            this.storageDir = storageDir;
            this.relayPair = kp;
            this.privateRelay = privateRelay;
        }
    }

    /**
     *   This handler allows to download Teleporta distribution, generated from runtime.
     */
    static class RespondSelfHandler extends AbstractHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            final String dl = params.isEmpty() ?  null : params.get("dl");
            if (dl == null) {
                httpExchange.sendResponseHeaders(200,INDEX.length);
                // respond static page with javascript to detect self domain
                httpExchange.getResponseHeaders().add("Content-Type","text/html");
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
                LOG.warning("self jar not found, TELEPORTA_APP_JAR is null ");
                respondAndClose(400,httpExchange);
                return;
            }
            final File jarFile = new File(jarF);
            if (!jarFile.exists() || !jarFile.isFile() || !jarFile.canRead()) {
                    LOG.warning(String.format("self file not found or not readable: '%s'", jarFile));
                respondAndClose(400,httpExchange);
                return;
            }

            final Headers headers = httpExchange.getResponseHeaders();
            headers.set("Content-Type","application/octet-stream");
            // build .zip distribution and respond as stream
            headers.set("Content-Disposition", "attachment;filename=teleporta.zip");
            httpExchange.sendResponseHeaders(200,0);

            final byte[] buffer = new byte[4096];
            try (ZipOutputStream zout = new ZipOutputStream(httpExchange.getResponseBody());
                 FileInputStream in = new FileInputStream(jarFile)) {
                zout.putNextEntry(new ZipEntry("/teleporta/" ));
                zout.closeEntry();
                zout.putNextEntry(new ZipEntry("/teleporta/" + jarFile.getName()));
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
                    zout.write(buffer, 0, n);
                zout.closeEntry();
                // if we're able to detect self domain - add connection string
                if (self != null) {
                    zout.putNextEntry(new ZipEntry("/teleporta/teleporta.properties"));
                    final Properties p = new Properties();
                    p.setProperty("relayUrl", self);
                    p.store(zout, "Initial Teleporta Server settings");
                    zout.closeEntry();
                }
                zout.finish();
            }
        }

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
        private final RelayRuntimeContext rc;
        GetPortalsHandler(RelayRuntimeContext rc) {
            this.rc = rc;
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to = params.get("to");
            // 'to' param is used as session
            if (!rc.portals.containsKey(to)) {
                LOG.warning(String.format("Cannot find portal: %s", to));
                return;
            }
            final RuntimePortal p = rc.portals.get(to);
            // mark for 'non-reload'
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
                props.put(String.format("portal.%d.id", count), pp.getKey());
                props.put(String.format("portal.%d.name", count), pp.getValue().name);
                props.put(String.format("portal.%d.publicKey", count), pp.getValue().publicKey);
            }
            // the 'total' property is used as counter, to iterate over properties,
            // linked to same portal
            props.put("total", String.valueOf(count));
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("respond %d portals", count));
            }
            respondEncryptedProperties(p.publicKey,props,httpExchange,false);
        }
    }
    /**
     * A handler to register/refresh portal
     */
    static class RegisterHandler extends AbstractHandler {
        private final RelayRuntimeContext rc;
        private final boolean allowPortalNamesUpdate; // if set - we allow to replace registered portals
        RegisterHandler(RelayRuntimeContext rc) {
            this.rc = rc;
            allowPortalNamesUpdate = Boolean.parseBoolean(
                    System.getProperty("allowPortalNameUpdate", "false"));
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            if (checkIfNonPostRequest(httpExchange))
                return;
            // check for limit of registered portals - to avoid DDOS
            if (rc.portals.size() > MAX_PORTALS) {
                LOG.log(Level.WARNING, "Max portals limit reached");
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
                   publicKey = data.getProperty("publicKey", null);

            if (rc.privateRelay) {
                final String helloMsg = data.getProperty("hello",null);
                if (helloMsg==null || helloMsg.isEmpty()) {
                    LOG.warning("Hello message required.");
                    respondAndClose(403, httpExchange);
                    return;
                }
                try {
                    tc.decryptKey(fromHex(helloMsg), rc.relayPair.getPrivate());
                } catch (Exception ignore) {
                    LOG.warning("Incorrect relay key provided.");
                    respondAndClose(403, httpExchange);
                    return;
                }
            }
            // unique ID
            String id = generateUniqueID() + "";
            // check for portal name
            if (name == null || name.isEmpty()) {
                LOG.log(Level.WARNING, "Portal name is not defined");
                respondAndClose(500, httpExchange);
                return;
            }
            // check public key
            if (publicKey == null || publicKey.isEmpty()) {
                LOG.log(Level.WARNING, "Public key is not defined");
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
                } else if(allowPortalNamesUpdate) {
                    id = existingId;
                    p.publicKey = publicKey; // update public key

                    // notify all other portals to reload list from relay
                    for (RuntimePortal pp : rc.portals.values()) {
                        if (pp.name.equals(p.name)){
                            continue;
                        }
                        pp.needReloadPortals = true;
                    }

                } else {
                    LOG.log(Level.WARNING, "Duplicate portal name");
                    respondAndClose(400, httpExchange);
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
            }
            // respond back generated ID
            final Properties resp = new Properties();
            resp.setProperty("id", id);
            if (!rc.privateRelay) {
                resp.setProperty("publicKey",
                        toHex(rc.relayPair.getPublic().getEncoded(), 0, 0));
            }
            respondProperties(resp, httpExchange, false);
        }
    }
    /**
     * A handler to respond list of files, awaiting download
     */
    static class FilePendingDownloadsHandler extends AbstractHandler {
        private final RelayRuntimeContext rc;
        FilePendingDownloadsHandler(RelayRuntimeContext rc) {
            this.rc = rc;
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to = params.get("to");
            final File toFolder = new File(rc.storageDir, to);
            if (!rc.portals.containsKey(to)) {
                LOG.warning(String.format("Cannot find portal: %s", to));
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

            // if there were no files for that portal (could be a new one) - respond current
            if (!toFolder.exists() || !toFolder.isDirectory()) {
                if (props.isEmpty()) {
                    respondAndClose(200, httpExchange);
                } else {
                    respondEncryptedProperties(p.publicKey,props, httpExchange, true);
                }
                 return;
            }
            final StringBuilder sb = new StringBuilder();
            try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(toFolder.toPath())) {
                int fileCounter = 1;
                for (Path e : dirStream) {
                    if (fileCounter > MAX_FILES_TO_LIST) {
                        break;
                    }
                    if (!e.toString().endsWith(".dat")) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(",");
                    }
                    String name = e.getFileName().toString();
                    name = name.substring(0, name.length() - 4);
                    sb.append(name);
                    fileCounter++;
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            }

            // no .dat files, but still settings
            if (sb.length() == 0) {
                if (props.isEmpty()) {
                    respondAndClose(200,httpExchange);
                } else {
                    respondEncryptedProperties(p.publicKey,props, httpExchange, true);
                }
                return;
            }
            props.setProperty("files", sb.toString());
            respondEncryptedProperties(p.publicKey,props, httpExchange, false);
        }
    }
    /**
     * A handler to upload new file to relay
     */
    static class FileUploadHandler extends AbstractHandler {
        private final RelayRuntimeContext rc;
        FileUploadHandler(RelayRuntimeContext rc) {
            this.rc = rc;
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
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
            // extract query params
            final String from = params.get("from"), // source portal
                    to = params.get("to"); // target portal
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("from: '%s' to: '%s'", from, to));
            }
            // generate storage folder
            final File toFolder = new File(rc.storageDir, to);
            // try to create it if it's not exist
            checkCreateFolder(toFolder);
            // create temp file on relay side
            final File out = new File(toFolder, generateUniqueID() + ".dat");
            final byte[] buffer = new byte[4096];
            // transfer file
            try (InputStream in = httpExchange.getRequestBody();
                 FileOutputStream fout = new FileOutputStream(out)) {
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
                    fout.write(buffer, 0, n);
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(String.format("file uploaded: %s",
                            out.getAbsolutePath()));
                }
                //  respond 200 OK with no data
                httpExchange.sendResponseHeaders(200, 0);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
                try {
                    if (!out.delete()) {
                        LOG.warning(String.format("Cannot delete broken upload:%s",
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
        private final RelayRuntimeContext rc;
        ClipboardUploadHandler(RelayRuntimeContext rc) {
            this.rc = rc;
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            if (checkIfNonPostRequest(httpExchange))
                return;
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String from = params.get("from");
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("from: '%s' ", from));
            }
            // build clipboard file
            final File out = new File(rc.storageDir, "cb.dat");
            // if it's not exist or not readable - just respond bad request
            if (!out.exists() || !out.isFile() || !out.canRead()) {
                LOG.warning(String.format("Clipboard file not found: %s",
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
                    LOG.fine(String.format("clipboard uploaded: %s",
                            out.getAbsolutePath()));
                }
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
                try {
                    if (!out.delete()) {
                        LOG.warning(String.format("Cannot delete broken cb upload:%s",
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
     * Handler to download updated clipboard contents
     */
    static class ClipboardDownloadHandler extends AbstractHandler {
        private final RelayRuntimeContext rc;
        ClipboardDownloadHandler(RelayRuntimeContext rc) {
            this.rc = rc;
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to = params.get("to"); // target portal's id
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("to=%s ", to));
            }
            if (!rc.portals.containsKey(to)) {
                LOG.warning(String.format("Cannot find portal: %s", to));
                respondAndClose(403, httpExchange);
                return;
            }
            // get target portal
            final RuntimePortal p = rc.portals.get(to);
            // check for clipboard file
            final File rFile = new File(rc.storageDir, "cb.dat");
            if (!rFile.exists() ||!rFile.isFile() ||!rFile.canRead()) {
                p.needLoadClipboard = false;
                LOG.warning(String.format("Clipboard file not found or not readable: %s",
                        rFile.getAbsolutePath()));
                respondAndClose(400, httpExchange);
                return;
            }
            final TeleCrypt tc = new TeleCrypt();
            httpExchange.sendResponseHeaders(200, 0);
            try (OutputStream out = httpExchange.getResponseBody();
                 FileInputStream fin = new FileInputStream(rFile)) {
                // try to extract session key first (AES)
                final byte[] key = new byte[256];
                if (fin.read(key)!=key.length) {
                    LOG.warning("Clipboard key corrupted!");
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
                out.write(enc2);
                out.flush();
                // do re-encryption for data flow
                tc.rencryptData(rkey, key2, fin, out);
                p.needLoadClipboard = false;
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(String.format("file downloaded: %s", rFile.getAbsolutePath()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            } finally {
                // file removal is fast, no need to detach in dedicated thread
                if (!rFile.delete()) {
                    LOG.warning(String.format("cannot delete file: %s", rFile.getAbsolutePath()));
                }
            }
        }
    }
    /**
     * Handle files downloads
     */
    static class FileDownloadHandler extends AbstractHandler {
        private final RelayRuntimeContext rc;
        FileDownloadHandler(RelayRuntimeContext rc) {
            this.rc = rc;
        }
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            final Map<String, String> params = getQueryParams(httpExchange.getRequestURI());
            if (params.isEmpty()) {
                respondAndClose(400, httpExchange);
                return;
            }
            final String to = params.get("to"), // client portal
                    fileId = params.get("file"); // file id
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine(String.format("to=%s ,file=%s", to, fileId));
            }
            final File toFolder = new File(rc.storageDir, to),
                    rFile = new File(toFolder, fileId + ".dat");

            if (!rFile.exists() ||!rFile.isFile() || !rFile.canRead()) {
                LOG.warning(String.format("Stored file not found or not readable: %s",
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
                    out.write(buffer, 0, n);
                }
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(String.format("file downloaded: %s", rFile.getAbsolutePath()));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
            } finally {
                // file removal is fast, no need to detach in dedicated thread
                if (!rFile.delete()) {
                    LOG.warning(String.format("cannot delete file: %s", rFile.getAbsolutePath()));
                }
            }
        }
    }
    /**
     * Abstract shared handler, contains some useful stuff.
     */
    abstract static class AbstractHandler implements HttpHandler {
        protected final TeleCrypt tc = new TeleCrypt();
        /**
         * Extract query params from URL
         * @param u
         *          url
         * @return
         *      key-value pairs with provided params
         */
        Map<String, String> getQueryParams(URI u) {
            if (u == null || u.getQuery() == null) {
                return Collections.emptyMap();
            }
            final String query = u.getQuery().toLowerCase().trim();
            final Map<String, String> qp = new LinkedHashMap<>();
            if (query.isEmpty()) {
                return qp;
            }
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
                } catch (Exception e) {
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
         * Respond unencrypted properties to http stream
         * @param props
         *          filled properties object
         * @param exchange
         *          HttpExchange instance
         * @param close
         *          if true - HttpExchange.close() method will be called
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
         * @param portalPk
         *          portal (client) public key
         * @param props
         *          properties object
         * @param exchange
         *          current http exchange context
         * @param close
         *      if true - closes output stream
         */
        protected void respondEncryptedProperties(String portalPk,
                                                  Properties props,
                                                  HttpExchange exchange, boolean close) {
            try (OutputStream os = exchange.getResponseBody();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                exchange.sendResponseHeaders(200, 0);
                // generate new session key (AES)
                final SecretKey sk =tc.generateFileKey();
                // restore public key
                PublicKey pk =tc.restorePublicKey(fromHex(portalPk));
                // encrypt session key with public key
                byte[] enc = tc.encryptKey(sk.getEncoded(),pk);
                // write encrypted key
                os.write(enc);
                // store properties data into byte array
                props.store(baos, "");
                // now encrypt stored data and push it to client
                try (ByteArrayInputStream in = new ByteArrayInputStream(baos.toByteArray())) {
                    tc.encryptData(sk, in, os );
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
        boolean needReloadPortals, needLoadClipboard;
        long lastSeen;
        RuntimePortal(String name, String publicKey) {
            super(name, publicKey);
        }
    }
    public static void printRelayKey(byte[] data) {
        System.out.printf("%s|%n", ("|TELEPORTA" + toHex(data,0,0))
                .replaceAll(".{80}(?=.)", "$0\n"));
    }
}
