package com.hpj.admin.chat;

import com.hpj.admin.chat.security.ChatPasswordEncoder;
import com.hpj.admin.common.serialize.DesPasswordEncoder;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatPasswordTest {
    @Test
    void bcryptIsTheDefaultAndPlaintextIsNeverAccepted() {
        var encoder = new ChatPasswordEncoder(false);
        String hash = encoder.encode("password");
        assertThat(encoder.matches("password", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
        assertThat(encoder.matches("password", "password")).isFalse();
        assertThat(encoder.matches("password", new DesPasswordEncoder().encode("password"))).isFalse();
    }

    @Test
    void legacyDesIsOptInAndAlwaysRequiresUpgrade() {
        var encoder = new ChatPasswordEncoder(true);
        String legacy = new DesPasswordEncoder().encode("password");
        assertThat(encoder.matches("password", legacy)).isTrue();
        assertThat(encoder.matches("wrong", legacy)).isFalse();
        assertThat(encoder.upgradeEncoding(legacy)).isTrue();
        assertThat(encoder.matches("password", "password")).isFalse();
    }
}
