package shinhan.fibri.ieum.common.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = Utf8ByteSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8ByteSize {

	String message() default "must be {max} bytes or fewer";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	int max();
}
