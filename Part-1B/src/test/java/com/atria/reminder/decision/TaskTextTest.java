package com.atria.reminder.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskTextTest {

    @Test
    void leadInFillerIsStrippedButTheUsersWordsAreKept() {
        assertEquals("call the dentist", TaskText.clean("remind me to call the dentist"));
        assertEquals("call mom", TaskText.clean("Remind me: call mom"));
        assertEquals("pay rent", TaskText.clean("please remind me about pay rent"));
        assertEquals("take the bread out", TaskText.clean("set a reminder to take the bread out"));
    }

    @Test
    void fillerOnlyTextLeavesNothing() {
        assertNull(TaskText.clean("remind me"), "'remind me' is filler, not a task");
        assertNull(TaskText.clean("Remind me to"));
        assertNull(TaskText.clean("reminder"));
        assertNull(TaskText.clean("   "));
        assertNull(TaskText.clean(null));
    }

    @Test
    void ordinaryTaskTextIsLeftAlone() {
        assertEquals("call the dentist", TaskText.clean("call the dentist"));
        assertEquals("my dentist appointment", TaskText.clean("my dentist appointment"));
        assertEquals("the standup reminder", TaskText.clean("the standup reminder"));
        assertEquals("check flight 492013", TaskText.clean("check flight 492013"));
    }

    @Test
    void aTaskThatMerelyStartsWithRemindIsNotMangled() {
        // The verb matters only as lead-in; a genuine task keeps its wording.
        assertEquals("remind the team about payroll",
                TaskText.clean("remind the team about payroll"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"it", "this", "that", "the thing", "that stuff", "something", "the other one",
            "remind me", "the reminder", "ME"})
    void pronounsAndPlaceholdersIdentifyNothing(String text) {
        assertTrue(TaskText.isVague(TaskText.clean(text)), "should be vague: " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"call the dentist", "my 1:1 with Sam", "the standup reminder", "pay rent"})
    void realTasksAndTargetsAreNotVague(String text) {
        assertFalse(TaskText.isVague(TaskText.clean(text)), "should not be vague: " + text);
    }

    @Test
    void nullAndBlankAreVague() {
        assertTrue(TaskText.isVague(null));
        assertTrue(TaskText.isVague("  "));
    }
}
