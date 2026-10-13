package com.restaurantes.flujo;

import java.util.concurrent.ThreadLocalRandom;

/** Cedulas ecuatorianas VALIDAS (provincia, tercer digito y verificador modulo 10) para los datos de prueba. */
final class Identificaciones {

    private Identificaciones() {
    }

    static String cedulaAleatoria() {
        ThreadLocalRandom azar = ThreadLocalRandom.current();
        StringBuilder c = new StringBuilder();
        c.append(String.format("%02d", azar.nextInt(1, 25)));
        c.append(azar.nextInt(0, 6));
        for (int i = 3; i < 9; i++) {
            c.append(azar.nextInt(0, 10));
        }
        int suma = 0;
        for (int i = 0; i < 9; i++) {
            int producto = (c.charAt(i) - '0') * (i % 2 == 0 ? 2 : 1);
            suma += producto > 9 ? producto - 9 : producto;
        }
        c.append((10 - suma % 10) % 10);
        return c.toString();
    }
}
