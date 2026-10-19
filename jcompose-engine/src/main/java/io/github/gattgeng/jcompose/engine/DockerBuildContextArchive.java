package io.github.gattgeng.jcompose.engine;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/** Creates Docker Engine build-context tars with Docker ignore rules and external Dockerfile staging. */
final class DockerBuildContextArchive implements AutoCloseable {
    private static final String EXTERNAL_DOCKERFILE_PREFIX = "__external_dockerfile__";
    private static final String UNICODE_SCALAR = "(?:[^/\\uD800-\\uDFFF]|[\\uD800-\\uDBFF][\\uDC00-\\uDFFF])";

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
                addDirectory(context, context, archive, tar, ignoreRules, archiveDockerfile, linkedDockerfile);
                if (externalDockerfile) addFile(dockerfileRealPath, archiveDockerfile, tar);
                tar.finish();
            }
            return new DockerBuildContextArchive(archive, archiveDockerfile);
        } catch (IOException | RuntimeException | Error failure) {
            Files.deleteIfExists(archive);
            throw failure;
        }
    }

    static DockerBuildContextArchive createInline(Path contextDirectory, String dockerfileContents) throws IOException {
        Path context = contextDirectory.toRealPath();
        String archiveDockerfile = EXTERNAL_DOCKERFILE_PREFIX + UUID.randomUUID();
        List<IgnoreRule> ignoreRules = readIgnoreRules(context, null);
        Path archive = Files.createTempFile("jcompose-build-context-", ".tar");
        try {
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                addDirectory(context, context, archive, tar, ignoreRules, archiveDockerfile, null);
                addInlineFile(dockerfileContents, archiveDockerfile, tar);
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

    static Predicate<Path> watchIgnoreMatcher(Path context, Path dockerfile) throws IOException {
        Path root = context.toAbsolutePath().normalize();
        List<IgnoreRule> rules = readIgnoreRules(root, dockerfile);
        return candidate -> {
            Path path = candidate.toAbsolutePath().normalize();
            if (!path.startsWith(root)) return false;
            String relative = toArchivePath(root, path);
            return isIgnored(relative, rules) || containsGitDirectory(relative);
        };
    }

    @Override public void close() throws IOException {
        try { stream.close(); }
        finally { Files.deleteIfExists(archiveFile); }
    }

    private static void addDirectory(Path root, Path directory, Path temporaryArchive, TarArchiveOutputStream tar, List<IgnoreRule> rules,
                                     String dockerfileArchive, Path linkedDockerfile) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path path : entries.sorted().toList()) {
                if (path.equals(temporaryArchive)) continue;
                if (linkedDockerfile != null && path.equals(linkedDockerfile)) continue;
                String archivePath = toArchivePath(root, path);
                boolean directoryEntry = Files.isDirectory(path) && !Files.isSymbolicLink(path);
                boolean forcedDockerfile = archivePath.equals(dockerfileArchive);
                boolean ignored = !forcedDockerfile && isIgnored(archivePath, rules);
                if (directoryEntry) {
                    boolean includeDirectory = !ignored || mayContainDockerfile(archivePath, dockerfileArchive)
                            || mayBeReincluded(archivePath, rules);
                    if (includeDirectory) addDirectoryEntry(path, archivePath, tar);
                    if (includeDirectory || !ignored)
                        addDirectory(root, path, temporaryArchive, tar, rules, dockerfileArchive, linkedDockerfile);
                } else if (!ignored) {
                    if (Files.isSymbolicLink(path)) addSymlink(path, archivePath, tar);
                    else if (Files.isRegularFile(path)) addFile(path, archivePath, tar);
                    else addSpecialFile(path, archivePath, tar);
                }
            }
        }
    }

    private static void addSpecialFile(Path path, String archivePath, TarArchiveOutputStream tar) throws IOException {
        int mode = unixMode(path);
        switch (mode & 0xF000) {
            case 0x1000 -> addNamedPipe(path, archivePath, tar, mode);
            case 0xC000 -> { /* Unix sockets are not part of Docker build contexts. */ }
            case 0x2000, 0x6000 -> throw new UnsupportedOperationException(
                    "Build context contains an unsupported Unix device node at '" + path + "'.");
            default -> { /* Ignore filesystem entry types that cannot be represented as regular files. */ }
        }
    }

    private static void addNamedPipe(Path path, String archivePath, TarArchiveOutputStream tar, int mode) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(archivePath, TarArchiveEntry.LF_FIFO);
        entry.setModTime(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
        entry.setMode(mode & 07777);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static int unixMode(Path path) throws IOException {
        try {
            Object value = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return value instanceof Number number ? number.intValue() : 0;
        } catch (UnsupportedOperationException exception) {
            return 0;
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

    private static void addInlineFile(String contents, String archivePath, TarArchiveOutputStream tar) throws IOException {
        byte[] bytes = contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(archivePath);
        entry.setSize(bytes.length);
        entry.setMode(0644);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
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
        Path dockerfileIgnore = dockerfile == null ? null
                : dockerfile.resolveSibling(dockerfile.getFileName() + ".dockerignore");
        Path dockerignore = dockerfileIgnore != null && Files.isRegularFile(dockerfileIgnore)
                ? dockerfileIgnore : context.resolve(".dockerignore");
        if (!Files.isRegularFile(dockerignore)) return List.of();
        List<IgnoreRule> rules = new ArrayList<>();
        List<String> lines = Files.readAllLines(dockerignore);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.startsWith("#")) continue;
            String rule = line.trim();
            if (rule.isEmpty()) continue;
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
            else if (c == '?') expression.append(UNICODE_SCALAR);
            else if (c == '[') {
                int end = findCharacterClassEnd(pattern, i + 1);
                if (end > i + 1) {
                    appendCharacterClass(pattern.substring(i + 1, end), expression);
                    i = end;
                } else expression.append("\\[");
            } else {
                if ("\\.()+|{}^$".indexOf(c) >= 0) expression.append('\\');
                expression.append(c);
            }
        }
        return expression.append("(?:/.*)?$").toString();
    }

    private static int findCharacterClassEnd(String pattern, int start) {
        for (int index = start; index < pattern.length(); index++) {
            if (pattern.charAt(index) == '\\' && index + 1 < pattern.length()) index++;
            else if (pattern.charAt(index) == ']') return index;
        }
        return -1;
    }

    private static void appendCharacterClass(String content, StringBuilder expression) {
        boolean negated = content.length() > 1 && content.charAt(0) == '^';
        String members = negated ? content.substring(1) : content;
        List<ClassToken> tokens = new ArrayList<>();
        for (int index = 0; index < members.length();) {
            boolean escaped = members.charAt(index) == '\\' && index + 1 < members.length();
            if (escaped) index++;
            int scalar = members.codePointAt(index);
            tokens.add(new ClassToken(scalar, escaped));
            index += Character.charCount(scalar);
        }
        List<ScalarRange> ranges = new ArrayList<>();
        for (int index = 0; index < tokens.size();) {
            if (index + 2 < tokens.size() && !tokens.get(index + 1).escaped()
                    && tokens.get(index + 1).scalar() == '-') {
                int start = tokens.get(index).scalar();
                int end = tokens.get(index + 2).scalar();
                if (start <= end) ranges.add(new ScalarRange(start, end));
                index += 3;
            } else {
                int scalar = tokens.get(index++).scalar();
                ranges.add(new ScalarRange(scalar, scalar));
            }
        }
        String scalarClass = ranges.stream().map(DockerBuildContextArchive::scalarRangeRegex)
                .filter(regex -> !regex.isEmpty()).reduce((left, right) -> left + "|" + right).orElse("");
        if (scalarClass.isEmpty()) {
            expression.append(negated ? UNICODE_SCALAR : "(?!)");
        } else if (negated) {
            expression.append("(?!(?:").append(scalarClass).append("))").append(UNICODE_SCALAR);
        } else {
            expression.append("(?:").append(scalarClass).append(')');
        }
    }

    private static String scalarRangeRegex(ScalarRange range) {
        List<String> parts = new ArrayList<>();
        int start = range.start();
        int end = range.end();
        if (start <= 0xD7FF) parts.add(bmpRangeRegex(start, Math.min(end, 0xD7FF)));
        if (end >= 0xE000 && start <= 0xFFFF) parts.add(bmpRangeRegex(Math.max(start, 0xE000), Math.min(end, 0xFFFF)));
        if (end >= 0x10000) parts.add(supplementaryRangeRegex(Math.max(start, 0x10000), end));
        return parts.stream().filter(part -> !part.isEmpty())
                .reduce((left, right) -> "(?:" + left + "|" + right + ")").orElse("");
    }

    private static String bmpRangeRegex(int start, int end) {
        if (start > end) return "";
        return start == end ? "\\x{" + Integer.toHexString(start) + "}"
                : "[\\x{" + Integer.toHexString(start) + "}-\\x{" + Integer.toHexString(end) + "}]";
    }

    private static String supplementaryRangeRegex(int start, int end) {
        return start == end ? "\\x{" + Integer.toHexString(start) + "}"
                : "[\\x{" + Integer.toHexString(start) + "}-\\x{" + Integer.toHexString(end) + "}]";
    }

    private static boolean isIgnored(String path, List<IgnoreRule> rules) {
        boolean ignored = false;
        for (IgnoreRule rule : rules) {
            if (rule.pattern().matcher(path).matches()) ignored = !rule.include();
        }
        return ignored;
    }

    private static boolean containsGitDirectory(String path) {
        for (String segment : path.split("/")) if (segment.equals(".git")) return true;
        return false;
    }

    private static boolean mayBeReincluded(String directory, List<IgnoreRule> rules) {
        String[] directorySegments = directory.split("/");
        for (IgnoreRule rule : rules) {
            if (!rule.include()) continue;
            String[] patternSegments = rule.patternText().split("/");
            if (patternSegments.length == 1 || rule.patternText().contains("**")) return true;
            int count = Math.min(directorySegments.length, patternSegments.length);
            boolean matchesPrefix = true;
            for (int index = 0; index < count; index++) {
                if (!Pattern.compile(toIgnoreRegex(patternSegments[index])).matcher(directorySegments[index]).matches()) {
                    matchesPrefix = false;
                    break;
                }
            }
            if (matchesPrefix) return true;
        }
        return false;
    }

    private static boolean mayContainDockerfile(String directory, String dockerfile) {
        return dockerfile.startsWith(directory + "/");
    }

    private static String toArchivePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private record IgnoreRule(boolean include, String patternText, Pattern pattern) { }
    private record ClassToken(int scalar, boolean escaped) { }
    private record ScalarRange(int start, int end) { }
}
