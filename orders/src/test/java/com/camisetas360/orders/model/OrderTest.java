package com.camisetas360.orders.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    // UT-ORD-010
    @Test
    void addItem_shouldLinkItemsToOrder_whenItemsAreAdded() {
        var order = new Order();
        var first = new OrderItem();
        var second = new OrderItem();

        order.addItem(first);
        order.addItem(second);

        assertThat(order.getItems()).containsExactly(first, second);
        assertThat(first.getOrder()).isSameAs(order);
        assertThat(second.getOrder()).isSameAs(order);
    }

    // UT-ORD-010
    @Test
    void setItems_shouldReplaceAndLinkItems_whenGivenIndependentList() {
        var order = new Order();
        var oldItem = new OrderItem();
        order.addItem(oldItem);
        var first = new OrderItem();
        var second = new OrderItem();

        order.setItems(List.of(first, second));

        assertThat(order.getItems()).containsExactly(first, second).doesNotContain(oldItem);
        assertThat(first.getOrder()).isSameAs(order);
        assertThat(second.getOrder()).isSameAs(order);
    }

    // UT-ORD-011
    @ParameterizedTest
    @NullSource
    @EmptySource
    void setItems_shouldClearExistingItems_whenInputIsNullOrEmpty(List<OrderItem> replacement) {
        var order = new Order();
        order.addItem(new OrderItem());

        order.setItems(replacement);

        assertThat(order.getItems()).isEmpty();
    }
}
