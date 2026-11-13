package com.camisetas360.carrito.repository;

import com.camisetas360.carrito.model.CartItem;
import com.camisetas360.carrito.model.CartItemKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, CartItemKey> {

    List<CartItem> findByKeyTenantIdAndKeyObjectIdOrderByKeySkuAsc(UUID tenantId, UUID objectId);
}
