SET FOREIGN_KEY_CHECKS = 0;

INSERT INTO payment_order (
    id, payment_no, order_id, user_id, method, amount, paid_amount, refunded_amount,
    status, idempotency_key, provider_trade_no, create_time, update_time, version, tenant_id
) VALUES
    (
        905101, 'TASK4-DUPLICATE-PAYMENT-1', 905100, 905101, 'WECHAT', 100.00, 0.00, 0.00,
        'PENDING', 'task4-duplicate-key-1', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0, 1
    ),
    (
        905102, 'TASK4-DUPLICATE-PAYMENT-2', 905100, 905102, 'WECHAT', 100.00, 0.00, 0.00,
        'PENDING', 'task4-duplicate-key-2', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0, 1
    ),
    (
        905103, 'TASK4-LEGACY-PAYMENT', 905103, 905103, 'WECHAT', 100.00, 100.00, 30.00,
        'PARTIALLY_REFUNDED', 'task4-legacy-payment-key', 'TASK4-LEGACY-TRADE',
        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0, 1
    );

INSERT INTO payment_ledger (
    id, payment_id, order_id, user_id, ledger_type, amount, status, request_key,
    provider_trade_no, create_time, tenant_id
) VALUES (
    905104, 905103, 905103, 905103, 'REFUND', 30.00, 'SUCCESS', 'TASK4-LEGACY-REFUND-KEY',
    'TASK4-LEGACY-REFUND-TRADE', CURRENT_TIMESTAMP(6), 1
);

SET FOREIGN_KEY_CHECKS = 1;

-- Both duplicate payment rows deliberately share this logical order marker.
SELECT 'TASK4-DUPLICATE-ORDER';
