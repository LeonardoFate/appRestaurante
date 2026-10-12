/**
 * Modulo facturacion: Comprobantes del SRI: secuenciales, XML, firma, autorizacion, notas de credito (ADR-007, ADR-008).
 *
 * <p>Reglas (ADR-001, verificadas por ArchUnit):
 * <ul>
 *   <li>{@code api}: lo unico que otros modulos pueden usar (interfaces, DTO, eventos).</li>
 *   <li>{@code internal}: privado del modulo.</li>
 *   <li>{@code domain}: Java puro, sin Quarkus, Jakarta ni JDBC (ADR-014).</li>
 * </ul>
 */
package com.restaurantes.facturacion;
