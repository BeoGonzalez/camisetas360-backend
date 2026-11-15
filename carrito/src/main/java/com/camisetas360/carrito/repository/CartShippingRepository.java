package com.camisetas360.carrito.repository;

import com.camisetas360.carrito.model.CartOwner;
import com.camisetas360.carrito.model.CartShipping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartShippingRepository extends JpaRepository<CartShipping, CartOwner> {
}
