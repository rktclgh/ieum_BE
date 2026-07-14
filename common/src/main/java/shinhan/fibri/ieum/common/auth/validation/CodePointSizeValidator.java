package shinhan.fibri.ieum.common.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CodePointSizeValidator implements ConstraintValidator<CodePointSize, String> {

	private int max;

	@Override
	public void initialize(CodePointSize constraintAnnotation) {
		this.max = constraintAnnotation.max();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.codePointCount(0, value.length()) <= max;
	}
}
