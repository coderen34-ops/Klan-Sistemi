package me.klansistemi.api;

import java.util.UUID;

/** rol: LIDER, YARDIMCI veya UYE. */
public record UyeBilgisi(UUID uuid, String ad, String rol, long katilma) { }
