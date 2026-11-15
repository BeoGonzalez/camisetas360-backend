package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.AddCartItemRequest;
import com.camisetas360.carrito.dtos.ShippingRequest;
import com.camisetas360.carrito.messaging.InteraccionEventPublisher;
import com.camisetas360.carrito.messaging.KafkaTopics;
import com.camisetas360.carrito.messaging.event.InteraccionEvento;
import com.camisetas360.carrito.model.CartItem;
import com.camisetas360.carrito.model.CartItemKey;
import com.camisetas360.carrito.model.CartOwner;
import com.camisetas360.carrito.model.CartShipping;
import com.camisetas360.carrito.model.ShippingMethod;
import com.camisetas360.carrito.repository.CartItemRepository;
import com.camisetas360.carrito.repository.CartShippingRepository;
import com.camisetas360.carrito.security.EntraIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OBJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final EntraIdentity IDENTITY = new EntraIdentity(TENANT, OBJECT);
    private static final String USUARIO = TENANT + ":" + OBJECT;

    @Mock CartItemRepository items;
    @Mock CartShippingRepository shipping;
    @Mock InteraccionEventPublisher publisher;
    @Mock CatalogoProductos catalogo;

    private CartManagementService service;

    @BeforeEach
    void setUp() {
        service = new CartManagementService(items, shipping, publisher, catalogo,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void enCatalogo(String sku, String nombre, String categoria, double precio) {
        lenient().when(catalogo.buscar(List.of(sku)))
                .thenReturn(Map.of(sku, new ProductoCatalogo(sku, nombre, categoria, precio)));
    }

    @Test
    void agregarItem_shouldUseCatalogDataIgnoringClientPriceAndName() {
        var key = new CartItemKey(TENANT, OBJECT, "CAM-003");
        enCatalogo("CAM-003", "Camiseta Retro Edición Especial", "Colección", 49.99);
        when(items.findById(key)).thenReturn(Optional.empty());
        when(shipping.findById(any())).thenReturn(Optional.empty());

        service.agregarItem(IDENTITY, new AddCartItemRequest("CAM-003", "Nombre falso", "x", 1.0, 1));

        var saved = ArgumentCaptor.forClass(CartItem.class);
        verify(items).save(saved.capture());
        assertThat(saved.getValue().getUnitPrice()).isEqualTo(49.99);
        assertThat(saved.getValue().getProductName()).isEqualTo("Camiseta Retro Edición Especial");
        assertThat(saved.getValue().getCategory()).isEqualTo("Colección");
        assertThat(capturar(KafkaTopics.ITEM_AGREGADO).producto())
                .isEqualTo(new InteraccionEvento.ProductoSnapshot("CAM-003", "Camiseta Retro Edición Especial", "Colección", 49.99));
    }

    @Test
    void agregarItem_shouldRejectUnknownSkuWithoutSavingOrPublishing() {
        when(catalogo.buscar(List.of("NO-EXISTE"))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.agregarItem(IDENTITY, new AddCartItemRequest("NO-EXISTE", "x", null, 1.0, 1)))
                .isInstanceOf(ProductoNoDisponibleException.class).hasMessageContaining("NO-EXISTE");

        verify(items, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void agregarItem_shouldFailWithoutSaving_whenCatalogIsUnavailable() {
        when(catalogo.buscar(any())).thenThrow(new CatalogoNoDisponibleException(new RuntimeException("timeout")));

        assertThatThrownBy(() -> service.agregarItem(IDENTITY, new AddCartItemRequest("SKU-A", "x", null, 1.0, 1)))
                .isInstanceOf(CatalogoNoDisponibleException.class);

        verifyNoInteractions(items, publisher);
    }

    @Test
    void agregarItem_shouldCreateLineAndPublishItemAdded() {
        var key = new CartItemKey(TENANT, OBJECT, "SKU-A");
        enCatalogo("SKU-A", "Camiseta", "Liga A", 20.0);
        when(items.findById(key)).thenReturn(Optional.empty());
        when(items.findByKeyTenantIdAndKeyObjectIdOrderByKeySkuAsc(TENANT, OBJECT))
                .thenReturn(List.of(new CartItem(key, "Camiseta", "Liga A", 20.0, 2, NOW)));
        when(shipping.findById(any())).thenReturn(Optional.empty());

        var cart = service.agregarItem(IDENTITY, new AddCartItemRequest("SKU-A", "Camiseta", "Liga A", 20.0, 2));

        assertThat(cart.subtotal()).isEqualTo(40.0);
        assertThat(cart.costoEnvio()).isZero();
        assertThat(cart.total()).isEqualTo(40.0);
        var saved = ArgumentCaptor.forClass(CartItem.class);
        verify(items).save(saved.capture());
        assertThat(saved.getValue().getQuantity()).isEqualTo(2);

        var evento = capturar(KafkaTopics.ITEM_AGREGADO);
        assertThat(evento.eventType()).isEqualTo("ITEM_AGREGADO");
        assertThat(evento.source()).isEqualTo("carrito");
        assertThat(evento.usuarioId()).isEqualTo(USUARIO);
        assertThat(evento.occurredAt()).isEqualTo(NOW.toString());
        assertThat(evento.cantidad()).isEqualTo(2);
        assertThat(evento.producto()).isEqualTo(new InteraccionEvento.ProductoSnapshot("SKU-A", "Camiseta", "Liga A", 20.0));
        assertThat(evento.envio()).isNull();
    }

    @Test
    void agregarItem_shouldAccumulateUnitsOnExistingLine() {
        var key = new CartItemKey(TENANT, OBJECT, "SKU-A");
        var existing = new CartItem(key, "Camiseta", "Liga A", 20.0, 3, NOW.minusSeconds(60));
        enCatalogo("SKU-A", "Camiseta 2026", "Liga A", 25.0);
        when(items.findById(key)).thenReturn(Optional.of(existing));
        when(items.findByKeyTenantIdAndKeyObjectIdOrderByKeySkuAsc(TENANT, OBJECT)).thenReturn(List.of(existing));
        when(shipping.findById(any())).thenReturn(Optional.empty());

        service.agregarItem(IDENTITY, new AddCartItemRequest("SKU-A", "Camiseta 2026", "Liga A", 25.0, 2));

        assertThat(existing.getQuantity()).isEqualTo(5);
        assertThat(existing.getUnitPrice()).isEqualTo(25.0);
        assertThat(existing.getProductName()).isEqualTo("Camiseta 2026");
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void agregarItem_shouldRejectMoreThanMaxUnitsWithoutPublishing() {
        var key = new CartItemKey(TENANT, OBJECT, "SKU-A");
        enCatalogo("SKU-A", "Camiseta", null, 20.0);
        when(items.findById(key)).thenReturn(Optional.of(new CartItem(key, "Camiseta", null, 20.0, 98, NOW)));

        assertThatThrownBy(() -> service.agregarItem(IDENTITY, new AddCartItemRequest("SKU-A", "Camiseta", null, 20.0, 2)))
                .isInstanceOf(CartLimitExceededException.class);

        verify(items, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void eliminarItem_shouldDeleteLineAndPublishRemovedQuantity() {
        var key = new CartItemKey(TENANT, OBJECT, "SKU-A");
        var existing = new CartItem(key, "Camiseta", "Liga A", 20.0, 3, NOW);
        when(items.findById(key)).thenReturn(Optional.of(existing));
        when(items.findByKeyTenantIdAndKeyObjectIdOrderByKeySkuAsc(TENANT, OBJECT)).thenReturn(List.of());
        when(shipping.findById(any())).thenReturn(Optional.empty());

        var cart = service.eliminarItem(IDENTITY, "SKU-A");

        assertThat(cart.items()).isEmpty();
        verify(items).delete(existing);
        var evento = capturar(KafkaTopics.ITEM_ELIMINADO);
        assertThat(evento.eventType()).isEqualTo("ITEM_ELIMINADO");
        assertThat(evento.cantidad()).isEqualTo(3);
        assertThat(evento.producto().sku()).isEqualTo("SKU-A");
    }

    @Test
    void eliminarItem_shouldFailWithoutPublishing_whenSkuIsNotInCart() {
        when(items.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.eliminarItem(IDENTITY, "SKU-X")).isInstanceOf(CartItemNotFoundException.class);

        verifyNoInteractions(publisher);
    }

    @Test
    void actualizarEnvio_shouldStoreChoiceAddCostAndPublishWithoutCommune() {
        var owner = new CartOwner(TENANT, OBJECT);
        var stored = new CartShipping(owner);
        when(shipping.findById(owner)).thenReturn(Optional.empty()).thenReturn(Optional.of(stored));
        when(shipping.save(any())).thenAnswer(invocation -> {
            CartShipping value = invocation.getArgument(0);
            stored.update(value.getMethod(), value.getRegion(), value.getCommune(), value.getUpdatedAt());
            return value;
        });
        when(items.findByKeyTenantIdAndKeyObjectIdOrderByKeySkuAsc(TENANT, OBJECT))
                .thenReturn(List.of(new CartItem(new CartItemKey(TENANT, OBJECT, "SKU-A"), "Camiseta", null, 10000.0, 1, NOW)));

        var cart = service.actualizarEnvio(IDENTITY, new ShippingRequest(ShippingMethod.EXPRESS, " Metropolitana ", "Santiago"));

        assertThat(cart.envio().metodo()).isEqualTo("EXPRESS");
        assertThat(cart.envio().region()).isEqualTo("Metropolitana");
        assertThat(cart.costoEnvio()).isEqualTo(6990.0);
        assertThat(cart.total()).isEqualTo(16990.0);
        var evento = capturar(KafkaTopics.ENVIO_ACTUALIZADO);
        assertThat(evento.eventType()).isEqualTo("ENVIO_ACTUALIZADO");
        assertThat(evento.envio()).isEqualTo(new InteraccionEvento.EnvioSnapshot("EXPRESS", "Metropolitana"));
        assertThat(evento.producto()).isNull();
    }

    @Test
    void obtener_shouldNotChargeShipping_whenCartIsEmpty() {
        var owner = new CartOwner(TENANT, OBJECT);
        var stored = new CartShipping(owner);
        stored.update(ShippingMethod.STANDARD, "Valparaiso", "Vina del Mar", NOW);
        when(items.findByKeyTenantIdAndKeyObjectIdOrderByKeySkuAsc(TENANT, OBJECT)).thenReturn(List.of());
        when(shipping.findById(owner)).thenReturn(Optional.of(stored));

        var cart = service.obtener(IDENTITY);

        assertThat(cart.envio().costo()).isEqualTo(3990.0);
        assertThat(cart.costoEnvio()).isZero();
        assertThat(cart.total()).isZero();
        verifyNoInteractions(publisher);
    }

    private InteraccionEvento capturar(String topic) {
        var captor = ArgumentCaptor.forClass(InteraccionEvento.class);
        verify(publisher).publicar(eq(topic), captor.capture());
        return captor.getValue();
    }
}
