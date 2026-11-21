package com.atria.reminder.guard;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FR-14 credential detection, decided in code before any model call (constitution IV).
 *
 * <p>Two independent rules, per the clarification session:
 * <ul>
 *   <li>a credential term appearing together with a value — digits or number words; or</li>
 *   <li>a bare run of 13 to 19 digits anywhere in the input.</li>
 * </ul>
 * A short digit run with no credential term nearby is ordinary text and must not match, so "remind me to check
 * flight 492013" and "parcel at room 214" are created normally.
 */
public final class SecretDetector {

    /** Terms that name a credential. Word-boundary matched, so "pinned" and "pincode note" do not trip "pin". */
    private static final Pattern CREDENTIAL_TERM = Pattern.compile(
            "\\b(otp|one[- ]time (?:code|password|pin)|password|passwd|passcode|pin|pin code|cvv|cvc"
                    + "|card number|account number|credit card|debit card|card details|security code)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A bare run of 13 to 19 digits is card-shaped on its own, whatever the wording around it. */
    private static final Pattern CARD_SHAPED = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");

    /** Three or more digits reads as a credential value next to a credential term. */
    private static final Pattern DIGIT_VALUE = Pattern.compile("(?<!\\d)\\d{3,}(?!\\d)");

    private static final List<String> NUMBER_WORDS = List.of(
            "zero", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
            "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "double", "triple");

    /** Three or more number words in a row spell out a credential: "my pin is one two three four". */
    private static final Pattern SPELLED_VALUE = Pattern.compile(
            "\\b(?:" + String.join("|", NUMBER_WORDS) + ")\\b(?:[ ,-]+\\b(?:" + String.join("|", NUMBER_WORDS)
                    + ")\\b){2,}", Pattern.CASE_INSENSITIVE);

    /** An explicit assignment after a credential term: "password is hunter2", "pin: 4921", "cvv = 123". */
    private static final Pattern ASSIGNED_VALUE = Pattern.compile(
            "\\b(?:otp|password|passwd|passcode|pin|cvv|cvc|security code|card number|account number)\\b"
                    + "\\s*(?:is|was|=|:|->)\\s*\\S+",
            Pattern.CASE_INSENSITIVE);

    private SecretDetector() {
    }

    /** @return the rule that fired, or null when the input holds no credential */
    public static String detect(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        if (CARD_SHAPED.matcher(input).find()) {
            return "a bare 13-19 digit run (card-shaped value)";
        }
        Matcher term = CREDENTIAL_TERM.matcher(input);
        if (term.find()) {
            if (ASSIGNED_VALUE.matcher(input).find()) {
                return "a credential term assigned a value";
            }
            if (DIGIT_VALUE.matcher(input).find()) {
                return "a credential term alongside a numeric value";
            }
            if (SPELLED_VALUE.matcher(input).find()) {
                return "a credential term alongside a spelled-out value";
            }
        }
        return null;
    }

    public static boolean isSecret(String input) {
        return detect(input) != null;
    }
}
