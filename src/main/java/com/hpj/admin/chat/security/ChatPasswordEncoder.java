package com.hpj.admin.chat.security;

import com.hpj.admin.common.serialize.DesPasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ChatPasswordEncoder implements PasswordEncoder {
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final boolean allowLegacyDes;

    public ChatPasswordEncoder(boolean allowLegacyDes) { this.allowLegacyDes = allowLegacyDes; }

    @Override
    public String encode(CharSequence rawPassword) { return bcrypt.encode(rawPassword); }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return false;
        if (encodedPassword.startsWith("$2")) return bcrypt.matches(rawPassword, encodedPassword);
        if (!allowLegacyDes) return false;
        return MessageDigest.isEqual(new DesPasswordEncoder().encode(rawPassword).getBytes(StandardCharsets.UTF_8),
                encodedPassword.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return !encodedPassword.startsWith("$2") || bcrypt.upgradeEncoding(encodedPassword);
    }
}
