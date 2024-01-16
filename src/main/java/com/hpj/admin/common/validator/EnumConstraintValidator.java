package com.hpj.admin.common.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * @author huangpeijun
 * @date 2020/4/18
 */
public class EnumConstraintValidator implements ConstraintValidator<EnumValue, Object> {
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        return false;
    }
}
