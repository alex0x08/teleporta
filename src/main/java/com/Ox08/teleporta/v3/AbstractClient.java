package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.services.TeleLnk;

import javax.crypto.spec.SecretKeySpec;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.Ox08.teleporta.v3.TeleportaCommons.isWindows;
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
    
    /**
     * Creates link to Teleporta's home on Desktop
     * @param teleportaHome 
     *          path to Teleporta's home
     */
    protected static void createDesktopLink(File teleportaHome) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
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
            if (!lnk.exists()) {
                // for Windows, we need to create .lnk file manually,
                // because createSymbolicLink is not allowed
                // without Administrator permissions
                if (isWindows()) {
                    TeleLnk.createLnkFor(teleportaHome.toPath(),
                            new File(lnk.getParent(), lnk.getName() + ".lnk"));
                } else {
                    Files.createSymbolicLink(lnk.toPath(), teleportaHome.toPath());
                }
            }
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
        if (c <= 0 && allowEmpty) {
            return null;
        }
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

    /**
     * Build portal name
     *
     * @return final portal name
     */
    public static String buildPortalName() {
        // try name from environment
        String portalName = System.getProperty("portalName", null);
        // try hostname
        if (portalName != null && !portalName.isEmpty()) {
            return portalName;
        }
        final String hostName, userName = System.getProperty("user.name", "unknown");
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw TeleportaError.withError(0x6105, e);
        }
        // if template defined - try it
        final String portalNameTemplate = System.getProperty("portalNameTemplate", null);
        if (portalNameTemplate != null && !portalNameTemplate.isEmpty()) {
            portalName = portalNameTemplate
                    .replace("HOSTNAME", hostName)
                    .replace("USERNAME", userName);
        } else {
            portalName = hostName;
        }
        // send unnamed
        if (portalName == null || portalName.isEmpty()) {
            portalName = "Unnamed portal";
        }
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
}
