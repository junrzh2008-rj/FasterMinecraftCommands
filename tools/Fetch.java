import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** Tiny Java-based HTTP utility: network works from the JVM even though the Windows shell TLS stack is sandbox-blocked. */
public class Fetch {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: Fetch <get|save> <url> [outFile]");
            return;
        }
        String mode = args[0];
        URI uri = URI.create(args[1]);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
        if (mode.equals("save")) {
            Path out = Paths.get(args[2]);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            HttpResponse<Path> r = client.send(req, HttpResponse.BodyHandlers.ofFile(out));
            System.out.println("HTTP " + r.statusCode() + " -> " + out.toAbsolutePath() + " (" + Files.size(out) + " bytes)");
            if (r.statusCode() >= 400) System.exit(2);
        } else {
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("HTTP " + r.statusCode());
            System.out.println(r.body());
            if (r.statusCode() >= 400) System.exit(2);
        }
    }
}
