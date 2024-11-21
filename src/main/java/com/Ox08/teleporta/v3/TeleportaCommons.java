package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Random;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * Shared functions and classes, used both on relay and client sides
 *
 * @author 0x08
 * @since 1.0
 */
public class TeleportaCommons {
    private final static Logger LOG = Logger.getLogger("TC");
    //public static final String FOLDERZIP_EXT = ".tmpzip";
    // DTO to store portal details
    public static class RegisteredPortal {
        final String name; // unique portal name (human readable)
                String publicKey;  // portal's public key
        RegisteredPortal(String name, String publicKey) {
            this.name = name;
            this.publicKey = publicKey;
        }
    }
    /**
     * Generates unique ID (more-less)
     *
     * @return pseudo-random ID
     */
    public static long generateUniqueID() {
        return (System.currentTimeMillis() << 20)
                | (System.nanoTime() & ~9223372036854251520L);
    }
    /**
     * Extracts relay seed from url
     *
     * @param seed url path
     * @return seed, used to generate urls for endpoints
     */
    public static char[] extractSeed(String seed) {
        if (seed == null || seed.isEmpty()) {
            // cannot extract seed
            throw TeleportaError.withError(0x7215);
        }
        seed = seed.toLowerCase().replaceAll("/", "");
        if (seed.isEmpty()) {
            // second check that cannot extract seed
            throw TeleportaError.withError(0x7215);
        }
        // remove random garbage first
        if (seed.length() > 22) {
            seed = seed.substring(0, 22);
        }
        return seed.toCharArray();
    }
    /**
     * Generates random number between provided range
     *
     * @param r   Random instance
     * @param min minumum int
     * @param max maximum int
     * @return random number
     */
    public static int randomNum(Random r, int min, int max) {
        return (int) ((r.nextDouble() * (max - min)) + min);
    }
    /**
     * Build server URL from seed
     *
     * @param seed provided seed
     * @param url  human readable URL part
     * @return generated server url
     */
    public static String buildServerUrl(String seed, String url) {
        final byte[] url1 = deriveKey(extractSeed(seed), url.getBytes());
        return toHex(url1, 0, 8);
    }

    public static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ignored) {
            return -1;
        }
    }
    /**
     * Decode URL endpoint
     *
     * @param url  remote relay url
     * @param part human-readable part
     * @return final url endpoint
     */
    public static String decodeUrl(URL url, String part) {
        // use seed to calculate actual url
        final byte[] url1 = deriveKey(extractSeed(url.getPath()), part.getBytes());
        return toHex(url1, 0, 8);
    }
    public static byte[] deriveKey(char[] sharedSecret, byte[] info) {
        try {
            final SecretKeyFactory kf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            final KeySpec specs = new PBEKeySpec(sharedSecret, info, 1024, 256);
            final SecretKey key = kf.generateSecret(specs);
            return key.getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }
    public static String genSeed() {
        final SecureRandom rand = new SecureRandom();
        final int r = randomNum(rand, 5, 25);
        final byte[] randBytes = new byte[22 + r];
        rand.nextBytes(randBytes);
        return toHex(randBytes, 0, 0);
    }
    public static byte[] fromHex(String hex) {
        final int l = hex.length();
        byte[] data = new byte[l / 2];
        for (int i = 0; i < l; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
    public static String toHex(byte[] data, int from, int to) {
        final Formatter formatter = new Formatter();
        for (int i = from; i < (to <= 0 ? data.length : to); i++) {
            formatter.format("%02x", data[i]);
        }
        return formatter.toString();
    }
    public static File checkCreateHomeFolder(String prefix) {
        final File teleportaHome;
        if (!System.getProperties().containsKey("appHome")) {
            final String userHome = System.getProperty("user.home");
            teleportaHome = Paths.get(userHome, ".apps", prefix).toFile();
            checkCreateFolder(teleportaHome);
        } else {
            teleportaHome = new File(System.getProperty("appHome"));
        }
        return teleportaHome;
    }
    public static void checkCreateFolder(File folder) {
        if ((!folder.exists() || !folder.isDirectory()) && !folder.mkdirs()) {
            throw TeleportaError.withError(0x6109,
                            folder.getAbsolutePath());
        }
    }

    /**
     * Delete specified folder recursively
     * @param file
     *          folder or file to remove
     * @param removeParent
     *          if true - also removes specified folder, otherwise - just inner content
     */
    public static void deleteRecursive(File file, boolean removeParent) {
        if (file.isFile()) {
            if (!file.delete()) {
                // cannot delete file
                LOG.warning(TeleportaError.messageFor(0x6106,
                        file.getAbsolutePath()));
            }
            return;
        }
        if (!file.isDirectory()) {
            // could be a link also - skip it
            return;
        }
        final File[] files = file.listFiles();
        // java allows to remove folder only if its empty
        if ((files == null || files.length == 0)) {
            if (!file.delete()) {
                LOG.warning(TeleportaError.messageFor(0x6110,
                        file.getAbsolutePath()));
            }
            return;
        }
        for (File f : files) {
            deleteRecursive(f, true);
        }
        if (removeParent && !file.delete()) {
            LOG.warning(TeleportaError.messageFor(0x6110, file.getAbsolutePath()));
        }
    }

    /**
     *  Setup logging
     * @param debug
     *          true - enable debug messages
     */
    public static void setLogging(boolean debug) {
        if (debug) {
            // setup logging for client connection
            Logger.getLogger("sun.net.www.protocol.http.HttpURLConnection")
                    .setLevel(Level.ALL);
            // setup logging for relay connections
            Logger.getLogger("com.sun.net.httpserver")
                    .setLevel(Level.ALL);
        }
        final Logger topLogger = Logger.getLogger("");
        // Handler for console (reuse it if it already exists)
        Handler consoleHandler = null;
        //see if there is already a console handler
        for (Handler handler : topLogger.getHandlers()) {
                if (handler instanceof ConsoleHandler) {
                    //found the console handler
                    consoleHandler = handler;
                    break;
                }
        }
        
        if (consoleHandler == null) {
                consoleHandler = new ConsoleHandler();
                topLogger.addHandler(consoleHandler);
        }
        // note: we need to change console encoding for Windows & Russian locale,
        //       because it uses different codepages for UI and console
        //       and Charset.defaultCharset() responds cp1251 where console
        //       actually uses cp866
        try  {
            // contains because it can be cp1251 and windows-1251
            if (isWindows() && Charset.defaultCharset().name().contains("1251")) {
                consoleHandler.setEncoding("cp866");
            }
        } catch (UnsupportedEncodingException e) {
            throw TeleportaError.withError(0x600,e);
        }
        if (debug) {
            consoleHandler.setLevel(Level.ALL);
            LOG.setLevel(Level.ALL);
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    /**
     * Custom HashMap implementation,that allows lookup by key and by value
     * @param <K>
     * @param <V>
     */
    public static class BidirectionalMap<K, V> extends HashMap<K, V> {
        public final HashMap<V, K> inversedMap = new HashMap<>();
        private final Object lock = new Object();
        public K getKey(V value) {
            return inversedMap.get(value);
        }
        @Override
        public V remove(Object key) {
            final V val;
            synchronized (lock) {
                val = super.remove(key);
                inversedMap.remove(val);
            }
            return val;
        }
        @Override
        public V get(Object key) {
            return super.get(key);
        }
        @Override
        public V put(K key, V value) {
            synchronized (lock) {
                inversedMap.put(value, key);
                return super.put(key, value);
            }
        }
    }
}
