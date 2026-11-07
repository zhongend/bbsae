package dev.bbsae.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Keeps the requested sponsor credit and its gold treatment pinned to the first card. */
public class UISponsorBoardTest
{
    @Test
    public void firstSponsorUsesTheRequestedNameAndThankYou()
    {
        assertEquals("【星辰久梦君_Star Dm Sov】", UISponsorBoard.NAMES[0]);
        assertEquals("感谢您赞助和开发合作本插件", UISponsorBoard.thankYouFor(0));
    }

    @Test
    public void firstCardTextIsGoldWithoutRecoloringTheOtherCards()
    {
        assertEquals(0xFFFFD27A, UISponsorBoard.nameColorFor(0));
        assertEquals(0xFFFFD27A, UISponsorBoard.thankYouColorFor(0));
        assertNotEquals(UISponsorBoard.nameColorFor(0), UISponsorBoard.nameColorFor(1));
        assertNotEquals(UISponsorBoard.thankYouColorFor(0), UISponsorBoard.thankYouColorFor(1));
        assertEquals("感谢你赞助本插件的开发。", UISponsorBoard.thankYouFor(1));
    }
}
