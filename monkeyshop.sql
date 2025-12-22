/*
 Navicat Premium Dump SQL

 Source Server         : static
 Source Server Type    : MySQL
 Source Server Version : 80041 (8.0.41)
 Source Host           : localhost:3306
 Source Schema         : monkeyshop

 Target Server Type    : MySQL
 Target Server Version : 80041 (8.0.41)
 File Encoding         : 65001

 Date: 22/12/2025 22:00:08
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for address
-- ----------------------------
DROP TABLE IF EXISTS `address`;
CREATE TABLE `address`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '地址ID',
  `user_id` bigint NOT NULL COMMENT '关联的用户ID',
  `receiver_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人姓名',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收货人电话',
  `detail_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '详细地址',
  `is_default` int NULL DEFAULT NULL,
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '收货地址表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of address
-- ----------------------------
INSERT INTO `address` VALUES (1, 1, '测试用户', '13800138000', '花果山水帘洞5号', 1, '2025-12-22 08:50:59');
INSERT INTO `address` VALUES (2, 2, '熊大', '15603838733', '狗熊岭', 0, '2025-12-22 09:18:00');
INSERT INTO `address` VALUES (3, 2, '熊二', '13838388888', '团结屯', 1, '2025-12-22 09:18:23');
INSERT INTO `address` VALUES (4, 3, '曹岩磊', '18888888888', '月球阿波罗洼', 1, '2025-12-22 12:20:44');

-- ----------------------------
-- Table structure for admin
-- ----------------------------
DROP TABLE IF EXISTS `admin`;
CREATE TABLE `admin`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '管理员',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of admin
-- ----------------------------
INSERT INTO `admin` VALUES (1, 'admin', '123456', '超级管理员', '2025-12-22 00:31:05');

-- ----------------------------
-- Table structure for monkey
-- ----------------------------
DROP TABLE IF EXISTS `monkey`;
CREATE TABLE `monkey`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商品名称',
  `breed` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '品种',
  `price` double NULL DEFAULT NULL COMMENT '价格',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '图片地址',
  `stock` int NULL DEFAULT 10 COMMENT '库存',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '猴子商品表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of monkey
-- ----------------------------
INSERT INTO `monkey` VALUES (1, '悟空', '金丝猴', 9998, '性格活泼，会耍金箍棒，西游记联名款', '/images/product/c9f11d47-adaa-4774-a05a-f2ef6e12f453.jpg', 9);
INSERT INTO `monkey` VALUES (2, '杰克', '卷尾猴', 5000, '加勒比海盗同款，非常聪明，适合看家', '/images/product/33ba344f-c179-4d9e-b4e3-f94811f374d6.jpg', 10);
INSERT INTO `monkey` VALUES (3, '金刚', '大猩猩', 12000, '体型巨大，虽然不是猴子但很强壮，安全感爆棚', '/images/product/fb59ce47-9806-442c-861e-8d63a46615cd.jpg', 10);
INSERT INTO `monkey` VALUES (4, '莫莫', '狐猴', 3000, '马达加斯加特产，喜欢跳舞，眼神清澈愚蠢', '/images/product/5de3868f-4de4-452b-8bbc-bc9a0eb85f94.jpg', 10);
INSERT INTO `monkey` VALUES (5, '大草猪', '肥猪', 1, '这是一头大笨猪', '/images/product/57736ad2-eb28-4924-88b5-4e496e6bd68e.jpg', 10);

-- ----------------------------
-- Table structure for orders
-- ----------------------------
DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '订单编号(对外展示)',
  `user_id` bigint NOT NULL COMMENT '买家ID',
  `product_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `product_image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `price` double NOT NULL,
  `receiver_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `address_snapshot` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '完整的收货地址',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '已支付' COMMENT '状态: 待支付/已支付/已发货',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '商品描述快照',
  `buyer_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '买家昵称快照',
  `buyer_avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '买家头像快照',
  `shipping_time` datetime NULL DEFAULT NULL COMMENT '发货时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `order_no`(`order_no` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of orders
-- ----------------------------
INSERT INTO `orders` VALUES (7, '2025122220410359', 3, '悟空', '/images/product/c9f11d47-adaa-4774-a05a-f2ef6e12f453.jpg', 9998, '曹岩磊', '18888888888', '月球阿波罗洼', '已支付', '2025-12-22 20:41:03', '性格活泼，会耍金箍棒，西游记联名款', '好孩子', '/images/avatar/e8f2c468-d9b2-4e85-ac7e-99018e5e0034.png', NULL);

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of user
-- ----------------------------
INSERT INTO `user` VALUES (1, 'test', '123456', '13800138000', NULL, '2025-12-22 00:31:05');
INSERT INTO `user` VALUES (2, '111', '123456', '15603838733', NULL, '2025-12-22 00:36:38');
INSERT INTO `user` VALUES (3, '好孩子', '123456', '16650668913', '/images/avatar/e8f2c468-d9b2-4e85-ac7e-99018e5e0034.png', '2025-12-22 12:14:37');

-- ----------------------------
-- Table structure for visit_log
-- ----------------------------
DROP TABLE IF EXISTS `visit_log`;
CREATE TABLE `visit_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `visit_time` datetime NOT NULL COMMENT '访问时间',
  `ip_address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '访客IP (可选)',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_visit_time`(`visit_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of visit_log
-- ----------------------------
INSERT INTO `visit_log` VALUES (1, '2025-12-22 19:22:07', '127.0.0.1');
INSERT INTO `visit_log` VALUES (2, '2025-12-22 19:22:08', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (3, '2025-12-22 19:22:08', '127.0.0.1');
INSERT INTO `visit_log` VALUES (4, '2025-12-22 19:22:09', '127.0.0.1');
INSERT INTO `visit_log` VALUES (5, '2025-12-22 19:22:09', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (6, '2025-12-22 19:22:10', '127.0.0.1');
INSERT INTO `visit_log` VALUES (7, '2025-12-22 19:22:10', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (8, '2025-12-22 19:22:11', '127.0.0.1');
INSERT INTO `visit_log` VALUES (9, '2025-12-22 19:22:12', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (10, '2025-12-22 19:22:13', '127.0.0.1');
INSERT INTO `visit_log` VALUES (11, '2025-12-22 19:22:14', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (12, '2025-12-22 19:22:14', '127.0.0.1');
INSERT INTO `visit_log` VALUES (13, '2025-12-22 19:22:15', '127.0.0.1');
INSERT INTO `visit_log` VALUES (14, '2025-12-22 19:22:16', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (15, '2025-12-22 19:58:40', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (16, '2025-12-22 19:58:41', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (17, '2025-12-22 20:23:30', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (18, '2025-12-22 20:53:04', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (19, '2025-12-22 21:16:45', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (20, '2025-12-22 21:16:46', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (21, '2025-12-22 21:31:45', '0:0:0:0:0:0:0:1');
INSERT INTO `visit_log` VALUES (22, '2025-12-22 21:33:12', '0:0:0:0:0:0:0:1');

SET FOREIGN_KEY_CHECKS = 1;
