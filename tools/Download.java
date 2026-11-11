import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

public class Download {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        Path out = Path.of(args[1]);
        String proxyHost = args.length > 2 ? args[2] : "127.0.0.1";
        int proxyPort = args.length > 3 ? Integer.parseInt(args[3]) : 10808;

        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(out));
        long ms = System.currentTimeMillis() - start;
        System.out.println("HTTP " + resp.statusCode() + " -> " + out + " (" + ms + " ms)");
    }
}
