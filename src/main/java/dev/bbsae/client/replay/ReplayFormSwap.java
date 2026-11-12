package dev.bbsae.client.replay;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The "model picked in the library lands on the replay's form slot" step.
 *
 * <p>It exists as its own function because the two details that make it correct are easy to get
 * wrong: the library hands over the <b>live</b> form instance, so the replay must store a copy
 * (editing the model later in the form editor must not rewrite the replay's form behind its back —
 * this is what the original replay list does too), and the write has to go through the same form
 * slot the film undo handler watches.</p>
 *
 * <p>Generic over the form type so the copy/write rule can be unit-tested without loading BBS'
 * form hierarchy (which needs the client registries).</p>
 */
public final class ReplayFormSwap
{
    private ReplayFormSwap()
    {}

    public static <F> F apply(F picked, Function<F, F> copy, Consumer<F> slot)
    {
        if (picked == null || copy == null || slot == null)
        {
            return null;
        }

        F stored = copy.apply(picked);

        if (stored != null)
        {
            slot.accept(stored);
        }

        return stored;
    }
}
