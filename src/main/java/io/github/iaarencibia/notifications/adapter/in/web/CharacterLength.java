package io.github.iaarencibia.notifications.adapter.in.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE_USE;

/**
 * A ceiling counted in characters, the way the database counts them.
 *
 * <p>{@code @Size} exists and would be shorter, and it is the wrong tool here: it measures
 * {@code CharSequence.length()}, which is UTF-16 code units. An emoji is one character for a
 * {@code VARCHAR} column and two for {@code length()}, so a subject of 512 emoji reads as 1024
 * and is turned away -- with an error naming a limit the caller has not exceeded, over a value
 * both the column and the domain accept.
 *
 * <p>The domain already counts this way. Without this, the endpoint would be the one place in
 * the system that disagreed, and being the only way in, its answer would be the only one anybody
 * ever saw.
 */
@Documented
@Constraint(validatedBy = CharacterLength.Validator.class)
// TYPE_USE is what lets this reach the keys and the values inside a Map, which are type
// arguments rather than members and cannot be annotated any other way.
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, RECORD_COMPONENT, TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@interface CharacterLength {

    int max();

    String message() default "must not exceed {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Counts code points, so a character outside the basic plane counts once and not twice. */
    class Validator implements ConstraintValidator<CharacterLength, CharSequence> {

        private int max;

        @Override
        public void initialize(CharacterLength constraint) {
            this.max = constraint.max();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            // Absence is not this constraint's business; @NotBlank says whether it is allowed.
            if (value == null) {
                return true;
            }
            String text = value.toString();
            return text.codePointCount(0, text.length()) <= max;
        }
    }
}
