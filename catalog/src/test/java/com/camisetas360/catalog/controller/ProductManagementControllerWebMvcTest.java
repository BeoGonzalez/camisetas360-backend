package com.camisetas360.catalog.controller;

import com.camisetas360.catalog.dtos.ProductCreateRequest;
import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.dtos.ProductUpdateRequest;
import com.camisetas360.catalog.models.ProductImage;
import com.camisetas360.catalog.security.SecurityConfig;
import com.camisetas360.catalog.service.ProductAlreadyExistsException;
import com.camisetas360.catalog.service.ProductManagementService;
import com.camisetas360.catalog.service.ProductNotFoundException;
import com.camisetas360.catalog.service.UnsupportedImageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProductManagementController.class)
@Import(SecurityConfig.class)
class ProductManagementControllerWebMvcTest {

        private static final String PATH = "/api/v1/catalog/products";
        private static final byte[] PNG = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        private static final String BODY = """
                        {"sku":"PL-ARS-LOC-2728","name":"Camiseta Arsenal Local","team":"Arsenal","kitType":"LOCAL",
                         "category":"Fútbol","league":"Premier League","price":94.99,"stock":10,"description":"Oficial"}
                        """;
        private static final String UPDATE = """
                        {"name":"Camiseta Arsenal Local","team":"Arsenal","kitType":"VISITANTE",
                         "category":"Fútbol","league":"Premier League","price":94.99,"stock":0}
                        """;
        private static final ProductDTO DTO = new ProductDTO("PL-ARS-LOC-2728", "Camiseta Arsenal Local", "Fútbol",
                        94.99, 10, "Oficial", "Premier League", "Arsenal", "LOCAL", null);

        @Autowired
        private MockMvc mvc;

        @MockitoBean
        private JwtDecoder decoder;

        @MockitoBean
        private ProductManagementService service;

        @ParameterizedTest
        @ValueSource(strings = { "ROLE_CATALOG_MANAGER", "ROLE_ADMIN" })
        void create_shouldReturn201WithLocation_forCatalogManagers(String role) throws Exception {
                when(service.create(any(ProductCreateRequest.class))).thenReturn(DTO);
                mvc.perform(post(PATH).with(as(role)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                                .andExpect(status().isCreated())
                                .andExpect(header().string("Location", "/api/v1/catalog/products/PL-ARS-LOC-2728"))
                                .andExpect(jsonPath("$.team").value("Arsenal"))
                                .andExpect(jsonPath("$.kitType").value("LOCAL"));
        }

        @Test
        void create_shouldReturn400WithoutBusinessCall_whenBodyIsInvalid() throws Exception {
                mvc.perform(post(PATH).with(as("ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.replace("\"stock\":10", "\"stock\":-1")))
                                .andExpect(status().isBadRequest());
                mvc.perform(post(PATH).with(as("ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.replace("\"LOCAL\"", "\"TERCERA\"")))
                                .andExpect(status().isBadRequest());
                mvc.perform(post(PATH).with(as("ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.replace("PL-ARS-LOC-2728", "sku con espacios")))
                                .andExpect(status().isBadRequest());
                verifyNoInteractions(service);
        }

        @Test
        void create_shouldReturn409_whenSkuExists() throws Exception {
                when(service.create(any(ProductCreateRequest.class)))
                                .thenThrow(new ProductAlreadyExistsException("PL-ARS-LOC-2728"));
                mvc.perform(post(PATH).with(as("ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                                .andExpect(status().isConflict());
        }

        @Test
        void update_shouldReturn200_andNotFoundForUnknownSku() throws Exception {
                when(service.update(eq("PL-ARS-LOC-2728"), any(ProductUpdateRequest.class))).thenReturn(DTO);
                when(service.update(eq("SKU-X"), any(ProductUpdateRequest.class)))
                                .thenThrow(new ProductNotFoundException("SKU-X"));
                mvc.perform(put(PATH + "/PL-ARS-LOC-2728").with(as("ROLE_CATALOG_MANAGER"))
                                .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.sku").value("PL-ARS-LOC-2728"));
                mvc.perform(put(PATH + "/SKU-X").with(as("ROLE_CATALOG_MANAGER"))
                                .contentType(MediaType.APPLICATION_JSON).content(UPDATE))
                                .andExpect(status().isNotFound());
        }

        @Test
        void delete_shouldReturn204() throws Exception {
                mvc.perform(delete(PATH + "/PL-ARS-LOC-2728").with(as("ROLE_ADMIN"))).andExpect(status().isNoContent());
                verify(service).delete("PL-ARS-LOC-2728");
        }

        @Test
        void putImage_shouldPassBytesToService() throws Exception {
                when(service.putImage("PL-ARS-LOC-2728", PNG)).thenReturn(DTO);
                mvc.perform(put(PATH + "/PL-ARS-LOC-2728/imagen").with(as("ROLE_ADMIN"))
                                .contentType(MediaType.IMAGE_PNG).content(PNG))
                                .andExpect(status().isOk());
                verify(service).putImage("PL-ARS-LOC-2728", PNG);
        }

        @Test
        void putImage_shouldReturn415_forOtherContentTypesOrFakeImages() throws Exception {
                mvc.perform(put(PATH + "/PL-ARS-LOC-2728/imagen").with(as("ROLE_ADMIN"))
                                .contentType("image/svg+xml").content("<svg/>"))
                                .andExpect(status().isUnsupportedMediaType());
                when(service.putImage(eq("PL-ARS-LOC-2728"), any())).thenThrow(new UnsupportedImageException());
                mvc.perform(put(PATH + "/PL-ARS-LOC-2728/imagen").with(as("ROLE_ADMIN"))
                                .contentType(MediaType.IMAGE_PNG).content("no soy png"))
                                .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        void putImage_shouldReturn413WithoutBusinessCall_whenImageIsTooLarge() throws Exception {
                mvc.perform(put(PATH + "/PL-ARS-LOC-2728/imagen").with(as("ROLE_ADMIN"))
                                .contentType(MediaType.IMAGE_JPEG)
                                .content(new byte[ProductManagementService.MAX_IMAGE_BYTES + 1]))
                                .andExpect(status().isContentTooLarge());
                verifyNoInteractions(service);
        }

        @Test
        void getImage_shouldBeReadableByCustomers_withStoredContentType() throws Exception {
                when(service.getImage("PL-ARS-LOC-2728")).thenReturn(new ProductImage(1L, "image/png", PNG));
                mvc.perform(get(PATH + "/PL-ARS-LOC-2728/imagen").with(as("ROLE_CUSTOMER")))
                                .andExpect(status().isOk())
                                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                                .andExpect(content().bytes(PNG))
                                .andExpect(header().string("Cache-Control",
                                                org.hamcrest.Matchers.containsString("private")))
                                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }

        @ParameterizedTest
        @MethodSource("writes")
        void writes_shouldReturn403_forCustomers(MockHttpServletRequestBuilder request) throws Exception {
                mvc.perform(request.with(as("ROLE_CUSTOMER"))).andExpect(status().isForbidden());
                verifyNoInteractions(service);
        }

        @ParameterizedTest
        @MethodSource("writes")
        void writes_shouldReturn403_forManagersWithoutCatalogScope(MockHttpServletRequestBuilder request)
                        throws Exception {
                mvc.perform(request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                                .andExpect(status().isForbidden());
                verifyNoInteractions(service);
        }

        @ParameterizedTest
        @MethodSource("writes")
        void writes_shouldReturn401_withoutToken(MockHttpServletRequestBuilder request) throws Exception {
                mvc.perform(request).andExpect(status().isUnauthorized());
                verifyNoInteractions(service);
        }

        static Stream<MockHttpServletRequestBuilder> writes() {
                return Stream.of(
                                post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY),
                                put(PATH + "/PL-ARS-LOC-2728").contentType(MediaType.APPLICATION_JSON).content(UPDATE),
                                delete(PATH + "/PL-ARS-LOC-2728"),
                                put(PATH + "/PL-ARS-LOC-2728/imagen").contentType(MediaType.IMAGE_PNG).content(PNG),
                                delete(PATH + "/PL-ARS-LOC-2728/imagen"));
        }

        private static JwtRequestPostProcessor as(String role) {
                return jwt().authorities(new SimpleGrantedAuthority("SCOPE_Catalog.Read"),
                                new SimpleGrantedAuthority(role));
        }
}
