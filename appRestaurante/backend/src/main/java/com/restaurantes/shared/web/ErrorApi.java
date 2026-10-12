package com.restaurantes.shared.web;

/** Cuerpo de error comun de la API. Nunca incluye SQL ni detalles internos. */
public record ErrorApi(String codigo, String mensaje) {
}
