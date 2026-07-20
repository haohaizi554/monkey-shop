UPDATE `orders` AS `o`
JOIN `payment_order` AS `p` ON `p`.`order_id` = `o`.`id`
SET `o`.`status` = '已支付'
WHERE `o`.`status` IN ('待支付', 'PENDING_PAYMENT')
  AND `p`.`status` = 'PAID';

UPDATE `user`
SET `password_change_required` = 0
WHERE `role` = 'ADMIN'
  AND `password_change_required` = 1
  AND `phone` IS NULL;
