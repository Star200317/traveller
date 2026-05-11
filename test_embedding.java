import java.net.*;
import java.io.*;
import java.util.*;

public class test_embedding {
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("ERROR: DASHSCOPE_API_KEY not set");
            return;
        }
        
        String url = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
        
        // Test with text-embedding-v1 (default 1536 dim)
        String jsonBody = """
            {
                "model": "text-embedding-v1",
                "input": {
                    "texts": ["测试文本"]
                }
            }
            """;
        
        System.out.println("=== Testing text-embedding-v1 ===");
        System.out.println("Request body: " + jsonBody);
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes("UTF-8"));
        }
        
        int code = conn.getResponseCode();
        System.out.println("Response code: " + code);
        
        StringBuilder resp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code < 400 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) resp.append(line);
        }
        
        String response = resp.toString();
        System.out.println("Response: " + response);
        
        // Count embedding dimensions
        int embStart = response.indexOf("\"embedding\":[");
        if (embStart >= 0) {
            int arrayStart = embStart + "\"embedding\":[".length();
            int arrayEnd = response.indexOf("]", arrayStart);
            String array = response.substring(arrayStart, arrayEnd);
            long dims = Arrays.stream(array.split(",")).count();
            System.out.println("Embedding dimensions (v1): " + dims);
        }
        
        // Test with text-embedding-v3 + dimensions=1536
        System.out.println("\n=== Testing text-embedding-v3 with dimensions=1536 ===");
        String v3Body = """
            {
                "model": "text-embedding-v3",
                "input": {
                    "texts": ["测试文本"]
                },
                "parameters": {
                    "dimensions": 1536
                }
            }
            """;
        System.out.println("Request body: " + v3Body);
        
        HttpURLConnection conn2 = (HttpURLConnection) new URL(url).openConnection();
        conn2.setRequestMethod("POST");
        conn2.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn2.setRequestProperty("Content-Type", "application/json");
        conn2.setDoOutput(true);
        
        try (OutputStream os = conn2.getOutputStream()) {
            os.write(v3Body.getBytes("UTF-8"));
        }
        
        int code2 = conn2.getResponseCode();
        System.out.println("Response code: " + code2);
        
        StringBuilder resp2 = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code2 < 400 ? conn2.getInputStream() : conn2.getErrorStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) resp2.append(line);
        }
        
        String response2 = resp2.toString();
        System.out.println("Response: " + response2);
        
        int embStart2 = response2.indexOf("\"embedding\":[");
        if (embStart2 >= 0) {
            int arrayStart2 = embStart2 + "\"embedding\":[".length();
            int arrayEnd2 = response2.indexOf("]", arrayStart2);
            String array2 = response2.substring(arrayStart2, arrayEnd2);
            long dims2 = Arrays.stream(array2.split(",")).count();
            System.out.println("Embedding dimensions (v3+1536): " + dims2);
        }
    }
}
