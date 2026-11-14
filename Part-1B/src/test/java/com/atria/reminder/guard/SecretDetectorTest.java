package com.atria.reminder.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "remind me that my OTP is 492013",
            "remind me my one-time code is 882211",
            "save my password: hunter2 for later",
            "remind me the pin is 4921",
            "remind me my CVV is 123",
            "remind me that my card number is 4111111111111111",
            "remind me my account number is 00123456789 tomorrow"
    })
    void refusesCredentialsWithAValue(String input) {
        assertTrue(SecretDetector.isSecret(input), "should be detected as a credential: " + input);
    }

    @Test
    void refusesAValueSpelledOutInWords() {
        assertTrue(SecretDetector.isSecret("remind me my pin is one two three four"));
    }

    @Test
    void refusesABareCardShapedDigitRunWithNoCredentialWord() {
        assertTrue(SecretDetector.isSecret("remind me about 4111111111111111 tomorrow"));
        assertTrue(SecretDetector.isSecret("note 1234567890123 please"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "remind me to check flight 492013 tomorrow",
            "remind me to pick up the parcel at room 214 tomorrow at 6pm",
            "remind me to call 555 1234 about the invoice",
            "remind me to change my password tomorrow at 9",
            "remind me to pin the schedule to the noticeboard",
            "remind me about order 12345678901 tomorrow"
    })
    void doesNotRefuseOrdinaryRemindersThatHappenToContainDigits(String input) {
        assertFalse(SecretDetector.isSecret(input), "should NOT be treated as a credential: " + input);
    }

    @Test
    void digitRunBoundaries() {
        assertFalse(SecretDetector.isSecret("reference " + "1".repeat(12) + " tomorrow"), "12 digits is not card-shaped");
        assertTrue(SecretDetector.isSecret("reference " + "1".repeat(13) + " tomorrow"), "13 digits is card-shaped");
        assertTrue(SecretDetector.isSecret("reference " + "1".repeat(19) + " tomorrow"), "19 digits is card-shaped");
        assertFalse(SecretDetector.isSecret("reference " + "1".repeat(20) + " tomorrow"), "20 digits is not card-shaped");
    }

    @Test
    void blankInputHoldsNoCredential() {
        assertFalse(SecretDetector.isSecret(null));
        assertFalse(SecretDetector.isSecret("   "));
    }

    @Test
    void theDetectorNeverReportsTheMatchedValue() {
        String detail = SecretDetector.detect("remind me that my OTP is 492013");
        assertTrue(detail != null && !detail.contains("492013"), "detail must not quote the secret: " + detail);
    }
}
