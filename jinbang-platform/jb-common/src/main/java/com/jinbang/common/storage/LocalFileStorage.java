package com.jinbang.common.storage;

import com.jinbang.common.core.BizException;
import com.jinbang.common.core.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(@Value("${jb.storage.local-dir:./data/files}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public String put(String folder, String extension, byte[] content) {
        LocalDate d = LocalDate.now();
        String ext = extension == null || extension.isBlank() ? "" : "." + extension.replaceAll("[^A-Za-z0-9]", "");
        String key = safe(folder) + "/" + d.getYear() + "/" + d.getMonthValue() + "/" + UUID.randomUUID() + ext;
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法的文件路径");
        }
        return p;
    }

    private String safe(String folder) {
        return folder == null ? "misc" : folder.replaceAll("[^A-Za-z0-9_/-]", "").replaceAll("\\.\\.", "");
    }
}
