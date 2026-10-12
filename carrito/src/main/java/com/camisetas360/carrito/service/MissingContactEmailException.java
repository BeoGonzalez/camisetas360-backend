package com.camisetas360.carrito.service;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class MissingContactEmailException extends RuntimeException {
 public MissingContactEmailException() { super("A valid contact email is required for checkout notifications"); }
}
