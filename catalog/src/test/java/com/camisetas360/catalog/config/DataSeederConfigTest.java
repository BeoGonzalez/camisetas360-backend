package com.camisetas360.catalog.config;

import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataSeederConfigTest {

    @Mock
    private ProductRepository repository;
    private final DataSeederConfig config = new DataSeederConfig();

    // UT-CAT-004
    @Test
    void initDatabase_shouldNotSeed_whenProductsAlreadyExist() throws Exception {
        when(repository.count()).thenReturn(3L);

        config.initDatabase(repository).run();

        verify(repository).count();
        verify(repository, never()).save(any(Product.class));
        verifyNoMoreInteractions(repository);
    }

    // UT-CAT-005
    @Test
    void initDatabase_shouldSeedThreeKitsPerTeam_whenDatabaseIsEmpty() throws Exception {
        when(repository.count()).thenReturn(0L, 288L);

        config.initDatabase(repository).run();

        var captor = ArgumentCaptor.forClass(Product.class);
        verify(repository, times(288)).save(captor.capture());
        var products = captor.getAllValues();
        assertThat(products).allSatisfy(product -> {
            assertThat(product.getName()).startsWith("Camiseta ").contains(" - ");
            assertThat(product.getSku()).matches("FUT-\\d{8}");
            assertThat(product.getPrice()).isPositive();
            assertThat(product.getStock()).isPositive();
            assertThat(product.getDescription()).isNotBlank().contains(product.getCategory());
        });
        var leagueCounts = products.stream().collect(
                Collectors.groupingBy(Product::getCategory, Collectors.counting()));
        assertThat(leagueCounts).isEqualTo(Map.of(
                "Premier League", 60L, "La Liga", 60L, "Serie A", 60L,
                "Bundesliga", 54L, "Ligue 1", 54L));
        var teams = products.stream().collect(
                Collectors.groupingBy(product -> product.getName().split(" - ", 2)[0]));
        assertThat(teams).hasSize(96);
        teams.values().forEach(kits -> {
            assertThat(kits).hasSize(3);
            assertThat(kits).extracting(Product::getName)
                    .anyMatch(name -> name.contains(" - Local "))
                    .anyMatch(name -> name.contains(" - Visita "))
                    .anyMatch(name -> name.contains(" - Tercera Equipación "));
        });
    }

    // UT-CAT-006
    @Test
    void initDatabase_shouldNotSave_whenCountFails() {
        var failure = new DataAccessResourceFailureException("count failed");
        when(repository.count()).thenThrow(failure);

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                () -> config.initDatabase(repository).run())).isSameAs(failure);

        verify(repository).count();
        verifyNoMoreInteractions(repository);
    }

    // UT-CAT-006
    @Test
    void initDatabase_shouldStopSeeding_whenFirstSaveFails() {
        when(repository.count()).thenReturn(0L);
        var failure = new DataAccessResourceFailureException("save failed");
        when(repository.save(any(Product.class))).thenThrow(failure);

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                () -> config.initDatabase(repository).run())).isSameAs(failure);

        verify(repository).count();
        verify(repository, times(1)).save(any(Product.class));
        verifyNoMoreInteractions(repository);
    }
}
