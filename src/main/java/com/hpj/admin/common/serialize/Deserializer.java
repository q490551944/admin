package com.hpj.admin.common.serialize;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 实体反序列化
 * @author huangpeijun
 * @date 2020/3/11
 */
public class Deserializer {

    /**
     * 加密反序列化
     */
    public static class DESedeDeserializer extends JsonDeserializer<String> {

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException, JsonProcessingException {
            if (parser != null && !StringUtils.isEmpty(parser.getText())) {
                DesPasswordEncoder encoder = new DesPasswordEncoder();
                return encoder.encode(parser.getText());
            }
            return null;
        }
    }
}
