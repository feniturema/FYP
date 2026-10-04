-- V1 baseline: exported from Hibernate DDL at 5f5fae4 (v0.4.2) on MySQL 8.0.46-0ubuntu0.24.04.4. DO NOT EDIT after merge.
CREATE TABLE `items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category` varchar(255) DEFAULT NULL,
  `item_condition` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(2000) DEFAULT NULL,
  `image_url` varchar(255) DEFAULT NULL,
  `price` decimal(10,2) NOT NULL,
  `seller_id` bigint NOT NULL,
  `status` enum('ACTIVE','REMOVED','SOLD') NOT NULL,
  `title` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `amount` decimal(10,2) NOT NULL,
  `buyer_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `payment_method` varchar(255) DEFAULT NULL,
  `ref_id` bigint NOT NULL,
  `source_type` enum('B2C_PRODUCT','C2C_ITEM','SECKILL') NOT NULL,
  `status` enum('CANCELLED','FAILED','PAID','PENDING') NOT NULL,
  `tracking_token` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK1tfq70x2qvba2uwt5qgphiy0e` (`tracking_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(2000) DEFAULT NULL,
  `image_url` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `total_stock` int NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `reviews` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `author_id` bigint NOT NULL,
  `comment` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `rating` int NOT NULL,
  `target_ref_id` bigint NOT NULL,
  `target_type` enum('ITEM','PRODUCT','SELLER') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `seckill_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `end_time` datetime(6) NOT NULL,
  `product_id` bigint NOT NULL,
  `seckill_price` decimal(10,2) NOT NULL,
  `seckill_stock` int NOT NULL,
  `start_time` datetime(6) NOT NULL,
  `status` enum('ACTIVE','ENDED','PENDING') NOT NULL,
  `stock_warmed` bit(1) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `email` varchar(255) NOT NULL,
  `email_verified` bit(1) NOT NULL,
  `name` varchar(255) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role` enum('ADMIN','STUDENT') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
