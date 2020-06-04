package com.hpj.admin.common.config;

import lombok.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @author huangpeijun
 * @date 2020/4/19
 */
public class StringToEnumConverterFactory implements ConverterFactory<String, Enum<?>> {

    private static final Map<Class<?>, Converter<String, ?>> converterMap =  new HashMap<>();

    @Override
    public <T extends Enum<?>> Converter<String, T> getConverter(@NonNull Class<T> aClass) {
        Converter<String, ?> stringConverter = converterMap.get(aClass);
        if (stringConverter == null) {
        }
        return null;
    }
}
