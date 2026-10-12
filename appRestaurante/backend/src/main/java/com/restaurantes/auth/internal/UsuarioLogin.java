package com.restaurantes.auth.internal;

import java.util.UUID;

/** Datos de un usuario necesarios para validar su login. */
record UsuarioLogin(UUID id, String email, String passwordHash, boolean activo, boolean bloqueado) {
}
