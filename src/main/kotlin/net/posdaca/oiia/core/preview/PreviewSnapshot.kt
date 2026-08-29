package net.posdaca.oiia.core.preview

/**
 * Immutable preview document produced by a domain service.
 *
 * Shared contract for Focus / Tech / GUI / Map:
 * - `*Service.loadSnapshot(...)` returns structure only
 * - `*Service.resolve(...)` fills loc / sprite / resources and returns a new snapshot
 * - `*Panel` consumes the snapshot and handles interaction; it does not parse scripts
 */
interface PreviewSnapshot {
    val isEmpty: Boolean
}