package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.messages.TeleportaError;
import com.Ox08.teleporta.v3.services.TeleLnk;

import javax.crypto.spec.SecretKeySpec;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
/**
 * Abstract parent client, that shares common functions between real Teleporta client and embedded, used on relay side
 *
 * @since 3.1.5
 * @author 0x08
 */
public abstract class AbstractClient {
    protected static final Logger LOG = Logger.getLogger("TC");
    protected TeleCrypt tc = new TeleCrypt();
    protected static void createDesktopLink(File teleportaHome) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
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
                // for Windows, we need to create .lnk file manually,
                // because createSymbolicLink is not allowed
                // without Administrator permissions
                if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
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
    protected SecretKeySpec readSessionKey(InputStream in, boolean allowEmpty, PrivateKey pkey) throws IOException {
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
     * Unpacks received zip file back to folder with files
     *
     * @param zipfolder zip archive with folder contents
     */
    public static void unpackFolder(final File zipfolder) {
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
            throw TeleportaError.withError(0x7004, e);
        }
    }
    /**
     * Pack folder into single ZIP file
     *
     * @param folder source folder
     * @return archived folder
     */
    public static File packFolder(File folder) {
        final File out = new File(folder.getParentFile(), folder.getName() + ".tmpzip");
        try (FileOutputStream fout = new FileOutputStream(out);
             final ZipOutputStream zos = new ZipOutputStream(fout)) {
            final Path pp = folder.toPath();
            try (Stream<Path> entries = Files.walk(pp)
                    .filter(path -> !Files.isDirectory(path))) {
                // folders will be added automatically
                entries.forEach(path -> {
                    final ZipEntry zipEntry = new ZipEntry(
                            (folder.getName()
                                    + '/'
                                    + pp.relativize(path))
                                    // ZIP requires / slash not \
                                    .replaceAll("\\\\", "/"));
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        // throw this exception to parent
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (Exception e) {
            throw TeleportaError.withError(0x7005, e);
        }
        return out;
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
    public static String parseRelayPublicKey(byte[] data) {
        return new String(data)
                .replaceAll("[^a-z0-9]", "");
    }
}
