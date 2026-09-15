package cm.identishield.core.mode;

/**
 * État affiché par la console (EF-36) : le mode sans couche et la disponibilité du PDP. {@code
 * pdpState} vaut {@code DISPONIBLE}, {@code DEGRADE} ou {@code INDISPONIBLE}.
 */
public record ModeStatus(boolean layerless, String pdpState) {}
