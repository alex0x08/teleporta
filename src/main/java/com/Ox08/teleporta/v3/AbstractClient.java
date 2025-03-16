package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.services.TeleLnk;

import javax.crypto.spec.SecretKeySpec;
import java.awt.*;
import java.io.*;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.Ox08.teleporta.v3.TeleportaCommons.*;
/**
 * Abstract parent client, that shares common functions between 
 * real Teleporta client and embedded, which used on relay side.
 *
 * @since 3.1.5
 * @author 0x08
 */
public abstract class AbstractClient {
    protected static final Logger LOG = Logger.getLogger("TC");
    protected TeleCrypt tc = new TeleCrypt(); // shared instance with cryptographic functions
                                              // it's ok, because each function is atomic

    public static final byte[] TELEPORTED_FILE_HEADER = "TELEPORTEDFILE".getBytes(),
            TELEPORTA_PACKET_HEADER = "TELEPORTAPACKET".getBytes();

    protected static final String ENTRY_META = "meta.properties", // zip entry with metadata
            ENTRY_DATA = "file.content"; // zip entry with file content

    /**
     * Creates link to Teleporta's home on Desktop
     * @param teleportaHome 
     *          path to Teleporta's home
     */
    protected static void createDesktopLink(File teleportaHome) {
        // don't try in headless mode, means that we're on server
        if (GraphicsEnvironment.isHeadless())
            return;

        try {
            // this is small hack to detect user's home folder
            // works better than reading common environment variable
            final File homeFolder = javax.swing.filechooser.FileSystemView
                    .getFileSystemView().getHomeDirectory(),
                    // probably there should be a better way to detect user's desktop folder,
                    // but in 99% cases its named as 'Desktop', on all OSes and environments
                    desktop = new File(homeFolder, "Desktop");
            // if we found Desktop folder - create link on it, otherwise - create link in user's home folder
            final File lnk = desktop.exists() && desktop.isDirectory() ?
                    new File(desktop, "Teleporta") :
                    new File(homeFolder, "Teleporta");
            if (!lnk.exists())
                // for Windows, we need to create .lnk file manually,
                // because createSymbolicLink is not allowed
                // without Administrator permissions
                if (isWindows())
                    TeleLnk.createLnkFor(teleportaHome.toPath(),
                            new File(lnk.getParent(), lnk.getName() + ".lnk"));
                else
                    Files.createSymbolicLink(lnk.toPath(), teleportaHome.toPath());


        } catch (IOException ex) {
            LOG.log(Level.WARNING, ex.getMessage(), ex);
        }
    }
    /**
     *  Reads session key (AES) from input stream
     * @param in
     *          input stream
     * @param allowEmpty
     *          if true - will not raise exception on empty input
     * @param pkey
     *          current private key (RSA), required to decrypt session key (AES)
     * @return
     *      decrypted session key or null
     * @throws IOException
     *          on i/o errors
     */
    protected SecretKeySpec readSessionKey(InputStream in, 
            boolean allowEmpty, PrivateKey pkey) throws IOException {
        final byte[] key = new byte[TeleCrypt.SESSION_KEY_LEN];
        final int c = in.read(key);
        /*
         * In some cases relay does not respond any data,
         * if allowEmpty is set - this is legit
         */
        if (c <= 0 && allowEmpty)
            return null;

        // If we read less data than key size - key is broken
        // This is done for simplicity
        if (c != TeleCrypt.SESSION_KEY_LEN) {
            // key corrupted message
            LOG.warning(TeleportaError.messageFor(0x7009));
            return null;
        }
        // try to decrypt actual key, using own private
        final byte[] dec = tc.decryptKey(key, pkey);
        // try to restore it, to being used later
        return new SecretKeySpec(dec, "AES");
    }
    protected void checkFileHeader(InputStream in) throws IOException {
        // check for file magic header
        final byte[] head = new byte[TELEPORTED_FILE_HEADER.length];
        if (in.read(head)!= TELEPORTED_FILE_HEADER.length||
                !Arrays.equals(head,TELEPORTED_FILE_HEADER)) {
            throw TeleportaError.withError(0x7018);
        }
    }
    public static boolean checkPacketHeader(InputStream in,
                                      boolean allowEmpty) throws IOException {
        // check for magic header
        final byte[] head = new byte[TELEPORTA_PACKET_HEADER.length];
        final int c = in.read(head);
        if (c <= 0 && allowEmpty)
            return false; // stop next processing

        if (c != TELEPORTA_PACKET_HEADER.length||
                !Arrays.equals(head,TELEPORTA_PACKET_HEADER))
            throw TeleportaError.withError(0x7018);

        return true; // process next
    }
    /**
     * Uset User-Agent field for Teleporta client connections
     * @param hc
     * @param ctx
     */
    protected void setVersion(URLConnection hc,ClientRuntimeContext ctx) {
        hc.setRequestProperty("User-Agent", "Teleporta Portal/" + (ctx.respondVersion ?
                SystemInfo.SI.getBuildVersion()  : "Unknown"));
    }
    /**
     * Build portal name
     *
     * @return final portal name
     */
    public static String buildPortalName() {
        // try name from environment
        String portalName = System.getProperty("portalName", null);
        // try hostname
        if (portalName != null && !portalName.isEmpty())
            return portalName;

        final String hostName, userName = System.getProperty("user.name", "unknown");
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw TeleportaError.withError(0x6105, e);
        }
        // if template defined - try it
        final String portalNameTemplate = System.getProperty("portalNameTemplate", null);
        if (portalNameTemplate != null && !portalNameTemplate.isEmpty())
            portalName = portalNameTemplate
                    .replace("HOSTNAME", hostName)
                    .replace("USERNAME", userName);
        else
            portalName = hostName;

        // send unnamed
        if (portalName == null || portalName.isEmpty())
            portalName = "Unnamed portal";

        return portalName;
    }
    /**
     * This function just removes all non-necessary characters from provided public key data.
     * We need only numbers and ASCII symbols in lower case
     * @param data
     *          public key data
     *
     * @return
     *      cleaned data
     */
    public static String parseRelayPublicKey(byte[] data) {
        return new String(data)
                .replaceAll("[^a-z0-9]", "");
    }

    public KeyPair restoreKeyPair(byte[] data) throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (data==null || data.length==0) {
            return null;
        }
        final String sdata = new String(data);
        if (!sdata.contains("x")) {
            return null;
        }
        final String[] pair = sdata.split("x");

        final String pk = pair[0].replaceAll("[^a-z0-9]", ""),
                pr = pair[1].replaceAll("[^a-z0-9]", "");
        PublicKey pubkey = tc.restorePublicKey(fromHex(pk));
        PrivateKey privKey =  tc.restorePrivateKey(fromHex(pr));
        return new KeyPair(pubkey,privKey);
    }

    public static String dumpKeyPair(KeyPair kp) {
        return String.format("|TELEPORTA%sx%s",
                toHex(kp.getPublic().getEncoded(), 0, 0)
                .replaceAll(".{80}(?=.)", "$0\n"),
                toHex(kp.getPrivate().getEncoded(), 0, 0)
                .replaceAll(".{80}(?=.)", "$0\n"));
    }

    /**
     * Teleporta Client's execution context
     */
    protected static class ClientRuntimeContext {
        final File storageDir; // selected storage dir
        final URL relayUrl; // current relay url
        final boolean allowClipboard, // is clipboard allowed?
                allowOutgoing,  // if true - we allow outgoing files from this portal
                useLockFile,
                respondVersion;
        String sessionId, // a session id, generated by relay and used as authentication
                relayPublicKey,  // relay's public key
                savedKeyPair;
        final Map<String, TeleportaCommons.RegisteredPortal> portals = new LinkedHashMap<>();
        final Map<String, String> portalNames = new LinkedHashMap<>();
        KeyPair keyPair; // portal public&private keys
        final Set<String> processingFiles = new HashSet<>();
        final Set<String> downloadingFiles = new HashSet<>();
        ClientRuntimeContext(URL relayUrl, File storageDir,
                             boolean allowClipboard,
                             boolean allowOutgoing,
                             boolean useLockFile,
                             boolean respondVersion,
                             String savedKeyPair) {
            this.storageDir = storageDir;
            this.relayUrl = relayUrl;
            this.allowClipboard = allowClipboard;
            this.allowOutgoing = allowOutgoing;
            this.useLockFile = useLockFile;
            this.respondVersion = respondVersion;
            this.savedKeyPair = savedKeyPair;
        }
    }

    static class CountingZipOutputStream extends ZipOutputStream {
        private final long total;
        private final String fileId;
        private long count;
        /**
         * Wraps another output stream, counting the number of bytes written.
         *
         * @param out the output stream to be wrapped
         */
        public CountingZipOutputStream(String fileId,long total,OutputStream out) {
            super(out); this.fileId = fileId; this.total = total;
        }
        public String getFileId() {
            return fileId;
        }
        public int getPercent() {
            return (int)(count * 100.0 / total + 0.5);
        }
        /** Returns the number of bytes written. */
        public long getCount() {
            return count;
        }
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len); count += len;
        }
        @Override
        public void write(int b) throws IOException {
            super.write(b); count++;
        }
        // Overriding close() because FilterOutputStream's close() method pre-JDK8 has bad behavior:
        // it silently ignores any exception thrown by flush(). Instead, just close the delegate stream.
        // It should flush itself if necessary.
        @Override
        public void close() throws IOException {
            super.close();
        }
    }

    static class CountingZipInputStream extends ZipInputStream {
        private long count,mark = -1;
        private final long total;
        private final String fileId;
        /**
         * Wraps another input stream, counting the number of bytes read.
         *
         * @param in the input stream to be wrapped
         */
        public CountingZipInputStream(String fileId,long total,InputStream in) {
            super(in); this.total = total; this.fileId = fileId;
        }
        public String getFileId() {
            return fileId;
        }
        public int getPercent() {
            return (int)(count * 100.0 / total + 0.5);
        }
        /** Returns the number of bytes read. */
        public long getCount() {
            return count;
        }
        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result != -1) {
                count++;
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            final int result = super.read(b, off, len);
            if (result != -1) {
                count += result;
            }
            return result;
        }

        @Override
        public long skip(long n) throws IOException {
            final long result = super.skip(n);
            count += result;
            return result;
        }

        @Override
        public synchronized void mark(int readlimit) {
            super.mark(readlimit);
            mark = count;
            // it's okay to mark even if mark isn't supported, as reset won't work
        }

        @Override
        public synchronized void reset() throws IOException {
            if (!in.markSupported()) {
                throw new IOException("Mark not supported");
            }
            if (mark == -1) {
                throw new IOException("Mark not set");
            }
            super.reset();
            count = mark;
        }
    }
}
