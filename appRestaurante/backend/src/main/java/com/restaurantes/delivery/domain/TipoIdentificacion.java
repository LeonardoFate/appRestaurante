package com.restaurantes.delivery.domain;

import com.restaurantes.shared.errors.ReglaDeNegocioException;
import java.util.Set;

/**
 * Tipos de identificacion de un cliente de delivery (codigos de la tabla del SRI: 04 RUC, 05 cedula, 06 pasaporte).
 * El consumidor final (07) no sirve: a un domicilio hay que saber a quien se entrega. Se valida el digito
 * verificador de la cedula y del RUC con el algoritmo publicado (modulo 10 y modulo 11), sin contrastarlo aun con el SRI.
 */
public final class TipoIdentificacion {

    private static final Set<String> VALIDOS = Set.of("04", "05", "06");

    private TipoIdentificacion() {
    }

    public static void validar(String tipo, String identificacion) {
        if (tipo == null || !VALIDOS.contains(tipo)) {
            throw ReglaDeNegocioException.invalido("IDENTIFICACION_TIPO_INVALIDO",
                    "El tipo de identificacion debe ser 04 (RUC), 05 (cedula) o 06 (pasaporte)");
        }
        if (identificacion == null || identificacion.isBlank()) {
            throw ReglaDeNegocioException.invalido("IDENTIFICACION_REQUERIDA", "La identificacion es obligatoria");
        }
        boolean numerica = identificacion.chars().allMatch(c -> c >= '0' && c <= '9');
        if (tipo.equals("05") && !(numerica && cedulaValida(identificacion))) {
            throw ReglaDeNegocioException.invalido("CEDULA_INVALIDA", "La cedula no es valida (10 digitos con digito verificador)");
        }
        if (tipo.equals("04") && !(numerica && rucValido(identificacion))) {
            throw ReglaDeNegocioException.invalido("RUC_INVALIDO", "El RUC no es valido (13 digitos con digito verificador)");
        }
    }

    /** Provincia 01-24 (o 30 para ecuatorianos en el exterior), tercer digito menor que 6 y verificador modulo 10. */
    static boolean cedulaValida(String c) {
        if (c.length() != 10) {
            return false;
        }
        int provincia = Integer.parseInt(c.substring(0, 2));
        if ((provincia < 1 || provincia > 24) && provincia != 30) {
            return false;
        }
        if (digito(c, 2) >= 6) {
            return false;
        }
        int suma = 0;
        for (int i = 0; i < 9; i++) {
            int producto = digito(c, i) * (i % 2 == 0 ? 2 : 1);
            suma += producto > 9 ? producto - 9 : producto;
        }
        return (10 - suma % 10) % 10 == digito(c, 9);
    }

    /**
     * Persona natural (tercer digito &lt; 6): cedula valida + establecimiento. Sociedad privada (9): modulo 11, pesos
     * 4-3-2-7-6-5-4-3-2, verificador en la posicion 10. Entidad publica (6): modulo 11, pesos 3-2-7-6-5-4-3-2, verificador
     * en la posicion 9 y establecimiento de 4 digitos. SIN verificar contra la ficha del SRI (ver riesgos).
     */
    static boolean rucValido(String r) {
        if (r.length() != 13) {
            return false;
        }
        int tercero = digito(r, 2);
        if (tercero < 6) {
            return cedulaValida(r.substring(0, 10)) && Integer.parseInt(r.substring(10)) >= 1;
        }
        if (tercero == 9) {
            return modulo11(r, new int[] {4, 3, 2, 7, 6, 5, 4, 3, 2}) == digito(r, 9) && Integer.parseInt(r.substring(10)) >= 1;
        }
        if (tercero == 6) {
            return modulo11(r, new int[] {3, 2, 7, 6, 5, 4, 3, 2}) == digito(r, 8) && Integer.parseInt(r.substring(9)) >= 1;
        }
        return false;
    }

    private static int modulo11(String s, int[] pesos) {
        int suma = 0;
        for (int i = 0; i < pesos.length; i++) {
            suma += digito(s, i) * pesos[i];
        }
        int resto = suma % 11;
        return resto == 0 ? 0 : 11 - resto;
    }

    private static int digito(String s, int i) {
        return s.charAt(i) - '0';
    }
}
