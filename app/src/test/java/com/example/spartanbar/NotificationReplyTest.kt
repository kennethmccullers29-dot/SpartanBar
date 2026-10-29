package com.example.spartanbar

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationReplyTest {
    @Test fun semanticReplyWinsOverOtherRemoteInputs() {
        assertEquals(1, selectReplyAction(
            booleanArrayOf(true, true),
            booleanArrayOf(false, true),
            arrayOf("Mark read", "Reply")
        ))
    }

    @Test fun replyTitleBeatsAnUnlabeledRemoteInput() {
        assertEquals(1, selectReplyAction(
            booleanArrayOf(true, true),
            booleanArrayOf(false, false),
            arrayOf("Archive", "Reply")
        ))
    }

    @Test fun anyRemoteInputIsUsedWhenNoneIsLabeledReply() {
        assertEquals(0, selectReplyAction(
            booleanArrayOf(true, false),
            booleanArrayOf(false, false),
            arrayOf("Respond", null)
        ))
    }

    @Test fun actionsWithoutRemoteInputCannotBeRepliedTo() {
        assertEquals(-1, selectReplyAction(
            booleanArrayOf(false),
            booleanArrayOf(true),
            arrayOf("Reply")
        ))
    }
}
