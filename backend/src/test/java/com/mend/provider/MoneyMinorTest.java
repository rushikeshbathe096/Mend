package com.mend.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoneyMinorTest {

    @Test
    void acceptsPositiveInrAmount() {
        MoneyMinor money = new MoneyMinor(100, "INR");
        assertEquals(100L, money.amountMinor());
        assertEquals("INR", money.currency());
    }

    @Test
    void rejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> new MoneyMinor(0, "INR"));
    }

    @Test
    void rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new MoneyMinor(-1, "INR"));
    }

    @Test
    void rejectsNonInrCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new MoneyMinor(100, "USD"));
        assertThrows(IllegalArgumentException.class, () -> new MoneyMinor(100, "inr"));
    }

    @Test
    void rejectsNullCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new MoneyMinor(100, null));
    }
}
