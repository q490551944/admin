package com.hpj.admin.monitor.support;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Test-owned localhost certificates; no global trust changes and no private material in diagnostics. */
public final class MonitoringTlsMaterial implements AutoCloseable {
    public static final String CONTAINER_KEY = "/usr/share/elasticsearch/config/monitor-http.key";
    public static final String CONTAINER_CERTIFICATE = "/usr/share/elasticsearch/config/monitor-http.crt";
    private final Path root;
    private final Path directory;
    private final byte[] keyPem;
    private final byte[] certificatePem;
    private final SSLContext trustedClient;
    private final SSLContext untrustedClient;
    private final SSLContext server;
    private boolean closed;

    private MonitoringTlsMaterial(Path root, Path directory, byte[] keyPem, byte[] certificatePem,
                                  SSLContext trustedClient, SSLContext untrustedClient, SSLContext server) {
        this.root = root;
        this.directory = directory;
        this.keyPem = keyPem;
        this.certificatePem = certificatePem;
        this.trustedClient = trustedClient;
        this.untrustedClient = untrustedClient;
        this.server = server;
    }

    public static MonitoringTlsMaterial create() throws IOException {
        Path root = Files.createDirectories(Path.of("target", "monitor-tls").toAbsolutePath().normalize()).toRealPath();
        Path directory = Files.createTempDirectory(root, "es-");
        char[] password = UUID.randomUUID().toString().toCharArray();
        byte[] keyBytes = null;
        try {
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            }
            KeyStore keys = generate(directory.resolve("http.p12"), password);
            KeyStore other = generate(directory.resolve("other.p12"), password);
            X509Certificate certificate = (X509Certificate) keys.getCertificate("http");
            certificate.checkValidity();
            PrivateKey key = (PrivateKey) keys.getKey("http", password);
            if (!"PKCS#8".equals(key.getFormat())) throw new IOException("Unsupported test private-key format");
            keyBytes = key.getEncoded();
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password);
            SSLContext server = SSLContext.getInstance("TLS");
            server.init(managers.getKeyManagers(), null, new SecureRandom());
            return new MonitoringTlsMaterial(root, directory, pem("PRIVATE KEY", keyBytes),
                    pem("CERTIFICATE", certificate.getEncoded()), trusted(certificate),
                    trusted((X509Certificate) other.getCertificate("http")), server);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            try { removeDirectory(root, directory); } catch (IOException ignored) { }
            throw new IOException("Cannot create owned TLS test material");
        } finally {
            Arrays.fill(password, '\0');
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    public SSLContext trustedClientContext() { checkOpen(); return trustedClient; }
    public SSLContext untrustedClientContext() { checkOpen(); return untrustedClient; }
    public SSLContext serverContext() { checkOpen(); return server; }

    /** Copies only into this caller-owned test container, readable by the image's uid 1000. */
    public void copyTo(GenericContainer<?> container) {
        checkOpen();
        container.withCopyToContainer(ownedFile(keyPem), CONTAINER_KEY)
                .withCopyToContainer(ownedFile(certificatePem), CONTAINER_CERTIFICATE);
    }

    private static KeyStore generate(Path file, char[] password) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        ProcessBuilder builder = new ProcessBuilder(List.of(keytool.toString(), "-genkeypair", "-alias", "http",
                "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass:env", "MONITOR_ES_TLS_STOREPASS",
                "-keypass:env", "MONITOR_ES_TLS_STOREPASS", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-ext", "EKU=serverAuth",
                "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "BC=ca:false", "-validity", "2", "-noprompt"));
        builder.environment().put("MONITOR_ES_TLS_STOREPASS", new String(password));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Owned TLS key generation failed");
            }
        } finally {
            builder.environment().remove("MONITOR_ES_TLS_STOREPASS");
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) { keys.load(input, password); }
        return keys;
    }

    private static SSLContext trusted(X509Certificate certificate) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("owned-server", certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static byte[] pem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return ("-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static Transferable ownedFile(byte[] bytes) {
        return new Transferable() {
            @Override public long getSize() { return bytes.length; }
            @Override public byte[] getBytes() { return bytes; }
            @Override public int getFileMode() { return 0100400; }
            @Override public void transferTo(TarArchiveOutputStream output, String destination) {
                TarArchiveEntry entry = new TarArchiveEntry(destination);
                entry.setUserId(1000);
                entry.setGroupId(0);
                entry.setMode(getFileMode());
                entry.setSize(bytes.length);
                try {
                    output.putArchiveEntry(entry);
                    output.write(bytes);
                    output.closeArchiveEntry();
                } catch (IOException failure) { throw new IllegalStateException("Cannot copy owned TLS material"); }
            }
        };
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Owned TLS material already closed");
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        Arrays.fill(keyPem, (byte) 0);
        Arrays.fill(certificatePem, (byte) 0);
        removeDirectory(root, directory);
        closed = true;
    }

    private static void removeDirectory(Path root, Path directory) throws IOException {
        if (!directory.toAbsolutePath().normalize().getParent().equals(root) || !directory.getFileName().toString().startsWith("es-")) {
            throw new IOException("Unexpected TLS material ownership");
        }
        if (!Files.exists(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override public String toString() { return "MonitoringTlsMaterial[owned-localhost]"; }
}
