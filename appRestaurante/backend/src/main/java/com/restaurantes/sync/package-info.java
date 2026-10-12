/**
 * Modulo sync: Endpoint /v1/sync del POS: deduplicacion y resolucion de conflictos (ADR-004, ADR-011).
 *
 * <p>Reglas (ADR-001, verificadas por ArchUnit):
 * <ul>
 *   <li>{@code api}: lo unico que otros modulos pueden usar (interfaces, DTO, eventos).</li>
 *   <li>{@code internal}: privado del modulo.</li>
 *   <li>{@code domain}: Java puro, sin Quarkus, Jakarta ni JDBC (ADR-014).</li>
 * </ul>
 */
package com.restaurantes.sync;
