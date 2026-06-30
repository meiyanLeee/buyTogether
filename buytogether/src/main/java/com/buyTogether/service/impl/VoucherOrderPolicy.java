package com.buyTogether.service.impl;

final class VoucherOrderPolicy {
    private VoucherOrderPolicy() {
    }

    static boolean canCreateOrder(int existingOrderCount, boolean stockDeducted) {
        return existingOrderCount <= 0 && stockDeducted;
    }
}
