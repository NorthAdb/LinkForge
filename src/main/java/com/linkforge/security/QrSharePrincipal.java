package com.linkforge.security;

/**
 * C1 wave B (D-C1-2): principal minted by QrShareAuthFilter for a request whose
 * exp+sig share token validates. Carries only the code it opens — a valid signature
 * IS the owner's consent, so no user identity exists on this path.
 */
public record QrSharePrincipal(String code) {
}
