package com.hpj.admin.common.validator;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

/**
 * @author huangpeijun
 * @date 2020/4/18
 */
public class EnumConstraintValidator implements ConstraintValidator<EnumValue, > {
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        return false;
    }
}
