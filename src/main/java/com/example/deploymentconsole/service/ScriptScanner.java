package com.example.deploymentconsole.service;

import com.example.deploymentconsole.model.ScriptInfo;
import com.example.deploymentconsole.model.ScriptStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@Service
public class ScriptScanner {
    private static final Pattern SEQUENCE =
            Pattern.compile("^(?:V)?(\\d+)(?:[_\\-.]|__).*\\.sql$", Pattern.CASE_INSENSITIVE);

    public List<ScriptInfo> scan(String folder) throws IOException {
        Path root = Paths.get(folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Folder does not exist: " + root);

        List<Temp> temp = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                  .forEach(p -> {
                      String name = p.getFileName().toString();
                      Matcher m = SEQUENCE.matcher(name);
                      long seq = m.matches() ? Long.parseLong(m.group(1)) : Long.MAX_VALUE;
                      temp.add(new Temp(p, root.relativize(p).toString(), name, seq));
                  });
        }
        temp.sort(Comparator.comparingLong((Temp x) -> x.sequence)
                .thenComparing(x -> x.relative, String.CASE_INSENSITIVE_ORDER));

        List<ScriptInfo> result = new ArrayList<>();
        int n=1;
        for (Temp x: temp) {
            result.add(new ScriptInfo(n++, x.relative, x.filename, x.sequence,
                    ScriptStatus.PENDING, null, null));
        }
        return result;
    }

    public Path resolve(String folder, String relativePath) {
        Path root = Paths.get(folder).toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid script path");
        return file;
    }

    private record Temp(Path path, String relative, String filename, long sequence) {}
}
