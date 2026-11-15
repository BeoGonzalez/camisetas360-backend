package com.camisetas360.catalog.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** El contenido no es JPEG, PNG ni WebP (se revisan los bytes, no el Content-Type declarado). */
@ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
public class UnsupportedImageException extends RuntimeException {

    public UnsupportedImageException() {
        super("La imagen debe ser JPEG, PNG o WebP");
    }
}
