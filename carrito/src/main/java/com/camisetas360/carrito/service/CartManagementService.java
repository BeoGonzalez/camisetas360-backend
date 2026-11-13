package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.AddCartItemRequest;
import com.camisetas360.carrito.dtos.CartDTO;
import com.camisetas360.carrito.dtos.ShippingRequest;
import com.camisetas360.carrito.messaging.InteraccionEventPublisher;
import com.camisetas360.carrito.messaging.KafkaTopics;
import com.camisetas360.carrito.messaging.event.InteraccionEvento;
import com.camisetas360.carrito.model.CartItem;
import com.camisetas360.carrito.model.CartItemKey;
import com.camisetas360.carrito.model.CartOwner;
import com.camisetas360.carrito.model.CartShipping;
import com.camisetas360.carrito.repository.CartItemRepository;
import com.camisetas360.carrito.repository.CartShippingRepository;
import com.camisetas360.carrito.security.EntraIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gestion del carrito (items y envio) del usuario autenticado.
 * Cada cambio publica un evento de interaccion en Kafka para analitica.
 */
@Service
public class CartManagementService {

    private final CartItemRepository items;
    private final CartShippingRepository shipping;
    private final InteraccionEventPublisher publisher;
    private final CatalogoProductos catalogo;
    private final TransactionOperations transaction;
    private final Clock clock;

    @Autowired
    public CartManagementService(CartItemRepository items, CartShippingRepository shipping,
                                 InteraccionEventPublisher publisher, CatalogoProductos catalogo,
                                 TransactionOperations transaction) {
        this(items, shipping, publisher, catalogo, transaction, Clock.systemUTC());
    }

    CartManagementService(CartItemRepository items, CartShippingRepository shipping,
                          InteraccionEventPublisher publisher, CatalogoProductos catalogo,
                          TransactionOperations transaction, Clock clock) {
        this.items = items;
        this.shipping = shipping;
        this.publisher = publisher;
        this.catalogo = catalogo;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CartDTO obtener(EntraIdentity identity) {
        return toDto(identity);
    }

    /**
     * Nombre, categoria y precio se toman del catalogo (se ignoran los del cliente). La consulta
     * HTTP ocurre antes de abrir la transaccion para no retener una conexion de BD mientras tanto.
     */
    public CartDTO agregarItem(EntraIdentity identity, AddCartItemRequest request) {
        ProductoCatalogo producto = catalogo.buscar(List.of(request.sku())).get(request.sku());
        if (producto == null) {
            throw new ProductoNoDisponibleException(List.of(request.sku()));
        }
        return transaction.execute(status -> agregar(identity, producto, request.cantidad()));
    }

    private CartDTO agregar(EntraIdentity identity, ProductoCatalogo producto, int cantidad) {
        Instant now = Instant.now(clock);
        var key = new CartItemKey(identity.tenantId(), identity.objectId(), producto.sku());
        var item = items.findById(key).orElse(null);

        if (item == null) {
            item = new CartItem(key, producto.nombre(), producto.categoria(), producto.precio(), cantidad, now);
        } else {
            if (item.getQuantity() + cantidad > CartItem.MAX_QUANTITY) {
                throw new CartLimitExceededException(producto.sku(), CartItem.MAX_QUANTITY);
            }
            item.addUnits(producto.nombre(), producto.categoria(), producto.precio(), cantidad, now);
        }
        items.save(item);

        publisher.publicar(KafkaTopics.ITEM_AGREGADO, evento("ITEM_AGREGADO", identity, now,
                new InteraccionEvento.ProductoSnapshot(producto.sku(), producto.nombre(), producto.categoria(),
                        producto.precio()),
                cantidad, null));

        return toDto(identity);
    }

    @Transactional
    public CartDTO eliminarItem(EntraIdentity identity, String sku) {
        var key = new CartItemKey(identity.tenantId(), identity.objectId(), sku);
        CartItem item = items.findById(key).orElseThrow(() -> new CartItemNotFoundException(sku));
        items.delete(item);

        publisher.publicar(KafkaTopics.ITEM_ELIMINADO, evento("ITEM_ELIMINADO", identity, Instant.now(clock),
                new InteraccionEvento.ProductoSnapshot(item.getSku(), item.getProductName(), item.getCategory(),
                        item.getUnitPrice()),
                item.getQuantity(), null));

        return toDto(identity);
    }

    @Transactional
    public CartDTO actualizarEnvio(EntraIdentity identity, ShippingRequest request) {
        Instant now = Instant.now(clock);
        var owner = new CartOwner(identity.tenantId(), identity.objectId());
        CartShipping current = shipping.findById(owner).orElseGet(() -> new CartShipping(owner));
        current.update(request.metodo(), request.region().trim(), request.comuna().trim(), now);
        shipping.save(current);

        publisher.publicar(KafkaTopics.ENVIO_ACTUALIZADO, evento("ENVIO_ACTUALIZADO", identity, now, null, null,
                new InteraccionEvento.EnvioSnapshot(request.metodo().name(), request.region().trim())));

        return toDto(identity);
    }

    private CartDTO toDto(EntraIdentity identity) {
        List<CartDTO.Item> lines = items
                .findByKeyTenantIdAndKeyObjectIdOrderByKeySkuAsc(identity.tenantId(), identity.objectId())
                .stream()
                .map(item -> new CartDTO.Item(item.getSku(), item.getProductName(), item.getCategory(),
                        item.getUnitPrice(), item.getQuantity(), item.getUnitPrice() * item.getQuantity()))
                .toList();

        CartDTO.Shipping envio = shipping.findById(new CartOwner(identity.tenantId(), identity.objectId()))
                .map(s -> new CartDTO.Shipping(s.getMethod().name(), s.getRegion(), s.getCommune(), s.getMethod().cost()))
                .orElse(null);

        double subtotal = lines.stream().mapToDouble(CartDTO.Item::subtotal).sum();
        double costoEnvio = envio == null || lines.isEmpty() ? 0.0 : envio.costo();
        return new CartDTO(lines, envio, subtotal, costoEnvio, subtotal + costoEnvio);
    }

    private static InteraccionEvento evento(String type, EntraIdentity identity, Instant now,
                                            InteraccionEvento.ProductoSnapshot producto, Integer cantidad,
                                            InteraccionEvento.EnvioSnapshot envio) {
        return new InteraccionEvento(UUID.randomUUID(), type, InteraccionEvento.VERSION, now.toString(), "carrito",
                usuarioId(identity), producto, cantidad, envio);
    }

    /** Mismo formato que catalog: tenant:object en minusculas. */
    static String usuarioId(EntraIdentity identity) {
        return identity.tenantId() + ":" + identity.objectId();
    }
}
