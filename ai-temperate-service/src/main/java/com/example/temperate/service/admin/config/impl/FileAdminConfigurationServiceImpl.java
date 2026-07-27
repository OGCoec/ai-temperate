package com.example.temperate.service.admin.config.impl;

import com.example.temperate.common.validation.email.EmailAddressNormalizer;
import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfiguration;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import com.example.temperate.service.admin.config.AdminConfigurationSnapshot;
import com.example.temperate.service.admin.config.AdminConfigurationState;
import com.example.temperate.service.admin.config.AdminStatus;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 使用隐藏目录中的 YAML 和初始化标记实现单管理员配置状态机。
 *
 * <p>配置写入采用进程间文件锁、同目录临时文件、强制落盘和原子移动；初始化标记一旦存在便不会由应用自动删除，
 * 因此删除或截断 YAML 只能进入 CORRUPT，不能重新开放首次注册。</p>
 */
@Service
public final class FileAdminConfigurationServiceImpl implements AdminConfigurationService {

    private static final String MARKER_FILE = ".initialized";
    private static final String LOCK_FILE = ".lock";
    private static final long MAX_CONFIGURATION_BYTES = 16L * 1024L;
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern BCRYPT = Pattern.compile(
            "^\\{bcrypt}\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path configurationPath;
    private final Path directory;
    private final Path markerPath;
    private final Path lockPath;
    private final Clock clock;
    private final YAMLMapper yamlMapper;
    private final ReentrantLock processLock = new ReentrantLock();

    public FileAdminConfigurationServiceImpl(AdminProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.configurationPath = properties.configPath().toAbsolutePath().normalize();
        this.directory = Objects.requireNonNull(
                configurationPath.getParent(), "Admin config path requires a parent directory");
        this.markerPath = directory.resolve(MARKER_FILE);
        this.lockPath = directory.resolve(LOCK_FILE);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.yamlMapper = new YAMLMapper();
        this.yamlMapper.findAndRegisterModules();
        this.yamlMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.yamlMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public AdminConfigurationSnapshot inspect(boolean forceReload) {
        Instant checkedAt = clock.instant();
        EntryState configurationEntry = entryState(configurationPath);
        EntryState markerEntry = entryState(markerPath);
        if (configurationEntry == EntryState.ABSENT
                && markerEntry == EntryState.ABSENT) {
            return snapshot(
                    AdminConfigurationState.UNINITIALIZED,
                    "请完成首次管理员初始化。",
                    checkedAt);
        }
        if (configurationEntry != EntryState.REGULAR
                || markerEntry != EntryState.REGULAR) {
            return snapshot(
                    AdminConfigurationState.CORRUPT,
                    "管理员配置不完整，请检查隐藏配置文件。",
                    checkedAt);
        }
        try {
            AdminConfiguration configuration = readAndValidate();
            AdminConfigurationState state = configuration.status() == AdminStatus.ACTIVE
                    ? AdminConfigurationState.ACTIVE
                    : AdminConfigurationState.DISABLED;
            String message = state == AdminConfigurationState.ACTIVE
                    ? "管理员已初始化。"
                    : "管理员配置已停用。";
            return snapshot(state, message, checkedAt);
        } catch (RuntimeException | IOException exception) {
            return snapshot(
                    AdminConfigurationState.CORRUPT,
                    "管理员配置无法安全读取，请检查隐藏配置文件。",
                    checkedAt);
        }
    }

    @Override
    public AdminConfiguration requireActive() {
        AdminConfigurationSnapshot snapshot = inspect(false);
        if (snapshot.state() == AdminConfigurationState.UNINITIALIZED) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_NOT_INITIALIZED,
                    "Administrator has not been initialized.");
        }
        if (snapshot.state() == AdminConfigurationState.DISABLED) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_DISABLED,
                    "Administrator access is disabled.");
        }
        if (snapshot.state() != AdminConfigurationState.ACTIVE) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_CONFIG_INVALID,
                    "Administrator configuration is invalid.");
        }
        try {
            AdminConfiguration configuration = readAndValidate();
            // 状态检查后的第二次读取仍须再次确认 ACTIVE，避免文件在两次读取间被切换为 DISABLED。
            if (configuration.status() != AdminStatus.ACTIVE) {
                throw new AdminException(
                        AdminErrorCode.ADMIN_DISABLED,
                        "Administrator access is disabled.");
            }
            return configuration;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof AdminException adminException) {
                throw adminException;
            }
            throw new AdminException(
                    AdminErrorCode.ADMIN_CONFIG_INVALID,
                    "Administrator configuration is invalid.",
                    exception);
        }
    }

    @Override
    public void requireUninitialized() {
        AdminConfigurationState state = inspect(false).state();
        if (state == AdminConfigurationState.UNINITIALIZED) {
            return;
        }
        if (state == AdminConfigurationState.ACTIVE) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_ALREADY_INITIALIZED,
                    "Administrator is already initialized.");
        }
        if (state == AdminConfigurationState.DISABLED) {
            throw new AdminException(
                    AdminErrorCode.ADMIN_DISABLED,
                    "Administrator access is disabled.");
        }
        throw new AdminException(
                AdminErrorCode.ADMIN_CONFIG_INVALID,
                "Administrator configuration is invalid.");
    }

    @Override
    public void initialize(AdminConfiguration configuration) {
        AdminConfiguration valid = validate(configuration);
        // 进程内锁先消除同一 JVM 的重叠文件锁异常，文件锁再协调可能共享挂载目录的多个应用实例。
        processLock.lock();
        try {
            try {
                prepareDirectory();
                try (FileChannel channel = FileChannel.open(
                                lockPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                        FileLock ignored = channel.lock()) {
                    requireUninitialized();
                    writeConfiguration(valid, false);
                    // Marker 在完整配置落盘后创建；任何半完成状态都会在下一次检查时 Fail Closed。
                    writeMarker();
                }
            } catch (AdminException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new AdminException(
                        AdminErrorCode.ADMIN_INFRASTRUCTURE_UNAVAILABLE,
                        "Administrator configuration could not be initialized.",
                        exception);
            }
        } finally {
            processLock.unlock();
        }
    }

    @Override
    public void upgradePasswordHash(String expectedCurrentHash, String upgradedHash) {
        if (expectedCurrentHash == null || upgradedHash == null || !upgradedHash.startsWith("{")) {
            throw new IllegalArgumentException("Password hash upgrade input is invalid.");
        }
        processLock.lock();
        try {
            try {
                prepareDirectory();
                try (FileChannel channel = FileChannel.open(
                                lockPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                        FileLock ignored = channel.lock()) {
                    AdminConfiguration current = requireActive();
                    if (!MessageDigest.isEqual(
                            current.passwordHash().getBytes(StandardCharsets.UTF_8),
                            expectedCurrentHash.getBytes(StandardCharsets.UTF_8))) {
                        return;
                    }
                    AdminConfiguration upgraded = new AdminConfiguration(
                            current.schemaVersion(),
                            current.status(),
                            current.email(),
                            current.countryIso2(),
                            current.phoneE164(),
                            upgradedHash,
                            current.createdAt(),
                            clock.instant());
                    writeConfiguration(validate(upgraded), true);
                }
            } catch (AdminException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new AdminException(
                        AdminErrorCode.ADMIN_INFRASTRUCTURE_UNAVAILABLE,
                        "Administrator password hash could not be upgraded.",
                        exception);
            }
        } finally {
            processLock.unlock();
        }
    }

    private AdminConfiguration readAndValidate() throws IOException {
        long size = Files.size(configurationPath);
        if (size == 0L || size > MAX_CONFIGURATION_BYTES) {
            throw new IOException("Admin configuration size is invalid.");
        }
        return validate(yamlMapper.readValue(configurationPath.toFile(), AdminConfiguration.class));
    }

    private static AdminConfiguration validate(AdminConfiguration configuration) {
        if (configuration == null
                || configuration.schemaVersion() != AdminConfiguration.CURRENT_SCHEMA_VERSION
                || configuration.status() == null
                || configuration.email() == null
                || configuration.countryIso2() == null
                || configuration.phoneE164() == null
                || configuration.passwordHash() == null
                || configuration.createdAt() == null
                || configuration.updatedAt() == null) {
            throw invalidConfiguration();
        }
        String normalizedEmail;
        try {
            normalizedEmail = EmailAddressNormalizer.normalize(configuration.email());
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
        if (!normalizedEmail.equals(configuration.email())
                || !configuration.countryIso2().matches("^[A-Z]{2}$")
                || !E164.matcher(configuration.phoneE164()).matches()
                || !BCRYPT.matcher(configuration.passwordHash()).matches()
                || configuration.updatedAt().isBefore(configuration.createdAt())) {
            throw invalidConfiguration();
        }
        try {
            PhoneNumberUtil phoneNumbers = PhoneNumberUtil.getInstance();
            var parsed = phoneNumbers.parse(configuration.phoneE164(), "ZZ");
            if (!phoneNumbers.isValidNumber(parsed)
                    || !configuration.countryIso2().equals(
                            phoneNumbers.getRegionCodeForNumber(parsed))) {
                throw invalidConfiguration();
            }
        } catch (NumberParseException exception) {
            throw invalidConfiguration();
        }
        return configuration;
    }

    private void prepareDirectory() throws IOException {
        Files.createDirectories(directory);
        setPosixPermissions(directory, DIRECTORY_PERMISSIONS);
        DosFileAttributeView attributes =
                Files.getFileAttributeView(directory, DosFileAttributeView.class);
        if (attributes != null) {
            attributes.setHidden(true);
        }
    }

    private void writeConfiguration(AdminConfiguration configuration, boolean replace)
            throws IOException {
        Path temporary = Files.createTempFile(directory, ".complete-", ".yaml.tmp");
        try {
            byte[] yaml = yamlMapper.writeValueAsBytes(configuration);
            try (FileChannel output = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                output.write(ByteBuffer.wrap(yaml));
                output.force(true);
            }
            setPosixPermissions(temporary, FILE_PERMISSIONS);
            moveAtomically(temporary, configurationPath, replace);
            setPosixPermissions(configurationPath, FILE_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeMarker() throws IOException {
        try (FileChannel marker = FileChannel.open(
                markerPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            marker.write(ByteBuffer.wrap("initialized\n".getBytes(StandardCharsets.US_ASCII)));
            marker.force(true);
        }
        setPosixPermissions(markerPath, FILE_PERMISSIONS);
    }

    private static void moveAtomically(Path source, Path target, boolean replace)
            throws IOException {
        if (replace) {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 使用 ACL/DOS 属性；不支持 POSIX 权限时由部署账户和目录 ACL 提供访问隔离。
        }
    }

    private static AdminConfigurationSnapshot snapshot(
            AdminConfigurationState state,
            String message,
            Instant checkedAt) {
        return new AdminConfigurationSnapshot(
                state,
                state == AdminConfigurationState.UNINITIALIZED,
                state == AdminConfigurationState.ACTIVE,
                message,
                checkedAt);
    }

    private static EntryState entryState(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() ? EntryState.REGULAR : EntryState.INVALID;
        } catch (NoSuchFileException exception) {
            return EntryState.ABSENT;
        } catch (IOException | SecurityException exception) {
            // 权限错误、符号链接或不可识别文件类型都必须 Fail Closed，不能被误判为首次启动。
            return EntryState.INVALID;
        }
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("Administrator configuration is invalid.");
    }

    private enum EntryState {
        ABSENT,
        REGULAR,
        INVALID
    }
}
