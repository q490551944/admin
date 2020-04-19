package com.hpj.admin.common.annotation;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.hpj.admin.common.serialize.Deserializer;
import com.hpj.admin.common.serialize.Serializers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author huangpeijun
 * @date 2020/3/15
 */
@Retention(value = RetentionPolicy.RUNTIME)
@JsonDeserialize(using = Deserializer.DESedeDeserializer.class)
@JsonSerialize(using = Serializers.DesSerializer.class)
@Target(value = ElementType.FIELD)
public @interface Encipher {
}
