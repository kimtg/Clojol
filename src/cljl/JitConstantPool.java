package cljl;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal JVM classfile Constant Pool encoder used by {@link BytecodeJIT}.
 */
final class JitConstantPool {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
    private int count = 1;
    private final Map<String, Integer> utf8Map = new HashMap<>();
    private final Map<String, Integer> classMap = new HashMap<>();
    private final Map<String, Integer> strMap = new HashMap<>();
    private final Map<String, Integer> natMap = new HashMap<>();
    private final Map<String, Integer> refMap = new HashMap<>();
    private final Map<Integer, Integer> intMap = new HashMap<>();
    private final Map<Long, Integer> longMap = new HashMap<>();
    private final Map<Double, Integer> doubleMap = new HashMap<>();

    int addUtf8(String s) {
        return utf8Map.computeIfAbsent(s, k -> {
            try {
                ByteArrayOutputStream tmp = new ByteArrayOutputStream();
                DataOutputStream d = new DataOutputStream(tmp);
                d.writeByte(1);
                d.writeUTF(k);
                buf.write(tmp.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return count++;
        });
    }

    int addClass(String internalName) {
        return classMap.computeIfAbsent(internalName, k -> {
            int nameIdx = addUtf8(k);
            buf.write(7);
            writeShort(nameIdx);
            return count++;
        });
    }

    int addString(String s) {
        return strMap.computeIfAbsent(s, k -> {
            int utfIdx = addUtf8(k);
            buf.write(8);
            writeShort(utfIdx);
            return count++;
        });
    }

    int addInteger(int v) {
        return intMap.computeIfAbsent(v, k -> {
            buf.write(3);
            buf.write((k >>> 24) & 0xff);
            buf.write((k >>> 16) & 0xff);
            buf.write((k >>> 8) & 0xff);
            buf.write(k & 0xff);
            return count++;
        });
    }

    int addLong(long v) {
        return longMap.computeIfAbsent(v, k -> {
            buf.write(5);
            writeLong(k);
            int idx = count;
            count += 2;
            return idx;
        });
    }

    int addDouble(double v) {
        return doubleMap.computeIfAbsent(v, k -> {
            buf.write(6);
            writeLong(Double.doubleToRawLongBits(k));
            int idx = count;
            count += 2;
            return idx;
        });
    }

    int addNameAndType(String name, String desc) {
        String key = name + "\0" + desc;
        return natMap.computeIfAbsent(key, k -> {
            int n = addUtf8(name);
            int d = addUtf8(desc);
            buf.write(12);
            writeShort(n);
            writeShort(d);
            return count++;
        });
    }

    int addFieldref(String owner, String name, String desc) {
        return addRef(9, owner, name, desc);
    }

    int addMethodref(String owner, String name, String desc) {
        return addRef(10, owner, name, desc);
    }

    int addInterfaceMethodref(String owner, String name, String desc) {
        return addRef(11, owner, name, desc);
    }

    private int addRef(int tag, String owner, String name, String desc) {
        String key = tag + "\0" + owner + "\0" + name + "\0" + desc;
        return refMap.computeIfAbsent(key, k -> {
            int c = addClass(owner);
            int nat = addNameAndType(name, desc);
            buf.write(tag);
            writeShort(c);
            writeShort(nat);
            return count++;
        });
    }

    private void writeShort(int v) {
        buf.write((v >>> 8) & 0xff);
        buf.write(v & 0xff);
    }

    private void writeLong(long v) {
        for (int i = 56; i >= 0; i -= 8) {
            buf.write((int) ((v >>> i) & 0xff));
        }
    }

    void writeTo(DataOutputStream out) throws IOException {
        out.writeShort(count);
        out.write(buf.toByteArray());
    }
}
