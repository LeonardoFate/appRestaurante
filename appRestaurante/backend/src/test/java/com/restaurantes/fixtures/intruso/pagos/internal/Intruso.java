package com.restaurantes.fixtures.intruso.pagos.internal;

import com.restaurantes.fixtures.intruso.pedidos.internal.Secreto;

/** Fixture de ArchitectureTest: otro modulo que usa lo interno de "pedidos" (violacion). */
public class Intruso {
    Secreto secreto = new Secreto();
}
