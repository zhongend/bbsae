package dev.bbsae.client.replay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * "Pick a model in the library" lands on the replay's form slot as a copy, through the same slot
 * the film undo handler watches. The generic helper keeps this test free of BBS' form hierarchy
 * (which needs the client registries) while pinning the two rules that matter: copy, then write.
 */
public class ReplayFormSwapTest
{
    private static final class Form
    {
        final String model;

        Form(String model)
        {
            this.model = model;
        }
    }

    @Test
    public void thePickedFormIsCopiedBeforeItIsStored()
    {
        Form picked = new Form("bbs:models/character");
        List<Form> copied = new ArrayList<>();
        Form[] stored = new Form[1];
        Function<Form, Form> copy = (form) ->
        {
            Form clone = new Form(form.model + "|copy");

            copied.add(clone);

            return clone;
        };

        ReplayFormSwap.apply(picked, copy, (form) -> stored[0] = form);

        assertEquals(1, copied.size());
        assertSame(copied.get(0), stored[0]);
        assertEquals("bbs:models/character|copy", stored[0].model);
    }

    @Test
    public void aNullPickDoesNotTouchTheSlot()
    {
        boolean[] called = {false};

        assertNull(ReplayFormSwap.apply(null, (form) -> form, (form) -> called[0] = true));
        assertEquals(false, called[0]);
    }

    @Test
    public void aFailingCopyDoesNotWriteAHalfForm()
    {
        boolean[] called = {false};

        assertNull(ReplayFormSwap.apply(new Form("x"), (form) -> null, (form) -> called[0] = true));
        assertEquals(false, called[0]);
    }
}
