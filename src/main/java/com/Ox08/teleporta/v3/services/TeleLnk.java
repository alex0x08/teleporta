package com.Ox08.teleporta.v3.services;
import java.io.*;
import java.nio.ByteOrder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.regex.Pattern;
/**
 * This class is based on amazing Dmitrii Shamrikov's work:
 *  <a href="https://github.com/BlackOverlord666/mslinks">...</a>
 *
 *  Because we don't need most of original library, we stripped code to one simple task:
 *     to create .lnk file on user's Desktop, that points to Teleporta's 'to' folder.
 *  That's all.
 *
 * @since 3.3.3
 * @author alex0x08
 */
public class TeleLnk {
    /**
     * Main function, that creates .lnk fie.
     * @param targetFolder
     *          target folder, to which link will point to
     * @param linkFile
     *          path to .lnk file
     * @throws IOException
     *          on i/o errors
     */
    public static void createLnkFor(Path targetFolder,File linkFile) throws IOException {
        final ShellLinkObj link = new ShellLinkObj();
        final String drive = targetFolder.getRoot().toString();
        String absolutePath = targetFolder.subpath(0, targetFolder.getNameCount()).toString();
        // root is computer
        link.idlist.add(new ItemIDRoot());
        // drive
        // windows usually creates TYPE_DRIVE_MISC here but TYPE_DRIVE_FIXED also works fine
        final ItemIDDrive driveItem = new ItemIDDrive(ItemID.TYPE_DRIVE_MISC,drive);
        link.idlist.add( driveItem);
        // each segment of the path is directory
        absolutePath = absolutePath.replaceAll("^([\\\\/])", "");
        String absoluteTargetPath = driveItem.getName() + absolutePath;
        link.info.setLocalBasePath(absoluteTargetPath);
        String[] path = absolutePath.split("[\\\\/]");
        for (String i : path) {
            link.idlist.add(new ItemIDFS(ItemID.TYPE_FS_DIRECTORY, i));
        }
        try (OutputStream out = new FileOutputStream(linkFile)) {
            link.serialize(out);
        }
    }

    public static void main(String[] args) throws IOException {
        Path targetFolder = Paths.get("c:/users/alex/Desktop").toAbsolutePath();

        createLnkFor(targetFolder, new File("testlink.lnk"));
    }
    static class GUID implements SerializableLinkObject {
        private final int d1;
        private final short d2, d3, d4;
        private final long d5;
        public GUID(String s) {
            if (s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}')
                s = s.substring(1, s.length() - 1);
            final String[] p = s.split("-");
            byte[] b = parse(p[0]);
            d1 = makeIntB(b[0], b[1], b[2], b[3]);
            b = parse(p[1]);
            d2 = makeShortB(b[0], b[1]);
            b = parse(p[2]);
            d3 = makeShortB(b[0], b[1]);
            d4 = (short) Long.parseLong(p[3], 16);
            d5 = Long.parseLong(p[4], 16);
        }
        private byte[] parse(String s) {
            byte[] b = new byte[s.length() >> 1];
            for (int i = 0, j = 0; j < s.length(); i++, j += 2)
                b[i] = (byte) Long.parseLong(s.substring(j, j + 2), 16);
            return b;
        }
        public void serialize(LinkDataWriter bw) throws IOException {
            bw.write4bytes(d1);
            bw.write2bytes(d2);
            bw.write2bytes(d3);
            bw.changeEndiannes();
            bw.write2bytes(d4);
            bw.write6bytes(d5);
            bw.changeEndiannes();
        }
        static short makeShortB(byte b0, byte b1) {
            return (short) ((b0 & 0xff) << 8 | (b1 & 0xff));
        }
        static int makeIntB(byte b0, byte b1, byte b2, byte b3) {
            return (b0 & 0xff) << 24 | (b1 & 0xff) << 16 | (b2 & 0xff) << 8 | (b3 & 0xff);
        }
    }
    static class LinkInfoFlags extends BitSet32 {
        public LinkInfoFlags(int n) {
            super(n);
            for (int i = 2; i < 32; i++)
                d = d & ~(1 << i);
            set(0);
        }
    }
    static class LinkFlags extends BitSet32 {
        public LinkFlags(int n) {
            super(n);
            clear(11);
            clear(16);
            for (int i = 27; i < 32; i++)
                clear(i);
            set(7); // set unicode
            set(0); // set has link target
            set(1); // has link info
        }
    }
    static class ItemIDRoot extends ItemIDRegItem {
        public ItemIDRoot() {
            super(GROUP_ROOT | TYPE_ROOT_REGITEM);
            clsid = new GUID("{20d04fe0-3aea-1069-a2d8-08002b30309d}"); //CLSID_COMPUTER
        }
    }
    abstract static class ItemIDRegItem extends ItemID {
        protected GUID clsid;
        public ItemIDRegItem(int flags) {
            super(flags);
        }
        @Override
        public void serialize(LinkDataWriter bw) throws IOException {
            super.serialize(bw);
            bw.write(0); // order
            clsid.serialize(bw);
        }
    }
    static class ItemIDFS extends ItemID {
        protected short attributes;
        protected String shortname, longname;
        public static final int FILE_ATTRIBUTE_DIRECTORY = 0x00000010;
        public ItemIDFS(int flags, String s) {
            super(flags | GROUP_FS);
            int subType = typeFlags & ID_TYPE_INGROUPMASK;
            if ((subType & TYPE_FS_DIRECTORY) != 0)
                attributes |= FILE_ATTRIBUTE_DIRECTORY;
            if (s == null || s.contains("\\"))
                throw new RuntimeException("wrong ItemIDFS name: " + s);
            longname = s;
            shortname = generateShortName(s);
            typeFlags |= TYPE_FS_UNICODE;
        }
        @Override
        public void serialize(LinkDataWriter bw) throws IOException {
            super.serialize(bw);
            bw.write(0);
            bw.write4bytes(0); //size;
            bw.write4bytes(0); // last modified
            bw.write2bytes(attributes);
            bw.writeUnicodeStringNullTerm(longname);
            bw.writeString(shortname);
        }
    }
    static class ItemIDDrive extends ItemID {
        protected String name;
        public ItemIDDrive(int flags,String s) {
            super(flags | GROUP_COMPUTER);
            int subType = typeFlags & ID_TYPE_INGROUPMASK;
            if (subType == 0)
                throw new RuntimeException(String.format("Incorrect type flags:%d", typeFlags));

            if (s == null)
                return;
            if (Pattern.matches("\\w:\\\\", s))
                name = s;
            else if (Pattern.matches("\\w:", s))
                name = s + "\\";
            else if (Pattern.matches("\\w", s))
                name = s + ":\\";
            else
                throw new RuntimeException("wrong drive name: " + s);
        }
        @Override
        public void serialize(LinkDataWriter bw) throws IOException {
            super.serialize(bw);
            bw.writeString(name);
            bw.write8bytes(0); // drive size
            bw.write8bytes(0); // drive free size
            bw.write(0); // no extension
            bw.write(0); // no clsid
        }

        public String getName() {
            return name;
        }

    }
    static class VolumeID implements SerializableLinkObject {
        public void serialize(LinkDataWriter bw) throws IOException {
            int size = 16;
            String label = "";
            byte[] label_b = label.getBytes();
            size += label_b.length + 1;
            boolean u = false;
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(label)) {
                size += 4 + 1 + 2;
                u = true;
            }
            bw.write4bytes(size);
            bw.write4bytes(3); //DRIVE_FIXED
            bw.write4bytes(0); //dsn
            int off = 16;
            if (u) off += 4;
            bw.write4bytes(off);
            off += label_b.length + 1;
            if (u) {
                off++;
                bw.write4bytes(off);
            }
            bw.write(label_b);
            bw.write(0);
            if (u) {
                bw.write(0);
                bw.write2bytes(0);
            }
        }
    }
    static class ItemID implements SerializableLinkObject {
        public static final int ID_TYPE_INGROUPMASK = 0x0f;
        public static final int TYPE_DRIVE_MISC = 0xf;
        // GROUP_FS - these values can be combined
        public static final int TYPE_FS_DIRECTORY = 0x1;
        public static final int TYPE_FS_UNICODE = 0x4;
        public static final int TYPE_ROOT_REGITEM = 0x0f;
        public static final int GROUP_FS = 0x30;
        public static final int GROUP_ROOT = 0x10;
        public static final int GROUP_COMPUTER = 0x20;
        protected int typeFlags;
        public ItemID(int flags) {
            this.typeFlags = flags;
        }
        @Override
        public void serialize(LinkDataWriter bw) throws IOException {
            bw.write(typeFlags);
        }
        protected static String generateShortName(String longname) {
            // assume that it is actually long, don't check it again
            longname = longname.replaceAll("\\.$|^\\.", "");
            int dotIdx = longname.lastIndexOf('.');
            String baseName = dotIdx == -1 ? longname : longname.substring(0, dotIdx);
            String ext = dotIdx == -1 ? "" : longname.substring(dotIdx + 1);
            ext = ext.replace(" ", "").replaceAll("[.\"/\\\\\\[\\]:;=,+]", "_");
            ext = ext.substring(0, Math.min(3, ext.length()));
            baseName = baseName.replace(" ", "").replaceAll("[.\"/\\\\\\[\\]:;=,+]", "_");
            baseName = baseName.substring(0, Math.min(6, baseName.length()));
            // well, for same short names we should use "~2", "~3" and so on,
            // but actual index is generated by os while creating a file and stored in filesystem
            // so it is not possible to get actual one
            StringBuilder shortname = new StringBuilder(baseName + "~1" + (ext.isEmpty() ? "" : "." + ext));
            // i have no idea how non-asci symbols are converted in dos names
            final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
            for (int i = 0; i < shortname.length(); ++i) {
                if (!asciiEncoder.canEncode(shortname.charAt(i)))
                    shortname.setCharAt(i, '_');
            }
            return shortname.toString().toUpperCase();
        }
    }
    static class FileAttributesFlags extends BitSet32 {
        public FileAttributesFlags(int n) {
            super(n);
            clear(3);
            clear(6);
            for (int i = 15; i < 32; i++)
                clear(i);
            set(4); // set directory
        }
    }
    static class BitSet32 implements SerializableLinkObject {
        int d;
        public BitSet32(int n) {
            d = n;
        }
        protected void set(int i) {
            d = (d & ~(1 << i)) | (1 << i);
        }
        protected void clear(int i) {
            d = d & ~(1 << i);
        }
        public void serialize(LinkDataWriter bw) throws IOException {
            bw.write4bytes(d);
        }
    }
    static class LinkDataWriter extends OutputStream {
        private boolean le = ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN);
        private final OutputStream stream;
        private int pos;
        public LinkDataWriter(OutputStream out) {
            stream = out;
        }
        public int getPosition() {
            return pos;
        }
        public void changeEndiannes() {
            le = !le;
        }
        @Override
        public void close() throws IOException {
            stream.close();
            super.close();
        }
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            pos += len;
            stream.write(b, off, len);
        }
        @Override
        public void write(int b) throws IOException {
            pos++;
            stream.write(b);
        }
        public void write(long b) throws IOException {
            write((int) b);
        }
        public void write2bytes(long n) throws IOException {
            if (le) {
                write(n & 0xff);
                write((n & 0xff00) >> 8);
            } else {
                write((n & 0xff00) >> 8);
                write(n & 0xff);
            }
        }
        public void write4bytes(long n) throws IOException {
            if (le) {
                write(n & 0xff);
                write((n & 0xff00) >> 8);
                write((n & 0xff0000) >> 16);
                write((n & 0xff000000L) >>> 24);
            } else {
                write((n & 0xff000000L) >>> 24);
                write((n & 0xff0000) >> 16);
                write((n & 0xff00) >> 8);
                write(n & 0xff);
            }
        }
        public void write6bytes(long n) throws IOException {
            long b0 = n & 0xff;
            long b1 = (n & 0xff00) >> 8;
            long b2 = (n & 0xff0000) >> 16;
            long b3 = (n & 0xff000000L) >>> 24;
            long b4 = (n & 0xff00000000L) >> 32;
            long b5 = (n & 0xff0000000000L) >> 40;
            if (le) {
                write(b0);
                write(b1);
                write(b2);
                write(b3);
                write(b4);
                write(b5);
            } else {
                write(b5);
                write(b4);
                write(b3);
                write(b2);
                write(b1);
                write(b0);
            }
        }
        public void write8bytes(long n) throws IOException {
            long b0 = n & 0xff;
            long b1 = (n & 0xff00) >> 8;
            long b2 = (n & 0xff0000) >> 16;
            long b3 = (n & 0xff000000L) >>> 24;
            long b4 = (n & 0xff00000000L) >> 32;
            long b5 = (n & 0xff0000000000L) >> 40;
            long b6 = (n & 0xff000000000000L) >> 48;
            long b7 = (n & 0xff00000000000000L) >>> 56;
            if (le) {
                write(b0);
                write(b1);
                write(b2);
                write(b3);
                write(b4);
                write(b5);
                write(b6);
                write(b7);
            } else {
                write(b7);
                write(b6);
                write(b5);
                write(b4);
                write(b3);
                write(b2);
                write(b1);
                write(b0);
            }
        }
        public void writeString(String s) throws IOException {
            write(s.getBytes());
            write(0);
        }
        public void writeUnicodeStringNullTerm(String s) throws IOException {
            for (int i = 0; i < s.length(); i++)
                write2bytes(s.charAt(i));
            write2bytes(0);
        }
    }
    interface SerializableLinkObject {
        void serialize(LinkDataWriter bw) throws IOException;
    }
    static class ShellLinkHeader implements SerializableLinkObject {
        private final LinkFlags lf = new LinkFlags(0);
        private final FileAttributesFlags faf = new FileAttributesFlags(0);
        public void serialize(LinkDataWriter bw) throws IOException {
            bw.write4bytes(0x0000004C); //header size
            final GUID clsid = new GUID("00021401-0000-0000-C000-000000000046");
            clsid.serialize(bw);
            lf.serialize(bw);
            faf.serialize(bw);
            final GregorianCalendar tmp = new GregorianCalendar();
            tmp.add(Calendar.YEAR, 369);
            // create, access ,mtime
            bw.write8bytes(tmp.getTimeInMillis());
            bw.write8bytes(tmp.getTimeInMillis());
            bw.write8bytes(tmp.getTimeInMillis());
            bw.write4bytes(0);
            bw.write4bytes(0);
            bw.write4bytes(1); //SW_SHOWNORMAL
            //	hkf.serialize(bw);
            bw.write(0);
            bw.write(0);
            bw.write2bytes(0);
            bw.write8bytes(0);
        }
    }
    static class LinkInfo implements SerializableLinkObject {
        private final LinkInfoFlags lif = new LinkInfoFlags(0);
        private final VolumeID vid = new VolumeID();
        private String localBasePath = "";
        public void serialize(LinkDataWriter bw) throws IOException {
            int pos = bw.getPosition();
            int hsize = 28;
            final CharsetEncoder ce = StandardCharsets.US_ASCII.newEncoder();
            if (localBasePath != null && !ce.canEncode(localBasePath))
                hsize += 8;
            byte[] vid_b, localBasePath_b, commonPathSuffix_b;
            vid_b = toByteArray(vid);
            localBasePath_b = localBasePath.getBytes();
            commonPathSuffix_b = new byte[0];
            int size = hsize + vid_b.length + localBasePath_b.length + 1 + commonPathSuffix_b.length + 1;
            if (hsize > 28) {
                size += localBasePath.length() * 2 + 2;
                size += 1;
                size += 2;
            }
            bw.write4bytes(size);
            bw.write4bytes(hsize);
            lif.serialize(bw);
            int off = hsize;
            bw.write4bytes(off); // volumeid offset
            off += vid_b.length;
            bw.write4bytes(off); // localBasePath offset
            off += localBasePath_b.length + 1;
            bw.write4bytes(0); // CommonNetworkRelativeLinkOffset
            bw.write4bytes(size - (hsize > 28 ? 4 : 1)); // fake commonPathSuffix offset
            if (hsize > 28) {
                bw.write4bytes(off); // LocalBasePathOffsetUnicode
                bw.write4bytes(size - 2); // fake CommonPathSuffixUnicode offset
            }
            bw.write(vid_b);
            bw.write(localBasePath_b);
            bw.write(0);
            if (hsize > 28) {
                bw.writeUnicodeStringNullTerm(localBasePath);
            }
            while (bw.getPosition() < pos + size)
                bw.write(0);
        }
        private byte[] toByteArray(SerializableLinkObject o) throws IOException {
            try (ByteArrayOutputStream arr = new ByteArrayOutputStream(); LinkDataWriter bt = new LinkDataWriter(arr);) {
                o.serialize(bt);
                return arr.toByteArray();
            }
        }

        public void setLocalBasePath(String s) {
            if (s == null) return;
            localBasePath = s;
        }
    }
    static class ShellLinkObj {
        private final ShellLinkHeader header = new ShellLinkHeader();
        final LinkTargetIDList idlist = new LinkTargetIDList();
        final LinkInfo info = new LinkInfo();
        public void serialize(OutputStream out) throws IOException {
            try (LinkDataWriter bw = new LinkDataWriter(out);) {
                header.serialize(bw);
                idlist.serialize(bw);
                info.serialize(bw);
                bw.write4bytes(0);
            }
        }
    }
    static class LinkTargetIDList extends LinkedList<ItemID> implements SerializableLinkObject {
        public void serialize(LinkDataWriter bw) throws IOException {
            int size = 2;
            byte[][] b = new byte[size()][];
            int i = 0;
            for (ItemID j : this) {
                try (ByteArrayOutputStream ba = new ByteArrayOutputStream(); LinkDataWriter w = new LinkDataWriter(ba);) {
                    j.serialize(w);
                    b[i++] = ba.toByteArray();
                }
            }
            for (byte[] j : b)
                size += j.length + 2;
            bw.write2bytes(size);
            for (byte[] j : b) {
                bw.write2bytes(j.length + 2);
                bw.write(j);
            }
            bw.write2bytes(0);
        }
    }


}
