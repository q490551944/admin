package com.hpj.admin.monitor;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Reproduces the archive operation Testcontainers uses to install Kafka's startup script. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = ".+")
class MonitoringArchiveCompatibilityTest {
    @Test
    void writesAndReadsStartupScriptArchiveWithTheResolvedTestDependencies() throws Exception {
        byte[] script = "#!/bin/sh\necho fixture\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("test-start.sh");
            entry.setSize(script.length);
            entry.setMode(0700);
            archive.putArchiveEntry(entry);
            archive.write(script);
            archive.closeArchiveEntry();
        }
        try (TarArchiveInputStream archive = new TarArchiveInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(archive.getNextEntry().getName()).isEqualTo("test-start.sh");
            assertThat(archive.readAllBytes()).isEqualTo(script);
            assertThat(archive.getNextEntry()).isNull();
        }
    }
}
