package com.hpj.admin.common.validator;

import org.springframework.util.ObjectUtils;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author huangpeijun
 * @date 2020/4/18
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnumValue {

    String message() default "{com.hpj.admin.common.validator.Enum.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    Class<? extends Enum<?>> enumClass();

    String key() default "name";

    class Validator implements ConstraintValidator<EnumValue, Object> {

        private Class<? extends Enum<?>> enumClass;

        private String key;

        @Override
        public void initialize(EnumValue constraintAnnotation) {
            enumClass = constraintAnnotation.enumClass();
            key = constraintAnnotation.key();
        }

        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            if (ObjectUtils.isEmpty(value)) {
                return Boolean.TRUE;
            }
            if (ObjectUtils.isEmpty(enumClass) || ObjectUtils.isEmpty(key)) {
                return Boolean.TRUE;
            }

            return false;
        }
    }
}
