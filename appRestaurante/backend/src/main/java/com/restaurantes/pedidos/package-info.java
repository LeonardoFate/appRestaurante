/**
 * Modulo pedidos: Maquina de estados por canal, items, totales e historial (ADR-009). Unico que cambia el estado del pedido.
 *
 * <p>Reglas (ADR-001, verificadas por ArchUnit):
 * <ul>
 *   <li>{@code api}: lo unico que otros modulos pueden usar (interfaces, DTO, eventos).</li>
 *   <li>{@code internal}: privado del modulo.</li>
 *   <li>{@code domain}: Java puro, sin Quarkus, Jakarta ni JDBC (ADR-014).</li>
 * </ul>
 */
package com.restaurantes.pedidos;
