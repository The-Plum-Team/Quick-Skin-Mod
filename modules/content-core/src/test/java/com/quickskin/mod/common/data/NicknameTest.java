package com.quickskin.mod.common.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A nickname is a readable name in any script, never a way to fake or hide another one. */
class NicknameTest {
    @Test
    void aBlankNicknameMeansNone() {
        assertEquals("", Nickname.normalize(null));
        assertEquals("", Nickname.normalize(""));
        assertEquals("", Nickname.normalize("  \t "));
        assertTrue(Nickname.isCanonical(""));
    }

    @Test
    void roleplayNamesInAnyScriptAreKept() {
        assertEquals("Sir Lancelot", Nickname.normalize("Sir Lancelot"));
        assertEquals("Señora Pérez", Nickname.normalize("Señora Pérez"));
        assertEquals("D'Artagnan", Nickname.normalize("D'Artagnan"));
        assertEquals("Jean-Luc", Nickname.normalize("Jean-Luc"));
        assertEquals("Mr. Smith, Jr", Nickname.normalize("Mr. Smith, Jr"));
        assertEquals("O’Neil_2", Nickname.normalize("O’Neil_2"));
        assertEquals("Ворон", Nickname.normalize("Ворон"));
        assertEquals("花子", Nickname.normalize("花子"));
        assertEquals("محمد", Nickname.normalize("محمد"));
    }

    @Test
    void spacesAreTrimmedAndCollapsed() {
        assertEquals("Sir Lancelot", Nickname.normalize("  Sir \t  Lancelot \n"));
        assertEquals("Sir Lancelot", Nickname.normalize("Sir Lancelot"));
        assertFalse(Nickname.isCanonical(" Sir Lancelot"));
        assertTrue(Nickname.isCanonical("Sir Lancelot"));
    }

    @Test
    void accentsAreComposed() {
        // "é" typed as e plus a combining acute accent is the same nickname as "é"
        assertEquals("José", Nickname.normalize("José"));
        assertTrue(Nickname.isCanonical("José"));
        assertFalse(Nickname.isCanonical("José"));
    }

    @Test
    void formattingControlAndInvisibleCharactersAreRefused() {
        assertNull(Nickname.normalize("§cRed"));
        assertNull(Nickname.normalize("Bad\u0000Name"));
        assertNull(Nickname.normalize("Zero​Width"));
        assertNull(Nickname.normalize("Right‮ToLeft"));
        assertNull(Nickname.normalize("Joiner‍Name"));
        assertNull(Nickname.normalize("PrivateGlyph"));
        assertNull(Nickname.normalize("Broken�Text"));
        assertNull(Nickname.normalize("Lone\uD800Surrogate"));
    }

    @Test
    void symbolsThatCouldFakeAnotherNameAreRefused() {
        assertNull(Nickname.normalize("Nick (Steve)"));
        assertNull(Nickname.normalize("[Admin] Nick"));
        assertNull(Nickname.normalize("<Steve>"));
        assertNull(Nickname.normalize("Nick: hi"));
        assertNull(Nickname.normalize("😀"));
        assertNull(Nickname.normalize("★Star★"));
    }

    @Test
    void aNicknameNeedsALetterOrADigit() {
        assertNull(Nickname.normalize("..."));
        assertNull(Nickname.normalize("- _ -"));
        assertEquals("7", Nickname.normalize("7"));
    }

    @Test
    void combiningMarksStayOnTheirLetterAndAreBounded() {
        // No precomposed "q" with these accents exists, so both marks stay
        assertEquals("q́̂", Nickname.normalize("q́̂"));
        assertNull(Nickname.normalize("à́̂̃"));
        assertNull(Nickname.normalize("́Mark"));
        assertNull(Nickname.normalize("Mark ́"));
        assertNull(Nickname.normalize("Mark-́"));
    }

    @Test
    void aNicknameHoldsAtMostThirtyTwoUnits() {
        String longest = "A".repeat(Nickname.MAX_LENGTH);
        assertEquals(longest, Nickname.normalize(longest));
        assertNull(Nickname.normalize(longest + "B"));
        // Surrounding spaces do not count
        assertEquals(longest, Nickname.normalize("  " + longest + "  "));
    }

    @Test
    void namesReadTheSameRegardlessOfCase() {
        assertTrue(Nickname.readsAs("Steve", "steve"));
        assertFalse(Nickname.readsAs("Steve", "Steven"));
        assertFalse(Nickname.readsAs("", ""));
        assertFalse(Nickname.readsAs(null, "Steve"));
    }

    @Test
    void lettersDrawnLikeLatinOnesReadAsTheLatinName() {
        // Cyrillic о and Greek Ν are drawn exactly like Latin o and N
        assertTrue(Nickname.readsAs("N\u043Etch", "Notch"));
        assertTrue(Nickname.readsAs("\u039Dotch", "notch"));
        assertTrue(Nickname.readsAs("\u0405teve", "Steve"));
        // Accents and fullwidth forms do not make another name either
        assertTrue(Nickname.readsAs("Notc\u1E25", "Notch"));
        assertTrue(Nickname.readsAs("\uFF2E\uFF4F\uFF54\uFF43\uFF48", "Notch"));
        // Names that only share some letters stay apart
        assertFalse(Nickname.readsAs("\u0412\u043E\u0440\u043E\u043D", "Notch"));
        assertFalse(Nickname.readsAs("Nacht", "Notch"));
    }

    @Test
    void charactersThatDrawNothingAreRefused() {
        assertNull(Nickname.normalize("\uFFA0"));
        assertNull(Nickname.normalize("Notch\uFFA0"));
        assertNull(Nickname.normalize("\u3164"));
        assertNull(Nickname.normalize("a\uFE0F"));
        assertNull(Nickname.normalize("a\u034F"));
        assertFalse(Nickname.isAllowedCharacter(0xFFA0));
        assertFalse(Nickname.isAllowedCharacter(0xFE0F));
        assertTrue(Nickname.isAllowedCharacter('a'));
        assertFalse(Nickname.isAllowedCharacter('('));
    }

    @Test
    void aRefusedNicknameSaysWhy() {
        assertEquals(Nickname.Problem.NONE, Nickname.check("Sir Lancelot"));
        assertEquals(Nickname.Problem.NONE, Nickname.check("   "));
        assertEquals(Nickname.Problem.NO_LETTER_OR_DIGIT, Nickname.check("..."));
        assertEquals(Nickname.Problem.MISPLACED_MARK, Nickname.check("Mark \u0301"));
        assertEquals(Nickname.Problem.TOO_LONG, Nickname.check("A".repeat(Nickname.MAX_LENGTH + 1)));
        assertEquals(Nickname.Problem.CHARACTER, Nickname.check("Nick (Steve)"));
    }

    @Test
    void anAnnouncedNicknameIsShownWhateverLettersThisJavaKnows() {
        assertTrue(Nickname.isSafeToShow("Sir Lancelot"));
        // A letter assigned in a later Unicode version is unknown to an older Java, never unsafe
        assertTrue(Nickname.isSafeToShow("Sir \uA7D0ancelot"));
        assertFalse(Nickname.isSafeToShow(""));
        assertFalse(Nickname.isSafeToShow(" Sir"));
        assertFalse(Nickname.isSafeToShow("Sir  Lancelot"));
        assertFalse(Nickname.isSafeToShow("\u00A7cRed"));
        assertFalse(Nickname.isSafeToShow("Nick (Steve)"));
        assertFalse(Nickname.isSafeToShow("Right\u202EToLeft"));
        assertFalse(Nickname.isSafeToShow("Blank\uFFA0"));
        assertFalse(Nickname.isSafeToShow("A".repeat(Nickname.MAX_LENGTH + 1)));
    }
}
