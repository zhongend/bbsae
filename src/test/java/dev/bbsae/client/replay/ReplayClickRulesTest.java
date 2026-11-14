package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BBS' replay-viewport click semantics: Alt selects actors, a plain click selects body parts. */
public class ReplayClickRulesTest
{
    @Test
    public void altOnAnotherActorSelectsThatReplay()
    {
        assertEquals(ReplayClickRules.Action.SELECT_REPLAY,
            ReplayClickRules.action(true, 3, false));
    }

    @Test
    public void plainClickOnAnotherActorDoesNotStealTheSelection()
    {
        assertEquals(ReplayClickRules.Action.NONE,
            ReplayClickRules.action(false, 3, false),
            "BBS only changes the actor with Alt held");
        assertEquals(ReplayClickRules.Action.NONE,
            ReplayClickRules.action(false, 3, true),
            "a whole-actor id from another replay must not be treated as a body part");
    }

    @Test
    public void aBodyPartIsSelectedWithOrWithoutAlt()
    {
        assertEquals(ReplayClickRules.Action.SELECT_PART,
            ReplayClickRules.action(false, -1, true));
        assertEquals(ReplayClickRules.Action.SELECT_PART,
            ReplayClickRules.action(true, -1, true),
            "Alt over the current actor still selects the part under the cursor");
    }

    @Test
    public void emptySpaceDoesNothing()
    {
        assertEquals(ReplayClickRules.Action.NONE, ReplayClickRules.action(false, -1, false));
        assertEquals(ReplayClickRules.Action.NONE, ReplayClickRules.action(true, -1, false));
    }
}
