package com.camisetas360.catalog.dtos;

import com.camisetas360.catalog.models.KitType;

/** Datos editables de un producto (comunes a creación y edición). */
public interface ProductFields {
    String name();
    String team();
    KitType kitType();
    String category();
    String league();
    Double price();
    Integer stock();
    String description();
}
