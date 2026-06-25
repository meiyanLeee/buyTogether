package com.hmdp.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoucherOrderPolicyTest {

    @Test
    void allowsOrderOnlyWhenUserHasNoExistingOrderAndStockWasDeducted() {
        assertTrue(VoucherOrderPolicy.canCreateOrder(0, true));
        assertFalse(VoucherOrderPolicy.canCreateOrder(1, true));
        assertFalse(VoucherOrderPolicy.canCreateOrder(0, false));
    }
}
