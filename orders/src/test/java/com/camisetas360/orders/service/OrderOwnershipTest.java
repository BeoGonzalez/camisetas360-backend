package com.camisetas360.orders.service;
import java.util.*;
import java.time.Instant;
import com.camisetas360.orders.model.*;
import com.camisetas360.orders.repository.OrderRepository;
import com.camisetas360.orders.messaging.OrderEventPublisher;
import com.camisetas360.orders.security.EntraIdentity;
import com.camisetas360.orders.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderOwnershipTest {
 static final EntraIdentity OWNER=new EntraIdentity(UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("22222222-2222-2222-2222-222222222222"));
 final OrderRepository repository=mock(OrderRepository.class);
 final OrderService service=new OrderService(repository,mock(OrderEventPublisher.class));
 Order stored(UUID tenant,UUID object) {
  var order=new Order(); order.setUserEmail("same@example.test"); order.setTenantId(tenant); order.setObjectId(object);
  order.setTotalAmount(20.0); order.setStatus(OrderStatus.CREATED); order.setCreatedAt(Instant.now());
  org.springframework.test.util.ReflectionTestUtils.setField(order,"id",42L);
  when(repository.findById(42L)).thenReturn(Optional.of(order)); return order;
 }
 @Test void ownerCanReadWithoutProvidingContactEmail() {
  stored(OWNER.tenantId(),OWNER.objectId());
  assertThat(service.findById(42L,OWNER).userEmail()).isEqualTo("same@example.test");
 }
 @Test void sameEmailWithAnotherObjectDoesNotGrantAccess() {
  stored(OWNER.tenantId(),UUID.fromString("33333333-3333-3333-3333-333333333333"));
  assertThatThrownBy(()->service.findById(42L,OWNER)).isInstanceOf(OrderNotFoundException.class);
 }
 @Test void sameObjectFromAnotherTenantDoesNotGrantAccess() {
  stored(UUID.fromString("33333333-3333-3333-3333-333333333333"),OWNER.objectId());
  assertThatThrownBy(()->service.findById(42L,OWNER)).isInstanceOf(OrderNotFoundException.class);
 }
 @Test void legacyOrderCannotBeClaimedByMatchingEmail() {
  stored(null,null);
  assertThatThrownBy(()->service.findById(42L,OWNER)).isInstanceOf(OrderNotFoundException.class);
 }
 @Test void listUsesBothImmutableIds() {
  when(repository.findByTenantIdAndObjectIdOrderByCreatedAtDesc(OWNER.tenantId(),OWNER.objectId())).thenReturn(List.of());
  assertThat(service.findByOwner(OWNER)).isEmpty();
  verify(repository).findByTenantIdAndObjectIdOrderByCreatedAtDesc(OWNER.tenantId(),OWNER.objectId());
 }
}
