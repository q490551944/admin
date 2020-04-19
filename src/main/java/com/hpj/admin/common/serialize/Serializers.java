package com.hpj.admin.common.serialize;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 实体属性序列化
 *
 * @author huangpeijun
 * @date 2020/3/11
 */
public class Serializers {

    /**
     * 时间序列化为时间戳
     */
    public static class DateToLongSerializer extends JsonSerializer<LocalDateTime> {

        @Override
        public void serialize(LocalDateTime date, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (date != null) {
                generator.writeNumber(Timestamp.valueOf(date).getTime());
            } else {
                generator.writeNumber(" ");
            }
        }
    }

    /**
     * 解密序列化
     */
    public static class DesSerializer extends JsonSerializer<String> {

        @Override
        public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (!StringUtils.isEmpty(value)) {
                DesPasswordEncoder encoder = new DesPasswordEncoder();
                generator.writeString(encoder.decode(value));
            } else {
                generator.writeString(" ");
            }
        }
    }

    /**
     * 系统用户密码序列化
     */
    public final static class PasswordSerializer extends JsonSerializer<String> {

        @Override
        public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(Constants.ECHO_PASSWORD);
        }
    }
}
