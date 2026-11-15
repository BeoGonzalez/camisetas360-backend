package com.camisetas360.auth.identity;

/** Selecciona el {@link ClaimsMapper} por issuer (comparacion exacta). */
@FunctionalInterface
public interface ClaimsMapperResolver {

    ClaimsMapper forIssuer(String issuer);

    /** Comportamiento historico: todo issuer se interpreta con los claims de Entra ID. */
    static ClaimsMapperResolver entraOnly() {
        var entra = new EntraClaimsMapper();
        return issuer -> entra;
    }
}
