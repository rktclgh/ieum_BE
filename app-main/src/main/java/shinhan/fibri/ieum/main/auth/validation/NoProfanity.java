package shinhan.fibri.ieum.main.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = ProfanityNicknameValidator.class)
@Target({FIELD})
@Retention(RUNTIME)
public @interface NoProfanity {

	String message() default "Nickname contains prohibited language";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
