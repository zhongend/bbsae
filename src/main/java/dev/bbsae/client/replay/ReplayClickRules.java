package dev.bbsae.client.replay;

/**
 * What a left click in the replay viewport means.
 *
 * <p>Pure on purpose: this is the branch the "Alt click does nothing / plain click steals the
 * actor" report was about. BBS' original editor only changes the selected actor with Alt held; a
 * plain click must go to the body part under the cursor. Keeping the decision out of the GL/UI code
 * makes that rule testable without a client.</p>
 */
public final class ReplayClickRules
{
    public enum Action
    {
        NONE,
        SELECT_REPLAY,
        SELECT_PART
    }

    private ReplayClickRules()
    {}

    /**
     * @param alt           whether Alt was held
     * @param pickedReplay  the other replay under the cursor, or -1
     * @param hasPart       whether the stencil carries the selected actor's form part
     */
    public static Action action(boolean alt, int pickedReplay, boolean hasPart)
    {
        if (pickedReplay >= 0)
        {
            /* Other actors exist in the pick buffer only so Alt can select them. Without Alt the
             * original editor's stencil contains just the current actor, so a plain click on
             * another actor does nothing instead of handing its whole form to the part picker. */
            return alt ? Action.SELECT_REPLAY : Action.NONE;
        }

        if (hasPart)
        {
            return Action.SELECT_PART;
        }

        return Action.NONE;
    }
}
