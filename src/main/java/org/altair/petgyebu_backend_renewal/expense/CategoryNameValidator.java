package org.altair.petgyebu_backend_renewal.expense;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class CategoryNameValidator implements ConstraintValidator<CategoryName, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || Category.isValidName(value);
    }
}
