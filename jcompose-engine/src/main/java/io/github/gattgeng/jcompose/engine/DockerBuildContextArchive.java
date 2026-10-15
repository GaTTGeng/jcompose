package io.github.gattgeng.jcompose.engine;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates Docker Engine build-context tars with Docker ignore rules and external Dockerfile staging. */
final class DockerBuildContextArchive implements AutoCloseable {
    private static final String EXTERNAL_DOCKERFILE_PREFIX = "__external_dockerfile__";

    private final Path archiveFile;
    private final String dockerfilePath;
    private final InputStream stream;

    private DockerBuildContextArchive(Path archiveFile, String dockerfilePath) throws IOException {
        this.archiveFile = archiveFile;
        this.dockerfilePath = dockerfilePath;
        this.stream = Files.newInputStream(archiveFile);
    }

    static DockerBuildContextArchive create(Path contextDirectory, Path dockerfile) throws IOException {
        Path context = contextDirectory.toRealPath();
        Path dockerfilePath = dockerfile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dockerfilePath))
            throw new IllegalArgumentException("Dockerfile does not exist or is not a regular file: " + dockerfilePath);

        Path dockerfileRealPath = dockerfilePath.toRealPath();
        boolean externalDockerfile = !dockerfileRealPath.startsWith(context);
        String archiveDockerfile = externalDockerfile
                ? EXTERNAL_DOCKERFILE_PREFIX + UUID.randomUUID() : toArchivePath(context, dockerfileRealPath);
        Path linkedDockerfile = externalDockerfile && dockerfilePath.startsWith(context) ? dockerfilePath : null;
        List<IgnoreRule> ignoreRules = readIgnoreRules(context, dockerfilePath);
        Path archive = Files.createTempFile("jcompose-build-context-", ".tar");
        try {
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                addDirectory(context, context, tar, ignoreRules, archiveDockerfile, linkedDockerfile);
                if (externalDockerfile) addFile(dockerfileRealPath, archiveDockerfile, tar);
                tar.finish();
            }
            return new DockerBuildContextArchive(archive, archiveDockerfile);
        } catch (IOException | RuntimeException | Error failure) {
            Files.deleteIfExists(archive);
            throw failure;
        }
    }

    String dockerfilePath() { return dockerfilePath; }
    InputStream stream() { return stream; }

    @Override public void close() throws IOException {
        try { stream.close(); }
        finally { Files.deleteIfExists(archiveFile); }
    }

    private static void addDirectory(Path root, Path directory, TarArchiveOutputStream tar, List<IgnoreRule> rules,
                                     String dockerfileArchive, Path linkedDockerfile) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path path : entries.sorted().toList()) {
                if (linkedDockerfile != null && path.equals(linkedDockerfile)) continue;
                String archivePath = toArchivePath(root, path);
                boolean directoryEntry = Files.isDirectory(path) && !Files.isSymbolicLink(path);
                boolean forcedDockerfile = archivePath.equals(dockerfileArchive);
                boolean ignored = !forcedDockerfile && isIgnored(archivePath, rules);
                if (directoryEntry) {
                    boolean includeDirectory = !ignored || mayContainDockerfile(archivePath, dockerfileArchive)
                            || mayBeReincluded(archivePath, rules);
                    if (includeDirectory) addDirectoryEntry(path, archivePath, tar);
                    addDirectory(root, path, tar, rules, dockerfileArchive, linkedDockerfile);
                } else if (!ignored) {
                    if (Files.isSymbolicLink(path)) addSymlink(path, archivePath, tar);
                    else if (Files.isRegularFile(path)) addFile(path, archivePath, tar);
                }
            }
        }
    }

    private static void addDirectoryEntry(Path path, String archivePath, TarArchiveOutputStream tar) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(archivePath + "/", TarArchiveEntry.LF_DIR);
        entry.setModTime(Files.getLastModifiedTime(path).toMillis());
        entry.setMode(mode(path, true));
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static void addFile(Path path, String archivePath, TarArchiveOutputStream tar) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(archivePath);
        entry.setSize(Files.size(path));
        entry.setModTime(Files.getLastModifiedTime(path).toMillis());
        entry.setMode(mode(path, false));
        tar.putArchiveEntry(entry);
        try (InputStream input = Files.newInputStream(path)) { input.transferTo(tar); }
        tar.closeArchiveEntry();
    }

    private static void addSymlink(Path path, String archivePath, TarArchiveOutputStream tar) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(archivePath, TarArchiveEntry.LF_SYMLINK);
        entry.setLinkName(Files.readSymbolicLink(path).toString().replace('\\', '/'));
        entry.setModTime(Files.getLastModifiedTime(path).toMillis());
        entry.setMode(0777);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static int mode(Path path, boolean directory) {
        try {
            Object rawMode = Files.getAttribute(path, "unix:mode");
            if (rawMode instanceof Number number) return number.intValue() & 07777;
        } catch (UnsupportedOperationException | IOException ignored) { }
        return directory ? 0755 : 0644;
    }

    private static List<IgnoreRule> readIgnoreRules(Path context, Path dockerfile) throws IOException {
        Path dockerfileIgnore = dockerfile.resolveSibling(dockerfile.getFileName() + ".dockerignore");
        Path dockerignore = Files.isRegularFile(dockerfileIgnore) ? dockerfileIgnore : context.resolve(".dockerignore");
        if (!Files.isRegularFile(dockerignore)) return List.of();
        List<IgnoreRule> rules = new ArrayList<>();
        List<String> lines = Files.readAllLines(dockerignore);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            String rule = line.trim();
            if (rule.isEmpty() || rule.startsWith("#")) continue;
            boolean include = rule.startsWith("!");
            String normalized = normalizePattern(include ? rule.substring(1) : rule);
            Pattern pattern = Pattern.compile(toIgnoreRegex(normalized));
            rules.add(new IgnoreRule(include, normalized, pattern));
        }
        return List.copyOf(rules);
    }

    private static String normalizePattern(String rawPattern) {
        String pattern = File.separatorChar == '\\' ? rawPattern.replace('\\', '/') : rawPattern;
        List<String> segments = new ArrayList<>();
        for (String segment : pattern.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..") && !segments.isEmpty() && !segments.get(segments.size() - 1).equals(".."))
                segments.remove(segments.size() - 1);
            else segments.add(segment);
        }
        return String.join("/", segments);
    }

    private static String toIgnoreRegex(String pattern) {
        if (pattern.isEmpty() || pattern.equals(".")) return "(?!)";
        StringBuilder expression = new StringBuilder("^");
        if (!pattern.contains("/")) expression.append("(?:.*/)?");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                expression.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                    i++;
                    expression.append("(?:.*/)?");
                } else expression.append(".*");
            } else if (c == '*') expression.append("[^/]*");
            else if (c == '?') expression.append("[^/]");
            else if (c == '[') {
                int end = pattern.indexOf(']', i + 1);
                if (end > i + 1) {
                    String group = pattern.substring(i + 1, end);
                    expression.append('[').append(group).append(']');
                    i = end;
                } else expression.append("\\[");
            } else {
                if ("\\.()+|{}^$".indexOf(c) >= 0) expression.append('\\');
                expression.append(c);
            }
        }
        return expression.append("(?:/.*)?$").toString();
    }

    private static boolean isIgnored(String path, List<IgnoreRule> rules) {
        boolean ignored = false;
        for (IgnoreRule rule : rules) {
            if (rule.pattern().matcher(path).matches()) ignored = !rule.include();
        }
        return ignored;
    }

    private static boolean mayBeReincluded(String directory, List<IgnoreRule> rules) {
        String prefix = directory + "/";
        return rules.stream().anyMatch(rule -> rule.include()
                && (rule.patternText().startsWith(prefix) || rule.patternText().contains("**")));
    }

    private static boolean mayContainDockerfile(String directory, String dockerfile) {
        return dockerfile.startsWith(directory + "/");
    }

    private static String toArchivePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private record IgnoreRule(boolean include, String patternText, Pattern pattern) { }
}
