package io.github.domsimus.keychain.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** File writes that never leave a half-written file behind, even after a crash or power cut. */
public final class SafeFiles {
    private SafeFiles() {}

    /**
     * Writes {@code content} to a temporary file next to {@code file}, forces it to disk, and only
     * then moves it over {@code file} in one step. Readers see the old content or the new content,
     * never a mix, and a power cut right after the move cannot leave an empty file. The file is
     * readable only by its owner where the file system supports that.
     */
    public static void writeAtomically(Path file, String content) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temporary = Files.createTempFile(folder, file.getFileName().toString(), ".tmp");
        try {
            restrictToOwner(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Owner-only permissions on Linux and macOS. On Windows the per-user profile folder's ACL applies. */
    public static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Not a POSIX file system.
        }
    }
}
