import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates the release zip (avoiding PowerShell Compress-Archive, which is sandbox-hostile here). */
public final class MakeZip {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.deleteIfExists(output);
        try (OutputStream fileOut = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut), StandardCharsets.UTF_8)) {
            for (int i = 1; i < args.length; i++) {
                String spec = args[i];
                int eq = spec.indexOf('=');
                String source = spec.substring(0, eq);
                String entryName = spec.substring(eq + 1);
                Path path = Paths.get(source);
                if (Files.isDirectory(path)) {
                    List<Path> files = new ArrayList<>();
                    try (var stream = Files.walk(path)) {
                        stream.filter(Files::isRegularFile).forEach(files::add);
                    }
                    files.sort(Path::compareTo);
                    for (Path file : files) {
                        String relative = path.relativize(file).toString().replace('\\', '/');
                        addFile(zip, file, entryName + "/" + relative);
                    }
                } else {
                    addFile(zip, path, entryName);
                }
            }
        }
        System.out.println("wrote " + output.toAbsolutePath() + " (" + Files.size(output) + " bytes)");
    }

    private static void addFile(ZipOutputStream zip, Path file, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(Files.getLastModifiedTime(file).toMillis());
        zip.putNextEntry(entry);
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
