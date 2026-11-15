package com.camisetas360.catalog.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
public class ImageTooLargeException extends RuntimeException {

    public ImageTooLargeException(int maxBytes) {
        super("La imagen supera el máximo de " + maxBytes + " bytes");
    }
}
