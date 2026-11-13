package com.camisetas360.testing.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Cuando un controlador responde con sendError (validacion 400, @ResponseStatus 404, 405, 415...),
 * el contenedor hace un dispatch ERROR a /error. Ese dispatch no puede caer en denyAll(): el cliente
 * debe recibir el estado original, no un 401/403 que lo confunda con un problema de permisos.
 * MockMvc no ejecuta ese reenvio por si solo, por eso se simula el dispatch ERROR explicitamente.
 */
public interface ErrorDispatchContract {
    MockMvc mvc();

    private static RequestPostProcessor errorDispatch(int status) {
        return request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/recurso");
            return request;
        };
    }

    @Test default void errorDispatchConservaElEstadoOriginal() throws Exception {
        for (int status : new int[] {400, 404, 405, 415, 422}) {
            mvc().perform(get("/error").with(errorDispatch(status)))
                    .andExpect(status().is(status))
                    .andExpect(jsonPath("$.status").value(status))
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist());
        }
    }

    @Test default void errorNoEsAccesibleComoRutaDirecta() throws Exception {
        mvc().perform(get("/error")).andExpect(status().isUnauthorized());
    }
}
