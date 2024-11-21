package com.Ox08.teleporta.v3;
import com.Ox08.teleporta.v3.errors.TeleportationException;
import com.Ox08.teleporta.v3.messages.TeleportaError;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Teleporta Cryptography
 *
 * @author 0x08
 * @since 1.0
 */
public class TeleCrypt {
    public static final int SESSION_KEY_LEN = 256;

    public static final String SESSION_CYPHER= "AES/CBC/PKCS5Padding",
            PK_CYPHER = "RSA";
    /**
     * Decrypt session AES key with RSA private key
     * @param data
     *          source data
     * @param privateKey
     *          RSA private key
     * @return
     *      decrypted data
     */
    public byte[] decryptKey(byte[] data, Key privateKey) {
        try {
            /*
             * note: Cipher instances are *not* thread-safe and both decrypt and encrypt
             * actions could start in same time
             */
            final Cipher encryptCipher = Cipher.getInstance(PK_CYPHER);
            encryptCipher.init(Cipher.DECRYPT_MODE, privateKey);
            return encryptCipher.doFinal(data);
        } catch (InvalidKeyException | NoSuchAlgorithmException 
                | BadPaddingException | IllegalBlockSizeException 
                | NoSuchPaddingException e) {
            // error decrypting session key
            throw TeleportaError.withError(0x7014,e);
        }
    }
    /**
     * Encrypts session AES key with RSA public key
     * @param data
     *          AES key
     * @param publicKey
     *          RSA public key
     * @return
     *      encrypted key
     */
    public byte[] encryptKey(byte[] data, Key publicKey) {
        try {
            final Cipher encryptCipher = Cipher.getInstance(PK_CYPHER);
            encryptCipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return encryptCipher.doFinal(data);
        } catch (InvalidKeyException | NoSuchAlgorithmException 
                | BadPaddingException | IllegalBlockSizeException 
                | NoSuchPaddingException e) {
            // error encrypting session key
            throw TeleportaError.withError(0x7015,e);
        }
    }
    /**
     * Simply generates RSA key pair
     * @return
     *          public-private RSA keys
     * @throws NoSuchAlgorithmException
     *      shouldn't happen for RSA
     */
    public KeyPair generateKeys() throws NoSuchAlgorithmException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance(PK_CYPHER);
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
    /**
     * Decrypts data
     * @param key
     *          AES key
     * @param inputStream
     *          source stream
     * @param outputStream
     *          target stream
     */
    public void decryptData(SecretKey key,
                            InputStream inputStream, OutputStream outputStream) {
        try {
            final byte[] fileIv = new byte[16];
            // read stored IV
            if (inputStream.read(fileIv)!=16) {
                // incorrect IV size
                throw TeleportaError.withError(0x7012);
            }
            final Cipher cipher = Cipher.getInstance(SESSION_CYPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(fileIv));
            final byte[] buffer = new byte[4096];
            int bytesRead;
            // don't wrap in try-catch - don't close it there!
            final CipherInputStream cipherIn = new CipherInputStream(inputStream, cipher);
                while ((bytesRead = cipherIn.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
        } catch (TeleportationException | IOException 
                | InvalidAlgorithmParameterException 
                | InvalidKeyException | NoSuchAlgorithmException 
                | NoSuchPaddingException e) {
            throw TeleportaError.withError(0x7008,e);
        }
    }


    public void decryptFolder(SecretKey key,
                            InputStream inputStream, File zipFolder) {
        try {
            final byte[] fileIv = new byte[16];
            // read stored IV
            if (inputStream.read(fileIv)!=16) {
                // incorrect IV size
                throw TeleportaError.withError(0x7012);
            }
            final Cipher cipher = Cipher.getInstance(SESSION_CYPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(fileIv));
            // don't wrap in try-catch - don't close it there!
            final CipherInputStream cipherIn = new CipherInputStream(inputStream, cipher);
            final ZipInputStream zipIn = new ZipInputStream(cipherIn);
            for (ZipEntry ze; (ze = zipIn.getNextEntry()) != null; ) {
                final Path resolvedPath = zipFolder
                        .getParentFile().toPath().resolve(ze.getName());
                if (ze.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(zipIn, resolvedPath);
                }
            }

        } catch (TeleportationException | IOException
                 | InvalidAlgorithmParameterException
                 | InvalidKeyException | NoSuchAlgorithmException
                 | NoSuchPaddingException e) {
            throw TeleportaError.withError(0x7008,e);
        }
    }


    /**
     * Encrypt data
     * @param key
     *          session key (AES)
     * @param inputStream
     *          source stream
     * @param outputStream
     *          target stream
     */
    public void encryptData(SecretKey key,
                            InputStream inputStream, OutputStream outputStream) {
        try {
            final Cipher cipher = Cipher.getInstance(SESSION_CYPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, generateIv());
            // note: required custom implementation to avoid closing of parent stream
            final NonclosableCipherOutputStream cipherOut
                    = new NonclosableCipherOutputStream(outputStream, cipher);
            // store IV directly in file as first 16 bytes
            final byte[] iv = cipher.getIV();
            outputStream.write(iv);
            final byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                cipherOut.write(buffer, 0, bytesRead);
                cipherOut.flush();
            }
            cipherOut.doFinal();
        } catch (IOException | InvalidAlgorithmParameterException 
                | InvalidKeyException | NoSuchAlgorithmException 
                | NoSuchPaddingException e) {
            throw TeleportaError.withError(0x7007,e);
        }
    }


    /**
     * Encrypt & send folder to output stream
     * @param key
     * @param folder
     * @param outputStream
     */
    public void encryptFolder(SecretKey key,
                            File folder, OutputStream outputStream) {
        try {
            final Cipher cipher = Cipher.getInstance(SESSION_CYPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, generateIv());
            // note: required custom implementation to avoid closing of parent stream
            final NonclosableCipherOutputStream cipherOut
                    = new NonclosableCipherOutputStream(outputStream, cipher);
            // store IV directly in file as first 16 bytes
            final byte[] iv = cipher.getIV();
            outputStream.write(iv);
            final ZipOutputStream zos = new ZipOutputStream(cipherOut);

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

            cipherOut.doFinal();
        } catch (IOException | InvalidAlgorithmParameterException
                 | InvalidKeyException | NoSuchAlgorithmException
                 | NoSuchPaddingException e) {
            throw TeleportaError.withError(0x7007,e);
        }
    }
    /**
     *
     * So this function does stream re-encryption:
     *  1) decrypt block from source stream
     *  2) encrypt block with another key and push to another stream
     *  Yep, we *that* cool.
     *
     * @param key
     *          a key for source stream
     * @param key2
     *          key for target stream
     * @param inputStream
     *          source stream
     * @param outputStream
     *          target stream
     */
    public void rencryptData(SecretKey key, SecretKey key2,
                             InputStream inputStream, OutputStream outputStream) {
        try {
            final byte[] fileIv = new byte[16];
            // read stored IV
            if (inputStream.read(fileIv)!=16) {
                // incorrect IV size
                throw TeleportaError.withError(0x7012);
            }
            final Cipher cipher = Cipher.getInstance(SESSION_CYPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(fileIv));
            final Cipher cipher2 = Cipher.getInstance(SESSION_CYPHER);
            cipher2.init(Cipher.ENCRYPT_MODE, key2, generateIv());
            final NonclosableCipherOutputStream cipherOut
                    = new NonclosableCipherOutputStream(outputStream, cipher2);
            // store IV directly in file as first 16 bytes
            final byte[] iv = cipher2.getIV();
            outputStream.write(iv);
            final byte[] buffer = new byte[4096];
            int bytesRead;
            // don't wrap in try-catch - don't close it there!
            final CipherInputStream cipherIn = new CipherInputStream(inputStream, cipher);
            while ((bytesRead = cipherIn.read(buffer)) != -1) {
                cipherOut.write(buffer, 0, bytesRead);
            }
            cipherOut.doFinal();
        } catch (TeleportationException | IOException 
                | InvalidAlgorithmParameterException 
                | InvalidKeyException | NoSuchAlgorithmException 
                | NoSuchPaddingException e) {
            throw TeleportaError.withError(0x7013,e);
        }
    }

    /**
     * Restores RSA public key from byte array
     * @param data
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeySpecException
     */
    public PublicKey restorePublicKey(byte[] data)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        final KeyFactory publicKeyFactory = KeyFactory.getInstance(PK_CYPHER);
        return publicKeyFactory.generatePublic(new X509EncodedKeySpec(data));
    }
    /**
     * Generates AES key, used to encrypt file content
     * @return
     * @throws NoSuchAlgorithmException
     */
    public SecretKey generateFileKey() throws NoSuchAlgorithmException {
        final KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(128);
        return keyGenerator.generateKey();
    }
    /**
     * Generates intialization vector
     * <a href="https://docs.oracle.com/javase/8/docs/api/javax/crypto/spec/IvParameterSpec.html">...</a>
     * @return
     *          an IvParameter filed by random data
     */
    public IvParameterSpec generateIv() {
        final byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        return new IvParameterSpec(iv);
    }

    /**
     * copied from javax.crypto.CipherOutputStream with some changes
     */
    public static class NonclosableCipherOutputStream extends FilterOutputStream {
        private final Cipher cipher;
        private final OutputStream output;
        private final byte[] ibuffer = new byte[1];
        private byte[] obuffer;
        private boolean closed;
        public NonclosableCipherOutputStream(OutputStream var1, Cipher var2) {
            super(var1); this.output = var1; this.cipher = var2;
        }
        @Override
        public void write(int var1) throws IOException {
            this.ibuffer[0] = (byte) var1;
            this.obuffer = this.cipher.update(this.ibuffer, 0, 1);
            if (this.obuffer != null) {
                this.output.write(this.obuffer);
                this.obuffer = null;
            }
        }
        @Override
        public void write(byte[] var1) throws IOException {
            this.write(var1, 0, var1.length);
        }
        @Override
        public void write(byte[] var1, int var2, int var3) throws IOException {
            this.obuffer = this.cipher.update(var1, var2, var3);
            if (this.obuffer != null) {
                this.output.write(this.obuffer);
                this.obuffer = null;
            }
        }
        @Override
        public void flush() throws IOException {
            if (this.obuffer != null) {
                this.output.write(this.obuffer);
                this.obuffer = null;
            }
            this.output.flush();
        }
        /**
         * Originally it was close() method which calls  cypher finalize.
         * Because we're streaming right into the Moon - we need to do finalize BUT
         * without closing underlying stream.
         */
        public void doFinal() {
            if (!this.closed) {
                this.closed = true;
                try {
                    this.obuffer = this.cipher.doFinal();
                } catch (BadPaddingException | IllegalBlockSizeException var3) {
                    this.obuffer = null;
                }
                try {
                    this.flush();
                } catch (IOException ignored) {
                }
                // don't close upsteam, because we write directly to remote server!
                // this.out.close();
            }
        }
    }
    // test flow
    public static void main(String[] args) throws Exception {
        TeleCrypt tc = new TeleCrypt();
        KeyPair pair = tc.generateKeys();
        File pk = new File("./public-key");
        Files.write(pk.toPath(), pair.getPublic().getEncoded());
        byte[] restoredPK = Files.readAllBytes(pk.toPath());
        KeyFactory publicKeyFactory = KeyFactory.getInstance("RSA");
        EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(restoredPK);
        PublicKey publicKey = publicKeyFactory.generatePublic(publicKeySpec);
        //  RSAPublicKeySpec spec = new RSAPublicKeySpec()
        File src = new File("/home/alex/Downloads/weights.zip");
        SecretKey fileKey = tc.generateFileKey();
        byte[] key = fileKey.getEncoded();
        byte[] encKey = tc.encryptKey(key, publicKey);
        File skey = new File("./test.key");
        Files.write(skey.toPath(), encKey);
        File enc = new File("./test.enc");
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(enc)) {
            tc.encryptData(fileKey, in, out);
        }
        byte[] restored = Files.readAllBytes(skey.toPath());
        byte[] decKey = tc.decryptKey(restored, pair.getPrivate());
        SecretKeySpec rkey = new SecretKeySpec(decKey, "AES");
        File dec = new File("./test.dec");
        try (FileInputStream in = new FileInputStream(enc); FileOutputStream out = new FileOutputStream(dec)) {
            tc.decryptData(rkey, in, out);
        }
    }
}
